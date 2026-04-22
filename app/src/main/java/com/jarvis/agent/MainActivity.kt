package com.jarvis.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

        binding.btnPerms.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        binding.btnExecute.setOnClickListener {
            val task = binding.etTask.text.toString()
            if (task.isNotBlank()) {
                if (!Settings.canDrawOverlays(this)) return@setOnClickListener
                startService(Intent(this, HUDService::class.java))
                JarvisService.startTask(task)
            }
        }

        lifecycleScope.launch {
            JarvisService.status.collectLatest {
                binding.tvStatus.text = "Service: $it"
            }
        }
        
        lifecycleScope.launch {
            JarvisService.currentStep.collectLatest {
                binding.tvStep.text = "Step: $it/50"
            }
        }
    }
}
