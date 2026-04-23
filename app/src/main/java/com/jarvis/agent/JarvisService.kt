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

                delay(1500L)

                for (step in 1..maxSteps) {
                    if (!isRunning.value) break
                    currentStep.value = step
                    status.value = "Step $step: Capturing screen..."
                    delay(400)

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

                    val rootNode = withContext(Dispatchers.Main) { rootInActiveWindow }
                    val uiTree = withContext(Dispatchers.Main) { parseUITree(rootNode) }

                    if (uiTree.isBlank()) {
                        Log.w(TAG, "Step $step: Empty UI tree — retrying")
                        delay(800L)
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
                        delay(1200L)
                        continue
                    }

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
    //  AUTO-CAPTURE — Returns screenshot + UI tree after action
    // ════════════════════════════════════════════════

    suspend fun autoCapture(): Pair<String?, String?> {
        delay(400) // Wait for UI to settle after action
        val b64 = captureScreen()
        val uiTree = try { parseUITree(rootInActiveWindow) } catch (e: Exception) { null }
        return Pair(b64, uiTree)
    }

    // ════════════════════════════════════════════════
    //  DIRECT ACTION EXECUTION (for MCP remote control)
    // ════════════════════════════════════════════════

    fun executeDirectAction(action: String, x: Int, y: Int, x2: Int, y2: Int, text: String, duration: Long = 0L) {
        val agentAction = AgentAction(
            action = action,
            x = x, y = y,
            x2 = x2, y2 = y2,
            text = text
        )
        executeAction(agentAction)
    }

    // ════════════════════════════════════════════════
    //  SEQUENCE EXECUTION — Batch actions, no round-trips
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
                }
            }

            // Delay between actions
            val delayMs = if (sa.delay > 0) sa.delay else when (sa.action) {
                "TAP" -> 300L
                "LONG_PRESS" -> 700L
                "SWIPE", "SCROLL" -> 600L
                "TYPE" -> 500L
                "OPEN_APP" -> 2000L
                "PRESS_BACK", "PRESS_HOME", "PRESS_RECENTS" -> 500L
                "WAIT" -> 1000L
                else -> 400L
            }
            delay(delayMs)
        }

        mcpState.value = mcpState.value.copy(isExecuting = false, currentCommand = "sequence_done")
        return autoCapture()
    }

    // ════════════════════════════════════════════════
    //  CHROME URL MACRO — Opens Chrome, types URL, loads page
    //  Runs entirely on phone with zero round-trips during execution
    // ════════════════════════════════════════════════

    suspend fun openChromeUrl(url: String): Pair<String?, String?> {
        mcpState.value = McpCommandState(isExecuting = true, currentCommand = "open_chrome_url", lastAction = "Opening Chrome...")

        // Step 1: Find and open Chrome
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
            // Fallback: use ACTION_VIEW with Chrome package
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage("com.android.chrome")
                    startActivity(intent)
                } catch (e: Exception) {
                    // Final fallback: open without specifying package
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            // If we used the fallback, the page is already loading — wait and capture
            delay(3000)
            mcpState.value = mcpState.value.copy(isExecuting = false)
            return autoCapture()
        }

        delay(2000) // Wait for Chrome to open

        mcpState.value = mcpState.value.copy(lastAction = "Tapping URL bar...")

        // Step 2: Find the URL bar in the UI tree and tap it
        val urlBarBounds = withContext(Dispatchers.Main) { findUrlBarBounds() }
        if (urlBarBounds != null) {
            val centerX = (urlBarBounds.left + urlBarBounds.right) / 2
            val centerY = (urlBarBounds.top + urlBarBounds.bottom) / 2

            // Tap URL bar
            withContext(Dispatchers.Main) {
                executeDirectAction("TAP", centerX, centerY, 0, 0, "")
            }
            delay(800) // Wait for keyboard + URL bar focus

            mcpState.value = mcpState.value.copy(lastAction = "Typing URL...")

            // Step 3: Type the URL using clipboard paste (most reliable method)
            withContext(Dispatchers.Main) {
                typeWithClipboard(url)
            }
            delay(500)

            // Step 4: Press Enter by finding the "Go" or "Enter" key on keyboard, or use dispatchGesture
            mcpState.value = mcpState.value.copy(lastAction = "Pressing Enter...")
            withContext(Dispatchers.Main) {
                pressEnterKey()
            }

            // Step 5: Wait for page to load
            delay(4000)
        } else {
            // Couldn't find URL bar — try using ACTION_VIEW with Chrome package as fallback
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
            delay(3000)
        }

        mcpState.value = mcpState.value.copy(isExecuting = false, lastAction = "Chrome URL done")
        return autoCapture()
    }

    // ════════════════════════════════════════════════
    //  ENHANCED TYPE — Keyboard injection / clipboard paste
    //  Works with Chrome URL bar and non-standard text fields
    // ════════════════════════════════════════════════

    fun typeWithClipboard(text: String) {
        try {
            val clip = android.content.ClipData.newPlainText("jarvis", text)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(clip)

            // Find the currently focused node
            val rootNode = rootInActiveWindow ?: return
            val focusedNode = findFocusedNode(rootNode)

            if (focusedNode != null) {
                // Try ACTION_PASTE on the focused node
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "TYPE via clipboard PASTE on focused node: '$text'")
            } else {
                // Fallback: try to find any editable node at the center of screen
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
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val isClickable = node.isClickable

            // Chrome URL bar identifiers
            val isUrlBar = resId.contains("url_bar", ignoreCase = true) ||
                    resId.contains("search_box", ignoreCase = true) ||
                    resId.contains("omnibox", ignoreCase = true) ||
                    resId.contains("toolbar", ignoreCase = true) ||
                    resId.contains("location_bar", ignoreCase = true) ||
                    (className.contains("EditText", ignoreCase = true) &&
                            (resId.contains("chrome", ignoreCase = true) ||
                                    resId.contains("com.android.chrome", ignoreCase = true))) ||
                    // Mobile Chrome: the URL bar is typically an EditText at the top of screen
                    (className.contains("EditText", ignoreCase = true) &&
                            rect.top < 300 && rect.width() > 400 && isClickable)

            if (isUrlBar) {
                candidates.add(Pair(node, rect))
            }

            for (i in 0 until node.childCount) {
                search(node.getChild(i))
            }
        }

        search(rootNode)

        // Return the most likely URL bar (top-most, widest EditText)
        return candidates
            .sortedWith(compareByDescending<Pair<AccessibilityNodeInfo, android.graphics.Rect>> { it.second.width() }
                .thenBy { it.second.top })
            .firstOrNull()?.second
    }

    private fun pressEnterKey() {
        // Try to find and click the "Go" or "Enter" button on the keyboard
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
                if (searchEnter(node.getChild(i))) return true
            }
            return false
        }

        if (!searchEnter(rootNode)) {
            // Fallback: use IME action by finding the focused node and performing ACTION_IME_ACTION
            val focused = findFocusedNode(rootNode)
            if (focused != null) {
                focused.performAction(AccessibilityNodeInfo.ACTION_IME_ACTION)
                Log.d(TAG, "Pressed Enter via IME ACTION")
            } else {
                Log.w(TAG, "Could not find Enter key or focused node")
            }
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

    private fun executeAction(action: AgentAction) {
        try {
            when (action.action) {
                "TAP" -> {
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
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
                    // Step 1: Tap at the coordinates to focus the field
                    val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                    val tapGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                        .build()
                    dispatchGesture(tapGesture, null, null)

                    Thread.sleep(400)

                    // Step 2: Try SET_TEXT first (works for standard EditText)
                    val node = findNodeAt(rootInActiveWindow, action.x, action.y)
                    var typed = false

                    if (node != null && node.isEditable) {
                        // Try SET_TEXT
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                action.text
                            )
                        }
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        if (result) {
                            typed = true
                            Log.d(TAG, "TYPE via SET_TEXT: '${action.text}' at (${action.x},${action.y})")
                        }
                    }

                    // Step 3: If SET_TEXT failed, use clipboard paste (works with Chrome URL bar)
                    if (!typed) {
                        val focusedNode = findFocusedNode(rootInActiveWindow)
                        val clip = android.content.ClipData.newPlainText("jarvis", action.text)
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(clip)

                        val targetNode = focusedNode ?: node
                        if (targetNode != null) {
                            val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            if (pasteResult) {
                                typed = true
                                Log.d(TAG, "TYPE via PASTE: '${action.text}' at (${action.x},${action.y})")
                            }
                        }

                        // Final fallback: try PASTE on the node at position
                        if (!typed && node != null && node !== targetNode) {
                            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            Log.d(TAG, "TYPE via PASTE (fallback): '${action.text}' at (${action.x},${action.y})")
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
