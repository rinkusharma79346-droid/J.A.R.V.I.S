package com.jarvis.agent
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class VayuApp : Application() {
    companion object { const val CHANNEL_ID = "vayu_service" }
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "VAYU Agent", NotificationManager.IMPORTANCE_LOW).apply { description = "VAYU agent service"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
