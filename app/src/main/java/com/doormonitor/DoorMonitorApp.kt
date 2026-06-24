package com.doormonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application entry point. Creates notification channels. The long-running work lives in
 * [com.doormonitor.service.KioskForegroundService], started from the Activity (foreground)
 * and from the boot receiver.
 */
class DoorMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    getString(R.string.kiosk_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = getString(R.string.kiosk_channel_desc) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = getString(R.string.alert_channel_desc) }
            )
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "kiosk_service"
        const val CHANNEL_ALERTS = "tablet_alerts"
    }
}
