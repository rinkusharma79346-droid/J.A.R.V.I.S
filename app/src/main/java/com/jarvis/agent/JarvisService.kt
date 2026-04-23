package com.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.LinkedList

class JarvisService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisService"
        var instance: JarvisService? = null
        val isRunning = MutableStateFlow(false)
        val status = MutableStateFlow("Idle")
        val currentStep = MutableStateFlow(0)
        val currentAction = MutableStateFlow("—")
        private var taskJob: Job? = null

        fun startTask(task: String) {
            instance?.executeReActLoop(task)
        }

        fun stopTask() {
            isRunning.value = false
            taskJob?.cancel()
            status.value = "Stopped"
            currentAction.value = "—"
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var memory: AgentMemory
    private var consecutiveFailures = 0
    private var consecutiveScreenshotFailures = 0

    override fun onServiceConnected() {
        instance = this
        memory = AgentMemory(getSharedPreferences("jarvis_settings", MODE_PRIVATE))
        status.value = "Ready — Service Connected"
        Log.i(TAG, "JarvisService connected — ready")
        Toast.makeText(this, "JARVIS Service Active", Toast.LENGTH_SHORT).show()

        // Initialize relay client and auto-connect
        RelayClient.init(getSharedPreferences("jarvis_settings", MODE_PRIVATE), this)
        if (RelayClient.isEnabled()) {
            RelayClient.connect()
            Log.i(TAG, "MCP Relay auto-connecting...")
        } else {
            // Auto-enable relay with default URL if not configured yet
            val prefs = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            val hasUrl = prefs.getString("relay_url", "")?.isNotBlank() == true
            if (hasUrl) {
                RelayClient.setEnabled(true)
                RelayClient.connect()
                Log.i(TAG, "MCP Relay auto-enabled and connecting...")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        RelayClient.disconnect()
        instance = null
    }

    // ════════════════════════════════════════════════
    //  MAIN REACT LOOP — THE BRAIN
    // ════════════════════════════════════════════════

    private fun executeReActLoop(task: String) {
        if (isRunning.value) {
            Log.w(TAG, "Already running a task")
            Toast.makeText(this, "Already running a task!", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if API key is configured
        val config = SettingsManager.getConfig(this)
        val hasKey = config.apiKey.isNotBlank() ||
                (config.provider == "gemini") // Gemini has fallback key
        if (!hasKey) {
            status.value = "Error: No API key configured. Go to Settings."
            Toast.makeText(this, "No API key! Open app Settings.", Toast.LENGTH_LONG).show()
            return
        }

        isRunning.value = true
        currentStep.value = 0
        consecutiveFailures = 0
        consecutiveScreenshotFailures = 0
        memory.startTask(task)

        val provider = ProviderFactory.create(config)
        val maxSteps = SettingsManager.getMaxSteps(this)
        val actionDelay = SettingsManager.getActionDelay(this)
        val history = LinkedList<String>()

        taskJob = serviceScope.launch {
            try {
                Log.i(TAG, "═══ STARTING TASK: $task ═══")
                Log.i(TAG, "Provider: ${provider.name} | Model: ${config.model} | MaxSteps: $maxSteps")
                status.value = "Starting: $task"
                currentAction.value = "Initializing..."
                RelayClient.pushStatusUpdate(status.value, 0, currentAction.value)

                // Give the system a moment to settle
                delay(1500L)

                for (step in 1..maxSteps) {
                    if (!isRunning.value) break
                    currentStep.value = step
                    status.value = "Step $step: Capturing screen..."
                    delay(400)

                    // ─── CAPTURE SCREENSHOT (suspend function handles threading) ───
                    val b64 = captureScreen()
                    if (b64 == null) {
                        consecutiveScreenshotFailures++
                        Log.w(TAG, "Step $step: Screenshot failed ($consecutiveScreenshotFailures/5)")
                        if (consecutiveScreenshotFailures >= 5) {
                            status.value = "Failed: Cannot capture screen after 5 attempts"
                            currentAction.value = "FAILED: Screen capture unavailable"
                            break
                        }
                        delay(1000L)
                        continue
                    }
                    consecutiveScreenshotFailures = 0

                    // ─── READ UI TREE (Main thread) ───
                    val rootNode = withContext(Dispatchers.Main) { rootInActiveWindow }
                    val uiTree = withContext(Dispatchers.Main) { parseUITree(rootNode) }

                    if (uiTree.isBlank()) {
                        Log.w(TAG, "Step $step: Empty UI tree — retrying")
                        delay(800L)
                        continue
                    }

                    // ─── STUCK DETECTION ───
                    history.add(uiTree.take(500))
                    if (history.size > 3) history.removeFirst()
                    if (history.size == 3 && history.distinct().size == 1) {
                        status.value = "Stuck detected — pressing BACK"
                        currentAction.value = "Auto: BACK (stuck recovery)"
                        RelayClient.pushStatusUpdate(status.value, step, currentAction.value)
                        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
                        history.clear()
                        delay(1200L)
                        continue
                    }

                    // ─── ASK AI FOR NEXT ACTION ───
                    status.value = "Step $step: Thinking..."
                    RelayClient.pushStatusUpdate(status.value, step, "Thinking...")

                    val request = AgentRequest(
                        task = task,
                        step = step,
                        uiTree = uiTree,
                        base64Screenshot = if (config.supportsVision) b64 else "",
                        screenshotMime = "image/jpeg",
                        history = memory.getHistory()
                    )

                    val action = try {
                        provider.getNextAction(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "Provider call failed: ${e.message}")
                        AgentAction(action = "FAIL", reason = "API error: ${e.message}")
                    }

                    Log.i(TAG, "Step $step: ${action.action} — ${action.reason}")

                    // ─── HANDLE FAILURES ───
                    if (action.action == "FAIL") {
                        consecutiveFailures++
                        val errorDetail = action.reason.ifBlank { "Unknown error" }
                        status.value = "Step $step: Failed — $errorDetail"
                        currentAction.value = "RETRY: $errorDetail"
                        RelayClient.pushStatusUpdate(status.value, step, currentAction.value)

                        if (consecutiveFailures >= 3) {
                            status.value = "Failed after $consecutiveFailures consecutive errors"
                            currentAction.value = "FAILED: $errorDetail"
                            break
                        }
                        delay(2000L)
                        continue
                    }
                    consecutiveFailures = 0

                    // ─── RECORD IN MEMORY ───
                    memory.record(step, action)

                    currentAction.value = "${action.action}: ${action.reason}"
                    status.value = "Step $step: ${action.action} (${action.reason})"
                    RelayClient.pushStatusUpdate(status.value, step, currentAction.value)

                    // ─── EXECUTE ACTION (Main thread) ───
                    withContext(Dispatchers.Main) { executeAction(action) }

                    // ─── CHECK IF DONE ───
                    if (action.action == "DONE") {
                        status.value = "Task Complete: ${action.reason}"
                        currentAction.value = "DONE: ${action.reason}"
                        break
                    }

                    // ─── ACTION-SPECIFIC DELAY ───
                    val delayMs = when (action.action) {
                        "TAP" -> actionDelay
                        "LONG_PRESS" -> actionDelay + 400L
                        "SWIPE", "SCROLL" -> actionDelay + 400L
                        "TYPE" -> actionDelay - 200L
                        "OPEN_APP" -> 2500L
                        "PRESS_BACK", "PRESS_HOME", "PRESS_RECENTS" -> actionDelay + 200L
                        else -> actionDelay
                    }
                    delay(delayMs.coerceAtLeast(400))
                }
            } catch (e: CancellationException) {
                status.value = "Task Cancelled"
                currentAction.value = "—"
            } catch (e: Exception) {
                Log.e(TAG, "Task error", e)
                status.value = "Error: ${e.message}"
                currentAction.value = "ERROR: ${e.message}"
            } finally {
                isRunning.value = false
                RelayClient.pushStatusUpdate(status.value, currentStep.value, currentAction.value)
            }
        }
    }

    // ════════════════════════════════════════════════
    //  SCREENSHOT CAPTURE
    // ════════════════════════════════════════════════

    /**
     * Suspend-friendly screenshot capture.
     * Uses suspendCancellableCoroutine to avoid blocking the Main thread.
     */
    private suspend fun captureScreen(): String? = withContext(Dispatchers.Main) {
        try {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBitmap = result.hardwareBuffer?.let { hb ->
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            }
                            if (hwBitmap == null) {
                                Log.e(TAG, "wrapHardwareBuffer returned null")
                                cont.resume(null) {}
                                return
                            }

                            val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                            result.hardwareBuffer.close()

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to copy hardware bitmap")
                                cont.resume(null) {}
                                return
                            }

                            val newW = (bitmap.width / 3).coerceAtLeast(200)
                            val newH = (bitmap.height / 3).coerceAtLeast(200)
                            val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                            bitmap.recycle()
                            if (scaled !== bitmap) scaled.recycle()

                            cont.resume(base64) {}
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed: ${e.message}")
                            cont.resume(null) {}
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with code: $errorCode")
                        cont.resume(null) {}
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen exception: ${e.message}")
            null
        }
    }

    /**
     * Public wrapper for relay client to capture screenshots on demand.
     */
    suspend fun captureScreenPublic(): String? = captureScreen()

    // ════════════════════════════════════════════════
    //  UI TREE PARSER
    // ════════════════════════════════════════════════

    private fun parseUITree(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = java.lang.StringBuilder()
        fun traverse(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18) return
            val indent = " ".repeat(depth * 2)
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val resId = n.viewIdResourceName ?: ""
            val clickable = n.isClickable
            val editable = n.isEditable
            val scrollable = n.isScrollable
            val checkable = n.isCheckable
            val checked = n.isChecked

            if (text.isNotBlank() || desc.isNotBlank() || clickable || editable || scrollable || resId.isNotBlank()) {
                val flags = buildString {
                    if (clickable) append(" click")
                    if (editable) append(" edit")
                    if (scrollable) append(" scroll")
                    if (checkable) append(" check=${checked}")
                }
                sb.append("$indent[${n.className}] text='$text' desc='$desc' resId='$resId' bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]$flags\n")
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { traverse(it, depth + 1) }
            }
        }
        traverse(node, 0)
        return sb.toString().take(12000)
    }

    // ════════════════════════════════════════════════
    //  ACTION EXECUTOR
    // ════════════════════════════════════════════════

    private fun executeAction(action: AgentAction) {
        try {
            when (action.action) {
                "TAP" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
                    dispatchGesture(gesture, null, null)
                    Log.d(TAG, "TAP at (${action.x}, ${action.y})")
                }

                "SWIPE", "SCROLL" -> {
                    val path = Path().apply {
                        moveTo(action.x.toFloat(), action.y.toFloat())
                        lineTo(action.x2.toFloat(), action.y2.toFloat())
                    }
                    val duration = if (action.action == "SCROLL") 500L else 400L
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                        .build()
                    dispatchGesture(gesture, null, null)
                    Log.d(TAG, "${action.action} from (${action.x},${action.y}) to (${action.x2},${action.y2})")
                }

                "TYPE" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val tapGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
                    dispatchGesture(tapGesture, null, null)

                    Thread.sleep(300)

                    val node = findNodeAt(rootInActiveWindow, action.x, action.y)
                    if (node != null && node.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                action.text
                            )
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Log.d(TAG, "TYPE via SET_TEXT: '${action.text}' at (${action.x},${action.y})")
                    } else if (node != null) {
                        val clip = android.content.ClipData.newPlainText("jarvis", action.text)
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(clip)
                        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Log.d(TAG, "TYPE via PASTE: '${action.text}' at (${action.x},${action.y})")
                    } else {
                        Log.w(TAG, "TYPE: No editable node found at (${action.x},${action.y})")
                    }
                }

                "OPEN_APP" -> {
                    val pm = packageManager
                    val intent = pm.getInstalledApplications(0).find {
                        pm.getApplicationLabel(it).toString().equals(action.app, ignoreCase = true)
                    }?.let { pm.getLaunchIntentForPackage(it.packageName) }
                        ?: pm.getLaunchIntentForPackage(action.app)

                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        Log.d(TAG, "OPEN_APP: ${action.app}")
                    } else {
                        Log.w(TAG, "OPEN_APP: App not found — ${action.app}")
                        Toast.makeText(this, "App not found: ${action.app}", Toast.LENGTH_SHORT).show()
                    }
                }

                "LONG_PRESS" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
                        .build()
                    dispatchGesture(gesture, null, null)
                    Log.d(TAG, "LONG_PRESS at (${action.x}, ${action.y})")
                }

                "PRESS_BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "PRESS_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "PRESS_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                else -> Log.w(TAG, "Unknown action: ${action.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAction failed: ${e.message}", e)
        }
    }

    private fun findNodeAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until root.childCount) {
            val child = findNodeAt(root.getChild(i), x, y)
            if (child != null) return child
        }
        return if (root.isEditable || root.isClickable) root else null
    }
}
