package com.vayu.agent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vayu.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge layout
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // ─── Animated entrance ───
        animateEntrance()

        // ─── Glow pulse animation on title ───
        startGlowPulse()

        // ─── Configure VayuView state observer ───
        configureVayuView()

        // ─── Permissions button ───
        binding.btnPerms.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // ─── Settings button ───
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // ─── Execute button ───
        binding.btnExecute.setOnClickListener {
            val task = binding.etTask.text.toString().trim()
            if (task.isBlank()) {
                binding.etTask.error = "Enter a task first"
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                binding.tvStatus.text = "Grant overlay permission first!"
                return@setOnClickListener
            }
            // FIX: Start HUD overlay as foreground service on API 26+ to prevent system kill
            val hudIntent = Intent(this, HUDService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(hudIntent)
            } else {
                startService(hudIntent)
            }
            VayuService.startTask(task)
        }

        // ─── Kill button ───
        binding.btnStop.setOnClickListener { VayuService.stopTask() }

        // ─── Load last task from memory ───
        val memory = AgentMemory(getSharedPreferences("vayu_settings", MODE_PRIVATE))
        val lastTask = memory.getLastTask()
        if (lastTask.isNotBlank()) binding.etTask.setText(lastTask)

        // ─── Show previous crash info if any ───
        showCrashInfoIfAvailable()

        // ─── Observe VayuService flows ───
        lifecycleScope.launch {
            VayuService.status.collectLatest { status ->
                binding.tvStatus.text = status
                binding.tvStatus.alpha = 0f
                binding.tvStatus.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        lifecycleScope.launch {
            VayuService.currentStep.collectLatest { step ->
                binding.tvStep.text = if (step > 0) "STEP $step" else "IDLE"
            }
        }

        lifecycleScope.launch {
            VayuService.currentAction.collectLatest { action ->
                binding.tvAction.text = action
                binding.tvAction.alpha = 0f
                binding.tvAction.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        lifecycleScope.launch {
            VayuService.isRunning.collectLatest { running ->
                binding.btnExecute.isEnabled = !running
                binding.btnStop.isEnabled = running
                binding.btnExecute.alpha = if (running) 0.4f else 1f
                binding.btnStop.alpha = if (running) 1f else 0.4f

                // Update status dot
                if (running) {
                    binding.statusDot.setBackgroundResource(R.drawable.status_dot_active)
                } else if (!RelayClient.mcpActive.value) {
                    binding.statusDot.setBackgroundResource(R.drawable.status_dot_idle)
                }

                // Update VayuView state
                binding.vayuView.currentState = if (running) VayuView.BotState.WORKING else VayuView.BotState.IDLE
            }
        }

        // ─── MCP / Relay connection status ───
        lifecycleScope.launch {
            RelayClient.isConnected.collectLatest { connected ->
                if (connected) {
                    binding.tvRelayStatus.text = "MCP"
                    binding.tvRelayStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.green_accent))
                    binding.mcpDot.setBackgroundResource(R.drawable.mcp_indicator)
                } else {
                    binding.tvRelayStatus.text = "MCP"
                    binding.tvRelayStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.gray))
                    binding.mcpDot.setBackgroundResource(R.drawable.mcp_indicator_off)
                }
            }
        }

        lifecycleScope.launch {
            RelayClient.relayStatus.collectLatest { status ->
                if (!RelayClient.isConnected.value) {
                    binding.tvRelayDetail.text = status
                    binding.tvRelayDetail.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.gray))
                } else {
                    val host = RelayClient.getRelayUrl()
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .removePrefix("wss://")
                        .removePrefix("ws://")
                    binding.tvRelayDetail.text = host
                    binding.tvRelayDetail.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.green_accent))
                }
            }
        }

        // ─── MCP active state — update status dot ───
        lifecycleScope.launch {
            RelayClient.mcpActive.collectLatest { active ->
                if (active) {
                    binding.statusDot.setBackgroundResource(R.drawable.status_dot_active)
                    binding.vayuView.currentState = VayuView.BotState.WORKING
                } else if (!VayuService.isRunning.value) {
                    binding.statusDot.setBackgroundResource(R.drawable.status_dot_idle)
                    binding.vayuView.currentState = VayuView.BotState.IDLE
                }
            }
        }

        updateApiStatus()
    }

    private fun configureVayuView() {
        // VayuView is referenced in the layout as a custom view
    }

    override fun onResume() {
        super.onResume()
        updateApiStatus()
    }

    private fun updateApiStatus() {
        try {
            val config = SettingsManager.getConfig(this)
            binding.tvApiProvider.text = "API: ${config.provider.replaceFirstChar { it.uppercase() }} / ${config.model}"
            val hasKey = config.apiKey.isNotBlank() || config.provider == "gemini"
            binding.tvApiKeyStatus.text = "${if (hasKey) "●" else "○"} API Key"
            binding.tvApiKeyStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(this,
                    if (hasKey) R.color.green_accent else R.color.red_accent)
            )
        } catch (e: Exception) {
            // Never let UI status update crash the Activity
            android.util.Log.e("MainActivity", "updateApiStatus failed", e)
        }
    }

    /** Show crash info from previous run if available (helps debugging). */
    private fun showCrashInfoIfAvailable() {
        try {
            val prefs = getSharedPreferences("vayu_crash", MODE_PRIVATE)
            val crashClass = prefs.getString("last_crash_class", null)
            val crashMsg = prefs.getString("last_crash_msg", null)
            val crashTime = prefs.getLong("last_crash_time", 0)

            if (crashClass != null && System.currentTimeMillis() - crashTime < 60000) {
                // Show crash from last 60 seconds
                binding.tvStatus.text = "CRASH: $crashClass — $crashMsg"
                binding.tvStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.red_accent)
                )
                // Clear so it doesn't show again
                prefs.edit().clear().apply()
            }
        } catch (_: Exception) {}
    }

    /** Animate the title with a pulsing glow behind it. */
    private fun startGlowPulse() {
        val anim = AlphaAnimation(0.25f, 0.7f)
        anim.duration = 2500
        anim.startOffset = 300
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        anim.interpolator = DecelerateInterpolator()
        binding.ivGlow.startAnimation(anim)

        // Subtle title text glow pulse
        val titlePulse = AlphaAnimation(0.85f, 1.0f)
        titlePulse.duration = 2500
        titlePulse.startOffset = 300
        titlePulse.repeatMode = Animation.REVERSE
        titlePulse.repeatCount = Animation.INFINITE
        titlePulse.interpolator = DecelerateInterpolator()
        binding.tvTitle.startAnimation(titlePulse)
    }

    /** Entrance animation — staggered fade-in for main elements. */
    private fun animateEntrance() {
        // Title area
        binding.ivGlow.alpha = 0f
        binding.tvTitle.alpha = 0f

        binding.ivGlow.animate()
            .alpha(0.4f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.tvTitle.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }
}
