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
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        status.value = "Service Connected"
        Log.i(TAG, "JarvisService connected — ready")
        Toast.makeText(this, "JARVIS Service Active", Toast.LENGTH_SHORT).show()
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
        instance = null
    }

    private fun executeReActLoop(task: String) {
        if (isRunning.value) {
            Log.w(TAG, "Already running a task")
            return
        }

        isRunning.value = true
        currentStep.value = 0
        val history = LinkedList<String>()

        taskJob = serviceScope.launch {
            try {
                Log.i(TAG, "═══ STARTING TASK: $task ═══")
                status.value = "Starting: $task"

                // Give the system a moment to settle
                delay(1000L)

                for (step in 1..50) {
                    if (!isRunning.value) break
                    currentStep.value = step
                    status.value = "Step $step: Capturing..."
                    delay(600)

                    // Capture screenshot on Main thread
                    val b64 = withContext(Dispatchers.Main) { captureScreen() }
                    if (b64 == null) {
                        Log.e(TAG, "Step $step: Screenshot failed — skipping")
                        continue
                    }

                    // Read UI tree on Main thread
                    val uiTree = withContext(Dispatchers.Main) { parseUITree(rootInActiveWindow) }

                    // Stuck detection
                    history.add(uiTree)
                    if (history.size > 3) history.removeFirst()
                    if (history.size == 3 && history[0] == history[1] && history[1] == history[2]) {
                        status.value = "Stuck detected! Pressing BACK."
                        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
                        history.clear()
                        delay(1000)
                        continue
                    }

                    status.value = "Step $step: Thinking..."
                    val action = GeminiClient.getNextAction(task, step, uiTree, b64)
                    Log.i(TAG, "Step $step: ${action.action} — ${action.reason}")

                    if (action.action == "FAIL") {
                        val errorDetail = if (action.reason.isNotBlank()) action.reason else "Invalid JSON or API structure."
                        status.value = "Failed at Step $step: $errorDetail"
                        currentAction.value = "FAILED: $errorDetail"
                        break
                    }

                    currentAction.value = "${action.action}: ${action.reason}"
                    status.value = "Step $step: ${action.action} (${action.reason})"

                    // Execute gesture on Main thread
                    withContext(Dispatchers.Main) { executeAction(action) }

                    if (action.action == "DONE") {
                        status.value = "Task Complete: ${action.reason}"
                        currentAction.value = "DONE: ${action.reason}"
                        break
                    }
                }
            } catch (e: CancellationException) {
                status.value = "Task Cancelled"
            } catch (e: Exception) {
                Log.e(TAG, "Task error", e)
                status.value = "Error: ${e.message}"
            } finally {
                isRunning.value = false
            }
        }
    }

    private fun captureScreen(): String? {
        var resultBase64: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hwBitmap = result.hardwareBuffer?.let { hb ->
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    }
                    if (hwBitmap == null) {
                        Log.e(TAG, "wrapHardwareBuffer returned null")
                        latch.countDown()
                        return
                    }

                    // Copy to software bitmap for compression
                    val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    hwBitmap.recycle()
                    result.hardwareBuffer.close()

                    if (bitmap == null) {
                        Log.e(TAG, "Failed to copy hardware bitmap")
                        latch.countDown()
                        return
                    }

                    // Scale down to reduce base64 size
                    val halfW = (bitmap.width / 2).coerceAtLeast(1)
                    val halfH = (bitmap.height / 2).coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, halfW, halfH, true)

                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    resultBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                    bitmap.recycle()
                    if (scaled !== bitmap) scaled.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot processing failed: ${e.message}")
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "Screenshot failed with code: $errorCode")
                latch.countDown()
            }
        })

        // Wait up to 3 seconds for screenshot
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return resultBase64
    }

    private fun parseUITree(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = java.lang.StringBuilder()
        fun traverse(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15) return
            val indent = " ".repeat(depth * 2)
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val resId = n.viewIdResourceName ?: ""
            if (text.isNotBlank() || desc.isNotBlank() || n.isClickable || n.isEditable || resId.isNotBlank()) {
                sb.append("$indent[${n.className}] text='$text' desc='$desc' resId='$resId' bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}] click=${n.isClickable} edit=${n.isEditable}\n")
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { traverse(it, depth + 1) }
            }
        }
        traverse(node, 0)
        return sb.toString().take(10000)
    }

    private fun executeAction(action: AgentAction) {
        when (action.action) {
            "TAP" -> {
                val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
                dispatchGesture(gesture, null, null)
            }
            "SWIPE", "SCROLL" -> {
                val path = Path().apply {
                    moveTo(action.x.toFloat(), action.y.toFloat())
                    lineTo(action.x2.toFloat(), action.y2.toFloat())
                }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build()
                dispatchGesture(gesture, null, null)
            }
            "TYPE" -> {
                // First click to focus
                val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
                dispatchGesture(gesture, null, null)

                // Then set text via accessibility
                val node = findNodeAt(rootInActiveWindow, action.x, action.y)
                if (node != null && node.isEditable) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else if (node != null) {
                    // Fallback: clipboard paste
                    val clip = android.content.ClipData.newPlainText("jarvis", action.text)
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(clip)
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }
            "OPEN_APP" -> {
                val pm = packageManager
                val intent = pm.getInstalledApplications(0).find {
                    pm.getApplicationLabel(it).toString().equals(action.app, ignoreCase = true)
                }?.let { pm.getLaunchIntentForPackage(it.packageName) }
                    ?: packageManager.getLaunchIntentForPackage(action.app)  // Try as package name
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            "PRESS_BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "PRESS_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
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
