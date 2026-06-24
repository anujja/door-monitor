package com.doormonitor.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.doormonitor.MainActivity
import com.doormonitor.service.KioskForegroundService
import com.doormonitor.service.KioskWatchdog

/**
 * Auto-start after boot: brings up the foreground service and launches the kiosk activity so
 * the wall tablet returns to the dashboard with no human interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot completed; starting Door Monitor")
                KioskForegroundService.start(context)
                KioskWatchdog.schedule(context)
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
