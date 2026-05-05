package com.vayu.agent
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast

class VayuApp : Application() {
    companion object { const val CHANNEL_ID = "vayu_service" }

    override fun onCreate() {
        // Install global crash handler FIRST before anything else
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("VayuApp", "FATAL CRASH on $thread", throwable)
            // Save crash info to SharedPreferences for debugging
            try {
                val prefs = getSharedPreferences("vayu_crash", MODE_PRIVATE)
                prefs.edit()
                    .putString("last_crash_class", throwable.javaClass.name)
                    .putString("last_crash_msg", throwable.message ?: "Unknown")
                    .putString("last_crash_stack", throwable.stackTraceToString().take(2000))
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}
            // Let the default handler kill the process
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate()

        try {
            val channel = NotificationChannel(CHANNEL_ID, "VAYU Agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VAYU agent service"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e("VayuApp", "Failed to create notification channel", e)
        }
    }
}
