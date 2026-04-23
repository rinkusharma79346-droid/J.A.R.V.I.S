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

class HUDService : Service() {

    companion object {
        private const val TAG = "HUDService"
        private const val MCP_AUTO_HIDE_MS = 10_000L

        private var isRunning = false

        /**
         * Start the HUD overlay for MCP command processing.
         * Called from RelayClient when MCP commands arrive.
         * This is the CRITICAL FIX — previously HUD only started from EXECUTE button.
         */
        fun showForMcp(context: Context) {
            if (!isRunning) {
                Log.d(TAG, "showForMcp: Starting HUD service for MCP overlay")
                val intent = Intent(context, HUDService::class.java)
                context.startService(intent)
            }
        }

        /** Stop the HUD overlay from outside. */
        fun hide(context: Context) {
            context.stopService(Intent(context, HUDService::class.java))
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    /** Track whether this instance was started for MCP (vs local EXECUTE) */
    private var isMcpMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // KILL button stops everything
        btnKill.setOnClickListener {
            JarvisService.stopTask()
            tvStatus.text = "JARVIS Killed"
            tvHudAction.text = ""

            // If MCP-only mode, auto-hide after a moment
            if (isMcpMode) {
                serviceScope.launch {
                    delay(1500)
                    stopSelf()
                }
            }
        }

        // ─── Observe JarvisService status (local ReAct loop) ───
        updateJob = serviceScope.launch {
            JarvisService.status.collectLatest { status ->
                tvStatus.text = "JARVIS"
                tvHudAction.text = status
                tvHudAction.alpha = 0f
                tvHudAction.animate().alpha(1f).setDuration(250).start()
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
                    tvStatus.text = "JARVIS"
                    tvHudRelay.text = "● MCP"
                    tvHudRelay.setTextColor(getColor(R.color.green_accent))

                    // Show status dot as cyan (active)
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)

                    // Start auto-hide timer — if no MCP commands for 10s, hide overlay
                    scheduleAutoHide()
                } else {
                    // MCP went inactive — update dot
                    if (!JarvisService.isRunning.value) {
                        hudDot.setBackgroundResource(R.drawable.status_dot_idle)
                    }
                }
            }
        }

        // ─── Observe RelayClient.mcpLastAction for live action text ───
        serviceScope.launch {
            RelayClient.mcpLastAction.collectLatest { action ->
                if (action.isNotBlank()) {
                    tvHudAction.text = action
                    tvHudAction.alpha = 0f
                    tvHudAction.animate().alpha(1f).setDuration(200).start()

                    // Reset auto-hide timer on each new action
                    scheduleAutoHide()
                }
            }
        }

        // ─── Observe JarvisService.mcpState for sequence/macro status ───
        mcpStateJob = serviceScope.launch {
            JarvisService.mcpState.collectLatest { state ->
                if (state.isExecuting) {
                    isMcpMode = true
                    tvHudAction.text = if (state.lastAction.isNotBlank()) state.lastAction else state.currentCommand
                    tvHudAction.alpha = 0f
                    tvHudAction.animate().alpha(1f).setDuration(200).start()
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)

                    if (state.commandCount > 0) {
                        tvHudStep.text = "${state.commandCount} remaining"
                    }

                    scheduleAutoHide()
                }
            }
        }

        // ─── Observe isRunning to update dot and auto-hide logic ───
        serviceScope.launch {
            JarvisService.isRunning.collectLatest { running ->
                if (running) {
                    hudDot.setBackgroundResource(R.drawable.status_dot_active)
                    // Cancel auto-hide when local task is running
                    autoHideJob?.cancel()
                } else {
                    // If not MCP active either, go idle
                    if (!RelayClient.mcpActive.value) {
                        hudDot.setBackgroundResource(R.drawable.status_dot_idle)
                        // Auto-hide if MCP-only mode
                        if (isMcpMode) {
                            scheduleAutoHide()
                        }
                    }
                }
            }
        }

        // Start the dot pulse animation
        startDotPulse()
    }

    /**
     * Schedule auto-hide after MCP_AUTO_HIDE_MS of no activity.
     * Each new MCP command resets this timer.
     */
    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = serviceScope.launch {
            delay(MCP_AUTO_HIDE_MS)
            // Only auto-hide if we're in MCP mode AND local task isn't running
            if (isMcpMode && !JarvisService.isRunning.value) {
                Log.d(TAG, "Auto-hiding HUD after ${MCP_AUTO_HIDE_MS}ms of MCP inactivity")
                animateHideAndStop()
            }
        }
    }

    /** Animate overlay out, then stop service. */
    private fun animateHideAndStop() {
        if (!::overlayView.isInitialized) return
        overlayView.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { stopSelf() }
            .start()
    }

    /** Pulse the status dot when active. */
    private fun startDotPulse() {
        if (!::hudDot.isInitialized) return
        val pulse = AlphaAnimation(0.4f, 1.0f)
        pulse.duration = 1200
        pulse.startOffset = 200
        pulse.repeatMode = Animation.REVERSE
        pulse.repeatCount = Animation.INFINITE
        pulse.interpolator = DecelerateInterpolator()
        hudDot.startAnimation(pulse)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        updateJob?.cancel()
        relayJob?.cancel()
        mcpJob?.cancel()
        mcpStateJob?.cancel()
        autoHideJob?.cancel()
        serviceScope.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}
