package com.jarvis.agent

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class HUDService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var tvStatus: TextView
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_hud, null)
        tvStatus = overlayView.findViewById(R.id.hudStatus)
        val btnKill = overlayView.findViewById<Button>(R.id.btnKill)
        val tvHudStep = overlayView.findViewById<TextView>(R.id.hudStep)

        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.y = 80
        windowManager.addView(overlayView, params)

        btnKill.setOnClickListener { JarvisService.stopTask(); tvStatus.text = "JARVIS Killed" }

        updateJob = serviceScope.launch { JarvisService.status.collectLatest { tvStatus.text = it } }
        serviceScope.launch { JarvisService.currentStep.collectLatest { tvHudStep.text = if (it > 0) "Step $it/50" else "" } }
    }

    override fun onDestroy() {
        super.onDestroy(); updateJob?.cancel(); serviceScope.cancel()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
    }
}
