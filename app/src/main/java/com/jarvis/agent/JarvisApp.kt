package com.jarvis.agent
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class JarvisApp : Application() {
    companion object { const val CHANNEL_ID = "jarvis_service" }
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "JARVIS Agent", NotificationManager.IMPORTANCE_LOW).apply { description = "JARVIS agent service"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
