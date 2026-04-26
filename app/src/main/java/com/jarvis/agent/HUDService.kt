package com.jarvis.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * HUD Overlay Service v10.0 — Session-Aware Edition
 *
 * KEY FIX: Overlay stays visible during MCP session, only hides when truly done.
 * - Tracks MCP session (mcpSessionActive) not just individual commands
 * - MCP_FORCE_HIDE_MS: 30s (was 3s) — brain needs time to think
 * - MCP_DONE_HIDE_MS: 5s (was 1.5s) — grace period after session ends
 * - Only hides when BOTH mcpSessionActive=false AND JarvisService.isRunning=false
 * - KILL button sets mcpSessionActive=false for immediate dismissal
 */
class HUDService : Service() {

    companion object {
        private const val TAG = "HUDService"
        private const val MCP_DONE_HIDE_MS = 5_000L    // Hide 5s after MCP session done (was 1.5s)
        private const val MCP_FORCE_HIDE_MS = 30_000L   // Force hide 30s after last MCP activity (was 3s)
        private const val LOCAL_TASK_DONE_HIDE_MS = 1_000L // Hide 1s after local task finishes

        private var isRunning = false
        private var lastMcpCommandTime = 0L
        private var lastLocalTaskTime = 0L

        /**
         * Start the HUD overlay for MCP command processing.
         */
        fun showForMcp(context: Context) {
            if (!isRunning) {
                Log.d(TAG, "showForMcp: Starting HUD service for MCP overlay")
                val intent = Intent(context, HUDService::class.java)
                context.startService(intent)
            } else {
                // Already running — just update timestamp
                lastMcpCommandTime = System.currentTimeMillis()
            }
        }

        /** Stop the HUD overlay from outside. */
        fun hide(context: Context) {
            try {
                context.stopService(Intent(context, HUDService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "hide: ${e.message}")
            }
        }

        /**
         * Called by RelayClient after each MCP command handler completes.
         * Signals that the command is done — overlay should dismiss soon.
         */
        fun mcpCommandDone() {
            lastMcpCommandTime = System.currentTimeMillis()
            Log.d(TAG, "mcpCommandDone: MCP command finished, scheduling auto-hide")
        }

        /**
         * Force hide from anywhere. Used when task is clearly done.
         */
        fun forceHide(context: Context) {
            lastMcpCommandTime = 0L
            lastLocalTaskTime = System.currentTimeMillis()
            try {
                context.stopService(Intent(context, HUDService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "forceHide: ${e.message}")
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var tvStatus: TextView
    private lateinit var tvHudRelay: TextView
    private lateinit var tvHudAction: TextView
    private lateinit var tvHudStep: TextView
    private lateinit var hudDot: View
    private lateinit var btnKill: Button

    private var updateJob: Job? = null
    private var relayJob: Job? = null
    private var mcpJob: Job? = null
    private var mcpStateJob: Job? = null
    private var autoHideJob: Job? = null
    private var mcpDoneWatchJob: Job? = null
    private var isRunningJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    /** Track whether this instance was started for MCP (vs local EXECUTE) */
    private var isMcpMode = false
    private var hasBeenToldToHide = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        hasBeenToldToHide = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_hud, null)
        tvStatus = overlayView.findViewById(R.id.hudStatus)
        tvHudRelay = overlayView.findViewById(R.id.hudRelay)
        tvHudAction = overlayView.findViewById(R.id.hudAction)
        tvHudStep = overlayView.findViewById(R.id.hudStep)
        hudDot = overlayView.findViewById(R.id.hudDot)
        btnKill = overlayView.findViewById(R.id.btnKill)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 80
        params.horizontalMargin = 0.04f

        windowManager.addView(overlayView, params)

        // Animate overlay appearance — slide down + fade in
        overlayView.alpha = 0f
        overlayView.translationY = -30f
        overlayView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // KILL button stops everything
        btnKill.setOnClickListener {
            JarvisService.stopTask()
            RelayClient.mcpSessionActive.value = false
            tvStatus.text = "JARVIS Killed"
            tvHudAction.text = ""
            scheduleImmediateHide()
        }

        // ─── Observe JarvisService status (local ReAct loop) ───
        updateJob = serviceScope.launch {
            JarvisService.status.collectLatest { status ->
                tvStatus.text = "JARVIS"
                tvHudAction.text = status
                tvHudAction.alpha = 0f
                tvHudAction.animate().alpha(1f).setDuration(200).start()
            }
        }

        serviceScope.launch {
            JarvisService.currentStep.collectLatest { step ->
                tvHudStep.text = if (step > 0) "Step $step/50" else ""
            }
        }

        // ─── Observe RelayClient connection (MCP status) ───
        relayJob = serviceScope.launch {
            RelayClient.isConnected.collectLatest { connected ->
                tvHudRelay.text = if (connected) "● MCP" else ""
                tvHudRelay.setTextColor(
                    getColor(if (connected) R.color.green_accent else R.color.gray)
                )
            }
        }

        // ─── CRITICAL: Observe RelayClient.mcpActive for MCP PiP ───
        mcpJob = serviceScope.launch {
            RelayClient.mcpActive.collectLatest { active ->
                if (active) {
                    isMcpMode = true
                    lastMcpCommandTime = System.currentTimeMillis()
                    tvStatus.text = "JARVIS"
                    tvHudRelay.text = "● MCP"
                    tvHudRelay.setTextColor(getColor(R.color.green_accent))
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)
                    scheduleAutoHide()
                } else {
                    // MCP command went inactive — but check if session is still active
                    if (!RelayClient.mcpSessionActive.value && !JarvisService.isRunning.value) {
                        hudDot.setBackgroundResource(R.drawable.status_dot_idle)
                        // MCP session ended + no local task → dismiss overlay
                        if (isMcpMode) {
                            scheduleDoneAutoHide()
                        }
                    }
                    // If mcpSessionActive is still true, overlay stays — brain is just thinking
                }
            }
        }

        // ─── Observe mcpSessionActive — overlay stays as long as brain session is active ───
        serviceScope.launch {
            RelayClient.mcpSessionActive.collectLatest { sessionActive ->
                if (sessionActive) {
                    isMcpMode = true
                    // Cancel any pending auto-hide — session is active
                    autoHideJob?.cancel()
                }
            }
        }

        // ─── Observe RelayClient.mcpLastAction for live action text ───
        serviceScope.launch {
            RelayClient.mcpLastAction.collectLatest { action ->
                if (action.isNotBlank()) {
                    tvHudAction.text = action
                    tvHudAction.alpha = 0f
                    tvHudAction.animate().alpha(1f).setDuration(150).start()
                    lastMcpCommandTime = System.currentTimeMillis()
                    scheduleAutoHide()
                }
            }
        }

        // ─── Observe JarvisService.mcpState for sequence/macro status ───
        mcpStateJob = serviceScope.launch {
            JarvisService.mcpState.collectLatest { state ->
                if (state.isExecuting) {
                    isMcpMode = true
                    lastMcpCommandTime = System.currentTimeMillis()
                    tvHudAction.text = if (state.lastAction.isNotBlank()) state.lastAction else state.currentCommand
                    tvHudAction.alpha = 0f
                    tvHudAction.animate().alpha(1f).setDuration(150).start()
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)
                    if (state.commandCount > 0) {
                        tvHudStep.text = "${state.commandCount} remaining"
                    }
                    scheduleAutoHide()
                }
            }
        }

        // ─── CRITICAL FIX: Observe isRunning to detect task completion ───
        isRunningJob = serviceScope.launch {
            JarvisService.isRunning.collectLatest { running ->
                if (running) {
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)
                    autoHideJob?.cancel()
                } else {
                    // Task stopped — check if MCP session is also inactive
                    lastLocalTaskTime = System.currentTimeMillis()
                    if (!RelayClient.mcpSessionActive.value && !RelayClient.mcpActive.value) {
                        hudDot.setBackgroundResource(R.drawable.status_dot_idle)
                        // Both local task AND MCP session are done → hide overlay
                        if (isMcpMode) {
                            scheduleDoneAutoHide()
                        } else {
                            // Local-only task done → hide after brief delay
                            scheduleLocalTaskDoneHide()
                        }
                    }
                    // If mcpSessionActive is still true, overlay stays — brain is still working
                }
            }
        }

        // ─── Periodic watchdog: force-hide overlay if it's stuck ───
        mcpDoneWatchJob = serviceScope.launch {
            while (isActive) {
                delay(500) // Check every 500ms (was 1000ms — too slow)
                val now = System.currentTimeMillis()

                // Case 1: MCP mode, session is over, task not running → should be hiding
                if (isMcpMode && !RelayClient.mcpSessionActive.value && !JarvisService.isRunning.value && !RelayClient.mcpActive.value) {
                    val elapsed = now - lastMcpCommandTime
                    if (elapsed > MCP_DONE_HIDE_MS) {
                        Log.d(TAG, "Watchdog: MCP session done + task stopped + ${elapsed}ms elapsed → hiding")
                        animateHideAndStop()
                    }
                }

                // Case 2: MCP mode, been too long since any activity AND session is over → force hide
                if (isMcpMode && lastMcpCommandTime > 0 && (now - lastMcpCommandTime) > MCP_FORCE_HIDE_MS) {
                    if (!RelayClient.mcpSessionActive.value && !JarvisService.isRunning.value) {
                        Log.d(TAG, "Watchdog: ${now - lastMcpCommandTime}ms since last MCP command + session over → force hiding")
                        animateHideAndStop()
                    }
                }

                // Case 3: Local-only task, task finished, been >1s → hide
                if (!isMcpMode && !JarvisService.isRunning.value && lastLocalTaskTime > 0 && (now - lastLocalTaskTime) > LOCAL_TASK_DONE_HIDE_MS) {
                    Log.d(TAG, "Watchdog: Local task done >1s ago → hiding")
                    animateHideAndStop()
                }
            }
        }

        // Start the dot pulse animation
        startDotPulse()
    }

