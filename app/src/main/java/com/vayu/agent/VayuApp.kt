package com.vayu.agent
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class VayuApp : Application() {
    companion object { const val CHANNEL_ID = "vayu_service" }

    override fun onCreate() {
        // CRITICAL FIX: Save the DEFAULT handler BEFORE setting our custom one.
        // The old code called getDefaultUncaughtExceptionHandler() INSIDE the handler,
        // which returned the custom handler itself, causing infinite recursion → StackOverflowError.
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

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
            // Call the ORIGINAL handler (saved before we set our custom one)
            originalHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate()

        try {
            val channel = NotificationChannel(CHANNEL_ID, "VAYU Agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VAYU agent service"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e("VayuApp", "Failed to create notification channel", e)
        }
    }
}
