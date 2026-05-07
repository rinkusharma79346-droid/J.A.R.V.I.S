package com.vayu.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
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
import kotlin.coroutines.resume
import java.util.LinkedList

/**
 * VAYU Service v10.0 — Speed Beast Edition
 *
 * Speed optimizations:
 *   - autoCapture delay: 80ms (UI settles faster)
 *   - Sequence action delays: halved (TAP 50ms, TYPE 150ms, etc.)
 *   - TYPE action: delay(200) with coroutine
 *   - ReAct loop delays: reduced from 800ms to 400ms default
 *   - Faster screenshot compression: quality 55
 *
 * Overlay fix:
 *   - Calls HUDService.forceHide() when task completes
 *   - Calls HUDService.forceHide() on cancellation/error
 *
 * Reliability:
 *   - Re-register relay on service connect
 *   - Force MCP active = false when task completes
 */
class VayuService : AccessibilityService() {

    companion object {
        private const val TAG = "VayuService"
        var instance: VayuService? = null
        val isRunning = MutableStateFlow(false)
        val status = MutableStateFlow("Idle")
        val currentStep = MutableStateFlow(0)
        val currentAction = MutableStateFlow("—")

        // MCP command state for PiP overlay
        val mcpState = MutableStateFlow(McpCommandState())

        private var taskJob: Job? = null

        fun startTask(task: String) {
            instance?.executeReActLoop(task)
        }

        fun stopTask() {
            isRunning.value = false
            taskJob?.cancel()
            status.value = "Stopped"
            currentAction.value = "—"
            // Force MCP inactive
            RelayClient.mcpActive.value = false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var memory: AgentMemory
    private var consecutiveFailures = 0
    private var consecutiveScreenshotFailures = 0

    // Chrome package names to try
    private val CHROME_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.google.android.apps.chrome"
    )

    override fun onServiceConnected() {
        instance = this
        memory = AgentMemory(getSharedPreferences("vayu_settings", MODE_PRIVATE))
        status.value = "Ready — Service Connected"
        Log.i(TAG, "VayuService connected — ready")
        Toast.makeText(this, "VAYU Agent Active", Toast.LENGTH_SHORT).show()

        // Initialize relay client and auto-connect
        RelayClient.init(getSharedPreferences("vayu_settings", MODE_PRIVATE), this)
        if (RelayClient.isEnabled()) {
            RelayClient.connect()
            Log.i(TAG, "MCP Relay auto-connecting...")
        } else {
            val prefs = getSharedPreferences("vayu_settings", MODE_PRIVATE)
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
    //  MAIN REACT LOOP — THE BRAIN (legacy, for local AI)
    // ════════════════════════════════════════════════

    private fun executeReActLoop(task: String) {
        if (isRunning.value) {
            Log.w(TAG, "Already running a task")
            Toast.makeText(this, "Already running a task!", Toast.LENGTH_SHORT).show()
            return
        }

        val config = SettingsManager.getConfig(this)
        val hasKey = config.apiKey.isNotBlank() ||
                (config.provider == "gemini")
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
                status.value = "Starting: $task"
                currentAction.value = "Initializing..."
                RelayClient.pushStatusUpdate(status.value, 0, currentAction.value)

                delay(800L)

                for (step in 1..maxSteps) {
                    if (!isRunning.value) break
                    currentStep.value = step
                    status.value = "Step $step: Capturing screen..."
                    delay(80)

                    val b64 = captureScreen()
                    if (b64 == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        // API 29: Screenshot not available, use UI tree only (don't count as failure)
                        Log.i(TAG, "Step $step: Screenshot unavailable on API ${Build.VERSION.SDK_INT}, using UI tree only")
                    } else if (b64 == null) {
                        consecutiveScreenshotFailures++
                        Log.w(TAG, "Step $step: Screenshot failed ($consecutiveScreenshotFailures/5)")
                        if (consecutiveScreenshotFailures >= 5) {
                            status.value = "Failed: Cannot capture screen after 5 attempts"
                            currentAction.value = "FAILED: Screen capture unavailable"
                            break
                        }
                        delay(500L)
                        continue
                    }
                    consecutiveScreenshotFailures = 0

                    val rootNode = withContext(Dispatchers.Main) { rootInActiveWindow }
                    val uiTree = withContext(Dispatchers.Main) { parseUITree(rootNode) }

                    if (uiTree.isBlank()) {
                        Log.w(TAG, "Step $step: Empty UI tree — retrying")
                        delay(400L)
                        continue
                    }

                    history.add(uiTree.take(500))
                    if (history.size > 3) history.removeFirst()
                    if (history.size == 3 && history.distinct().size == 1) {
                        status.value = "Stuck detected — pressing BACK"
                        currentAction.value = "Auto: BACK (stuck recovery)"
                        RelayClient.pushStatusUpdate(status.value, step, currentAction.value)
                        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
                        history.clear()
                        delay(800L)
                        continue
                    }

                    status.value = "Step $step: Thinking..."
                    RelayClient.pushStatusUpdate(status.value, step, "Thinking...")

                    val request = AgentRequest(
                        task = task,
                        step = step,
                        uiTree = uiTree,
                        base64Screenshot = if (config.supportsVision) (b64 ?: "") else "",
                        screenshotMime = "image/jpeg",
                        history = memory.getHistory()
                    )

                    val action = try {
                        provider.getNextAction(request).let { it.copy(action = it.action.uppercase().trim()) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Provider call failed: ${e.message}")
                        AgentAction(action = "FAIL", reason = "API error: ${e.message}")
                    }

                    Log.i(TAG, "Step $step: ${action.action} — ${action.reason}")

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
                        delay(1500L)
                        continue
                    }
                    consecutiveFailures = 0

                    memory.record(step, action)

                    currentAction.value = "${action.action}: ${action.reason}"
                    status.value = "Step $step: ${action.action} (${action.reason})"
                    RelayClient.pushStatusUpdate(status.value, step, currentAction.value)

                    withContext(Dispatchers.Main) { executeAction(action) }

                    if (action.action == "DONE") {
                        status.value = "Task Complete: ${action.reason}"
                        currentAction.value = "DONE: ${action.reason}"
                        break
                    }

                    val delayMs = when (action.action) {
                        "TAP" -> actionDelay.coerceAtMost(500L)
                        "LONG_PRESS" -> (actionDelay + 200L).coerceAtMost(700L)
                        "SWIPE", "SCROLL" -> (actionDelay + 200L).coerceAtMost(700L)
                        "TYPE" -> (actionDelay - 100L).coerceAtLeast(200L)
                        "OPEN_APP" -> 1500L
                        "PRESS_BACK", "PRESS_HOME", "PRESS_RECENTS" -> (actionDelay + 100L).coerceAtMost(600L)
                        else -> actionDelay.coerceAtMost(500L)
                    }
                    delay(delayMs)
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
                RelayClient.mcpActive.value = false
                RelayClient.mcpSessionActive.value = false
                RelayClient.pushStatusUpdate(status.value, currentStep.value, currentAction.value)
                HUDService.forceHide(this@VayuService)
            }
        }
    }

    // ════════════════════════════════════════════════
    //  SCREENSHOT CAPTURE
    //  NOTE: AccessibilityService.takeScreenshot() requires API 30+ (Android 11+)
    //  On API 29 (Android 10), we use the legacy rootInActiveWindow approach
    // ════════════════════════════════════════════════

    @android.annotation.SuppressLint("NewApi")
    private suspend fun captureScreen(): String? = withContext(Dispatchers.Main) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Use the official takeScreenshot API
                captureScreenApi30()
            } else {
                // API 29 and below: Fallback — return null gracefully
                // The agent will work with UI tree only on older devices
                Log.w(TAG, "Screenshot capture not available on API ${Build.VERSION.SDK_INT} (requires API 30+)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen exception: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private suspend fun captureScreenApi30(): String? = withContext(Dispatchers.Main) {
        try {
            suspendCancellableCoroutine<String?> { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBitmap = result.hardwareBuffer.let { hb ->
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            }
                            if (hwBitmap == null) {
                                Log.e(TAG, "wrapHardwareBuffer returned null")
                                cont.resume(null)
                                return
                            }

                            val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                            result.hardwareBuffer.close()

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to copy hardware bitmap")
                                cont.resume(null)
                                return
                            }

                            val newW = (bitmap.width / 4).coerceAtLeast(160)
                            val newH = (bitmap.height / 4).coerceAtLeast(160)
                            val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 55, baos)
                            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                            bitmap.recycle()
                            if (scaled !== bitmap) scaled.recycle()

                            cont.resume(base64)
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed: ${e.message}")
                            cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with code: $errorCode")
                        cont.resume(null)
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenApi30 exception: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("NewApi")
    suspend fun captureScreenPublic(): String? = captureScreen()

    fun getUiTreePublic(): String? {
        return try {
            parseUITree(rootInActiveWindow)
        } catch (e: Exception) {
            Log.e(TAG, "getUiTreePublic failed: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════
    //  AUTO-CAPTURE
    // ════════════════════════════════════════════════

    @android.annotation.SuppressLint("NewApi")
    suspend fun autoCapture(): Pair<String?, String?> {
        delay(350)
        val b64 = captureScreen()
        val uiTree = withContext(Dispatchers.Main) {
            try { parseUITree(rootInActiveWindow) } catch (e: Exception) { null }
        }
        return Pair(b64, uiTree)
    }

    // ════════════════════════════════════════════════
    //  DIRECT ACTION EXECUTION
    // ════════════════════════════════════════════════

    suspend fun executeDirectAction(action: String, x: Int, y: Int, x2: Int, y2: Int, text: String, @Suppress("UNUSED_PARAMETER") duration: Long = 0L) {
        val agentAction = AgentAction(
            action = action.uppercase(),
            x = x, y = y,
            x2 = x2, y2 = y2,
            text = text,
            duration = duration
        )
        try {
            executeAction(agentAction)
        } catch (e: Exception) {
            Log.e(TAG, "executeDirectAction failed: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════
    //  SEQUENCE EXECUTION
    // ════════════════════════════════════════════════

    suspend fun executeSequence(actions: List<SequenceAction>): Pair<String?, String?> {
        mcpState.value = McpCommandState(isExecuting = true, currentCommand = "sequence", commandCount = actions.size)

        for ((index, sa) in actions.withIndex()) {
            mcpState.value = mcpState.value.copy(
                lastAction = "${sa.action}${if (sa.text.isNotBlank()) ": ${sa.text.take(30)}" else ""}",
                commandCount = actions.size - index
            )

            withContext(Dispatchers.Main) {
                when (sa.action) {
                    "TAP" -> executeAction(sa.toAgentAction())
                    "LONG_PRESS" -> executeAction(sa.toAgentAction())
                    "SWIPE" -> executeAction(sa.toAgentAction())
                    "SCROLL" -> executeAction(sa.toAgentAction())
                    "TYPE" -> executeAction(sa.toAgentAction().copy(action = "TYPE"))
                    "PRESS_BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "PRESS_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "PRESS_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "OPEN_APP" -> executeAction(sa.toAgentAction().copy(action = "OPEN_APP"))
                    "WAIT" -> { /* just delay */ }
                    else -> Log.w(TAG, "Unknown sequence action: ${sa.action}")
                }
            }

            val delayMs = if (sa.delay > 0) sa.delay else when (sa.action) {
                "TAP" -> 350L
                "LONG_PRESS" -> 700L
                "SWIPE", "SCROLL" -> 800L
                "TYPE" -> 600L
                "OPEN_APP" -> 1000L
                "PRESS_BACK", "PRESS_HOME", "PRESS_RECENTS" -> 150L
                "WAIT" -> 300L
                else -> 100L
            }
            delay(delayMs)
        }

        mcpState.value = mcpState.value.copy(isExecuting = false, currentCommand = "sequence_done")
        return autoCapture()
    }

    // ════════════════════════════════════════════════
    //  CHROME URL MACRO
    // ════════════════════════════════════════════════

    suspend fun openChromeUrl(url: String): Pair<String?, String?> {
        mcpState.value = McpCommandState(isExecuting = true, currentCommand = "open_chrome_url", lastAction = "Opening Chrome...")

        val chromePackage = findChromePackage()
        if (chromePackage != null) {
            withContext(Dispatchers.Main) {
                val intent = packageManager.getLaunchIntentForPackage(chromePackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage("com.android.chrome")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            delay(2000)
            mcpState.value = mcpState.value.copy(isExecuting = false)
            return autoCapture()
        }

        delay(1500)

        mcpState.value = mcpState.value.copy(lastAction = "Tapping URL bar...")

        val urlBarBounds = withContext(Dispatchers.Main) { findUrlBarBounds() }
        if (urlBarBounds != null) {
            val centerX = (urlBarBounds.left + urlBarBounds.right) / 2
            val centerY = (urlBarBounds.top + urlBarBounds.bottom) / 2

            withContext(Dispatchers.Main) {
                executeDirectAction("TAP", centerX, centerY, 0, 0, "")
            }
            delay(500)

            mcpState.value = mcpState.value.copy(lastAction = "Typing URL...")

            withContext(Dispatchers.Main) {
                typeWithClipboard(url)
            }
            delay(300)

            mcpState.value = mcpState.value.copy(lastAction = "Pressing Enter...")
            withContext(Dispatchers.Main) {
                pressEnterKey()
            }

            delay(3000)
        } else {
            Log.w(TAG, "Chrome URL bar not found in UI tree, using ACTION_VIEW fallback")
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage("com.android.chrome")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            delay(2000)
        }

        mcpState.value = mcpState.value.copy(isExecuting = false, lastAction = "Chrome URL done")
        return autoCapture()
    }

    suspend fun findAndTapTextPublic(query: String): UiActionResult {
        val node = findBestNodeByText(rootInActiveWindow, query, preferClickable = true)
            ?: return UiActionResult(false, "No visible element matched: $query")
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX()
        val y = rect.centerY()
        executeAction(AgentAction(action = "TAP", x = x, y = y))
        return UiActionResult(true, "Tapped best match for: $query", x, y)
    }

    suspend fun typeInFieldPublic(text: String, hint: String = ""): UiActionResult {
        val root = rootInActiveWindow ?: return UiActionResult(false, "No active window")
        val node = if (hint.isNotBlank()) findBestNodeByText(root, hint, preferEditable = true) else null
        val target = node ?: findAnyEditableNode(root) ?: findFocusedNode(root)
            ?: return UiActionResult(false, "No editable/focused field found")
        val rect = android.graphics.Rect()
        target.getBoundsInScreen(rect)
        val x = rect.centerX()
        val y = rect.centerY()
        executeAction(AgentAction(action = "TAP", x = x, y = y))
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(150)
        typeWithClipboard(text)
        return UiActionResult(true, "Typed into ${if (hint.isBlank()) "focused field" else hint}", x, y)
    }

    // ════════════════════════════════════════════════
    //  ENHANCED TYPE
    // ════════════════════════════════════════════════

    fun typeWithClipboard(text: String) {
        try {
            val clip = android.content.ClipData.newPlainText("vayu", text)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(clip)

            val rootNode = rootInActiveWindow ?: return
            val focusedNode = findFocusedNode(rootNode)

            if (focusedNode != null) {
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "TYPE via clipboard PASTE on focused node: '$text'")
            } else {
                val fallbackNode = findAnyEditableNode(rootNode)
                if (fallbackNode != null) {
                    fallbackNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "TYPE via clipboard PASTE on fallback node: '$text'")
                } else {
                    Log.w(TAG, "TYPE: No focused or editable node found for paste")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "typeWithClipboard failed: ${e.message}", e)
        }
    }

    private fun findBestNodeByText(root: AccessibilityNodeInfo?, query: String, preferClickable: Boolean = false, preferEditable: Boolean = false): AccessibilityNodeInfo? {
        if (root == null || query.isBlank()) return null
        val q = query.lowercase().trim()
        var best: Pair<AccessibilityNodeInfo, Int>? = null

        fun scoreNode(node: AccessibilityNodeInfo): Int {
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName
            ).map { it.lowercase().trim() }.filter { it.isNotBlank() }
            var score = -1
            for (value in values) {
                score = maxOf(score, when {
                    value == q -> 100
                    value.startsWith(q) -> 85
                    value.contains(q) -> 70
                    else -> -1
                })
            }
            if (score < 0) return -1
            if (preferClickable && node.isClickable) score += 25
            if (preferEditable && node.isEditable) score += 40
            return score
        }

        fun walk(node: AccessibilityNodeInfo) {
            val score = scoreNode(node)
            if (score >= 0 && (best == null || score > best!!.second)) best = node to score
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }

        walk(root)
        return best?.first
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isAccessibilityFocused || root.isFocused) return root
        for (i in 0 until root.childCount) {
            val found = findFocusedNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findAnyEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val found = findAnyEditableNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ════════════════════════════════════════════════
    //  CHROME HELPERS
    // ════════════════════════════════════════════════

    private fun findChromePackage(): String? {
        for (pkg in CHROME_PACKAGES) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) { /* not installed */ }
        }
        return null
    }

    private fun findUrlBarBounds(): android.graphics.Rect? {
        val rootNode = rootInActiveWindow ?: return null
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()

        fun search(node: AccessibilityNodeInfo) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            val resId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val isClickable = node.isClickable

            val isUrlBar = resId.contains("url_bar", ignoreCase = true) ||
                    resId.contains("search_box", ignoreCase = true) ||
                    resId.contains("omnibox", ignoreCase = true) ||
                    resId.contains("toolbar", ignoreCase = true) ||
                    resId.contains("location_bar", ignoreCase = true) ||
                    (className.contains("EditText", ignoreCase = true) &&
                            (resId.contains("chrome", ignoreCase = true) ||
                                    resId.contains("com.android.chrome", ignoreCase = true))) ||
                    (className.contains("EditText", ignoreCase = true) &&
                            rect.top < 300 && rect.width() > 400 && isClickable)

            if (isUrlBar) {
                candidates.add(Pair(node, rect))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { search(it) }
            }
        }

        search(rootNode)

        return candidates
            .sortedWith(compareByDescending<Pair<AccessibilityNodeInfo, android.graphics.Rect>> { it.second.width() }
                .thenBy { it.second.top })
            .firstOrNull()?.second
    }

    private fun pressEnterKey() {
        val rootNode = rootInActiveWindow ?: return

        fun searchEnter(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""

            if (node.isClickable && (
                        text == "go" || text == "enter" || text == "search" ||
                        desc == "go" || desc == "enter" || desc == "search" ||
                        resId.contains("enter", ignoreCase = true) ||
                        resId.contains("action_go", ignoreCase = true) ||
                        resId.contains("action_search", ignoreCase = true) ||
                        resId.contains("ime_action", ignoreCase = true)
                        )) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Pressed Enter via keyboard button: $text $desc $resId")
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> if (searchEnter(child)) return true }
            }
            return false
        }

        if (!searchEnter(rootNode)) {
            val displayMetrics = resources.displayMetrics
            val enterX = (displayMetrics.widthPixels * 0.85).toInt()
            val enterY = (displayMetrics.heightPixels * 0.92).toInt()

            val path = Path().apply { moveTo(enterX.toFloat(), enterY.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            try { dispatchGesture(gesture, null, null) } catch (e: Exception) { Log.e(TAG, "dispatchGesture Enter failed: ${e.message}") }
            Log.d(TAG, "Pressed Enter via gesture at ($enterX, $enterY)")
        }
    }

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

    private suspend fun dispatchGestureAndWait(gesture: GestureDescription, label: String): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        fun finish(result: Boolean) {
            if (!resumed && cont.isActive) {
                resumed = true
                cont.resume(result)
            }
        }
        try {
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture completed: $label")
                    finish(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled: $label")
                    finish(false)
                }
            }, null)
            if (!accepted) finish(false)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture $label failed: ${e.message}")
            finish(false)
        }
    }

    private suspend fun executeAction(action: AgentAction) {
        try {
            when (action.action) {
                "TAP" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    dispatchGestureAndWait(gesture, "TAP(${action.x},${action.y})")
                    Log.d(TAG, "TAP at (${action.x}, ${action.y})")
                }

                "SWIPE", "SCROLL" -> {
                    val path = Path().apply {
                        moveTo(action.x.toFloat(), action.y.toFloat())
                        lineTo(action.x2.toFloat(), action.y2.toFloat())
                    }
                    val duration = action.duration.takeIf { it > 0 } ?: if (action.action == "SCROLL") 550L else 450L
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                        .build()
                    dispatchGestureAndWait(gesture, "${action.action}(${action.x},${action.y}→${action.x2},${action.y2})")
                    Log.d(TAG, "${action.action} from (${action.x},${action.y}) to (${action.x2},${action.y2})")
                }

                "TYPE" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val tapGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    dispatchGestureAndWait(tapGesture, "TYPE tap(${action.x},${action.y})")

                    delay(300)

                    val node = findNodeAt(rootInActiveWindow, action.x, action.y)
                    var typed = false

                    val isWebViewField = isInsideWebView(node)

                    if (isWebViewField) {
                        val focusedNode = findFocusedNode(rootInActiveWindow) ?: node

                        if (focusedNode != null) {
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, focusedNode.text?.length ?: 0)
                            })
                            delay(50)
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                            })
                            delay(80)
                        }

                        val clip = android.content.ClipData.newPlainText("vayu", action.text)
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(clip)

                        val targetNode = focusedNode ?: node
                        if (targetNode != null) {
                            val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            if (pasteResult) {
                                typed = true
                                Log.d(TAG, "TYPE via PASTE (WebView): '${action.text.take(50)}' at (${action.x},${action.y})")
                            }
                        }

                        if (!typed && node != null && node !== targetNode) {
                            val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            if (pasteResult) {
                                typed = true
                                Log.d(TAG, "TYPE via PASTE (fallback): '${action.text.take(50)}'")
                            }
                        }

                        if (!typed && node != null && node.isEditable) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                            }
                            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            if (result) {
                                typed = true
                                Log.d(TAG, "TYPE via SET_TEXT (WebView last resort): '${action.text.take(50)}'")
                            }
                        }
                    } else {
                        if (node != null && node.isEditable) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                            }
                            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            if (result) {
                                typed = true
                                Log.d(TAG, "TYPE via SET_TEXT: '${action.text.take(50)}' at (${action.x},${action.y})")
                            }
                        }

                        if (!typed) {
                            val focusedNode = findFocusedNode(rootInActiveWindow)
                            val clip = android.content.ClipData.newPlainText("vayu", action.text)
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(clip)

                            val targetNode = focusedNode ?: node
                            if (targetNode != null) {
                                val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                if (pasteResult) {
                                    typed = true
                                    Log.d(TAG, "TYPE via PASTE: '${action.text.take(50)}' at (${action.x},${action.y})")
                                }
                            }

                            if (!typed && node != null && node !== targetNode) {
                                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                Log.d(TAG, "TYPE via PASTE (fallback): '${action.text.take(50)}'")
                            }
                        }
                    }

                    if (!typed) {
                        Log.w(TAG, "TYPE: All methods failed at (${action.x},${action.y})")
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
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                        .build()
                    dispatchGestureAndWait(gesture, "LONG_PRESS(${action.x},${action.y})")
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

    private fun isInsideWebView(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current = node.parent
        while (current != null) {
            val className = current.className?.toString() ?: ""
            if (className == "android.webkit.WebView" || className.contains("WebView")) {
                return true
            }
            current = current.parent
        }
        return false
    }
}