    /**
     * Schedule auto-hide after MCP_FORCE_HIDE_MS of no activity.
     */
    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(MCP_FORCE_HIDE_MS)
            if (isMcpMode && !JarvisService.isRunning.value && !RelayClient.mcpSessionActive.value) {
                Log.d(TAG, "Auto-hiding HUD after ${MCP_FORCE_HIDE_MS}ms of MCP inactivity + session over")
                animateHideAndStop()
            }
        }
    }

    /**
     * Schedule faster auto-hide after MCP command done.
     */
    private fun scheduleDoneAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(MCP_DONE_HIDE_MS)
            // Double-check: session is over AND not running
            if (!JarvisService.isRunning.value && !RelayClient.mcpSessionActive.value) {
                Log.d(TAG, "Auto-hiding HUD after MCP session done signal (${MCP_DONE_HIDE_MS}ms)")
                animateHideAndStop()
            }
        }
    }

    /**
     * Schedule hide after local task finishes.
     */
    private fun scheduleLocalTaskDoneHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(LOCAL_TASK_DONE_HIDE_MS)
            if (!JarvisService.isRunning.value && !RelayClient.mcpActive.value) {
                Log.d(TAG, "Auto-hiding HUD after local task done (${LOCAL_TASK_DONE_HIDE_MS}ms)")
                animateHideAndStop()
            }
        }
    }

    /**
     * Immediately schedule hide (for KILL button).
     */
    private fun scheduleImmediateHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(500)
            animateHideAndStop()
        }
    }

    /** Animate overlay out, then stop service. */
    private fun animateHideAndStop() {
        if (hasBeenToldToHide) return // Prevent double-stop
        hasBeenToldToHide = true
        autoHideJob?.cancel()
        if (!::overlayView.isInitialized) return
        try {
            overlayView.animate()
                .alpha(0f)
                .translationY(-30f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    try { stopSelf() } catch (_: Exception) {}
                }
                .start()
        } catch (e: Exception) {
            try { stopSelf() } catch (_: Exception) {}
        }
    }

    /** Pulse the status dot when active. */
    private fun startDotPulse() {
        if (!::hudDot.isInitialized) return
        val pulse = AlphaAnimation(0.4f, 1.0f)
        pulse.duration = 1000
        pulse.startOffset = 200
        pulse.repeatMode = Animation.REVERSE
        pulse.repeatCount = Animation.INFINITE
        pulse.interpolator = DecelerateInterpolator()
        hudDot.startAnimation(pulse)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hasBeenToldToHide = false
        updateJob?.cancel()
        relayJob?.cancel()
        mcpJob?.cancel()
        mcpStateJob?.cancel()
        autoHideJob?.cancel()
        mcpDoneWatchJob?.cancel()
        isRunningJob?.cancel()
        serviceScope.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}
