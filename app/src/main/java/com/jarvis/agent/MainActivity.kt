package com.jarvis.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        startGlowPulse()

        binding.btnPerms.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        binding.btnExecute.setOnClickListener {
            val task = binding.etTask.text.toString().trim()
            if (task.isBlank()) { binding.etTask.error = "Enter a task first"; return@setOnClickListener }
            if (!Settings.canDrawOverlays(this)) { binding.tvStatus.text = "Grant overlay permission first!"; return@setOnClickListener }
            startService(Intent(this, HUDService::class.java))
            JarvisService.startTask(task)
        }

        binding.btnStop.setOnClickListener { JarvisService.stopTask() }

        val memory = AgentMemory(getSharedPreferences("jarvis_settings", MODE_PRIVATE))
        val lastTask = memory.getLastTask()
        if (lastTask.isNotBlank()) binding.etTask.setText(lastTask)

        lifecycleScope.launch { JarvisService.status.collectLatest { binding.tvStatus.text = it; binding.tvStatus.alpha = 0f; binding.tvStatus.animate().alpha(1f).setDuration(300).start() } }
        lifecycleScope.launch { JarvisService.currentStep.collectLatest { binding.tvStep.text = if (it > 0) "STEP $it" else "IDLE" } }
        lifecycleScope.launch { JarvisService.currentAction.collectLatest { binding.tvAction.text = it; binding.tvAction.alpha = 0f; binding.tvAction.animate().alpha(1f).setDuration(200).start() } }
        lifecycleScope.launch { JarvisService.isRunning.collectLatest { r -> binding.btnExecute.isEnabled = !r; binding.btnStop.isEnabled = r; binding.btnExecute.alpha = if (r) 0.4f else 1f; binding.btnStop.alpha = if (r) 1f else 0.4f } }
        lifecycleScope.launch { RelayClient.isConnected.collectLatest { c -> binding.tvRelayStatus.text = if (c) "● Relay" else "○ Relay"; binding.tvRelayStatus.setTextColor(getColor(if (c) R.color.green_accent else R.color.gray)) } }
        updateApiStatus()
    }

    override fun onResume() { super.onResume(); updateApiStatus() }

    private fun updateApiStatus() {
        val config = SettingsManager.getConfig(this)
        binding.tvApiProvider.text = "API: ${config.provider.replaceFirstChar { it.uppercase() }} / ${config.model}"
        val hasKey = config.apiKey.isNotBlank() || config.provider == "gemini"
        binding.tvApiKeyStatus.text = "${if (hasKey) "●" else "○"} API Key"
        binding.tvApiKeyStatus.setTextColor(getColor(if (hasKey) R.color.green_accent else R.color.red_accent))
    }

    private fun startGlowPulse() {
        val anim = AlphaAnimation(0.3f, 0.8f); anim.duration = 2000; anim.startOffset = 500; anim.repeatMode = Animation.REVERSE; anim.repeatCount = Animation.INFINITE
        binding.ivGlow.startAnimation(anim)
    }
}
