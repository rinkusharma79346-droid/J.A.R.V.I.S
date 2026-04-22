package com.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.LinkedList

class JarvisService : AccessibilityService() {

    companion object {
        var instance: JarvisService? = null
        val isRunning = MutableStateFlow(false)
        val status = MutableStateFlow("Idle")
        val currentStep = MutableStateFlow(0)
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

    override fun onServiceConnected() {
        instance = this
        status.value = "Service Connected"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun executeReActLoop(task: String) {
        isRunning.value = true
        currentStep.value = 0
        val history = LinkedList<String>()

        taskJob = serviceScope.launch {
            try {
                for (step in 1..50) {
                    if (!isRunning.value) break
                    currentStep.value = step
                    status.value = "Step $step: Capturing..."
                    delay(600) // 0.6s delay

                    val b64 = captureScreen() ?: continue
                    val uiTree = parseUITree(rootInActiveWindow)
                    
                    history.add(uiTree)
                    if (history.size > 3) history.removeFirst()
                    if (history.size == 3 && history[0] == history[1] && history[1] == history[2]) {
                        status.value = "Stuck detected! Pressing BACK."
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        history.clear()
                        delay(1000)
                        continue
                    }

                    status.value = "Step $step: Thinking..."
                    val action = GeminiClient.getNextAction(task, step, uiTree, b64)

                    if (action.action == "FAIL") {
                        val errorDetail = if (action.reason.isNotBlank()) action.reason else "Invalid JSON or API structure."
                        status.value = "Failed at Step $step: $errorDetail"
                        isRunning.value = false
                        break
                    }

                    status.value = "Action: ${action.action} (${action.reason})"
                    executeAction(action)
                    
                    if (action.action == "DONE") {
                        isRunning.value = false
                        break
                    }
                }
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                isRunning.value = false
            }
        }
    }

    private suspend fun captureScreen(): String? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                if (bitmap == null) {
                    cont.resumeWith(Result.success(null))
                    return
                }
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos)
                cont.resumeWith(Result.success(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)))
            }
            override fun onFailure(errorCode: Int) {
                cont.resumeWith(Result.success(null))
            }
        })
    }

    private fun parseUITree(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = java.lang.StringBuilder()
        fun traverse(n: AccessibilityNodeInfo, depth: Int) {
            val indent = " ".repeat(depth * 2)
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (text.isNotBlank() || desc.isNotBlank() || n.isClickable || n.isEditable) {
                sb.append("$indent[${n.className}] text='$text' desc='$desc' bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}] click=${n.isClickable}\n")
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
                Thread.sleep(500)
                
                // Then try to inject text via generic traversal (rough approximation for agent)
                val node = findNodeAt(rootInActiveWindow, action.x, action.y)
                if (node != null && node.isEditable) {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            "OPEN_APP" -> {
                val pm = packageManager
                val intent = pm.getInstalledApplications(0).find {
                    pm.getApplicationLabel(it).toString().equals(action.app, ignoreCase = true)
                }?.let { pm.getLaunchIntentForPackage(it.packageName) }
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
        return root
    }
}
