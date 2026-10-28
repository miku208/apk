package com.arisu.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.arisu.companion.overlay.FloatingPetService

class ArisuApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            FloatingPetService.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_channel_pet),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the floating 3D companion available"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}

private const val TAG = "ArisuApplication"
