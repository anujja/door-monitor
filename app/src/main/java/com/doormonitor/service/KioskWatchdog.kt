package com.doormonitor.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net. START_STICKY restarts the service in most cases, but a hard process
 * kill or an OEM "battery saver" can stop it. This worker periodically re-issues a service
 * start. On Android 12+ a background FGS start can be blocked; that is caught and logged —
 * for a fully unattended deployment, device-owner mode avoids the restriction entirely.
 */
class KioskWatchdog(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            KioskForegroundService.start(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog could not (re)start service: ${t.message}")
            Result.success() // never fail; just try again next cycle
        }
    }

    companion object {
        private const val TAG = "KioskWatchdog"
        private const val WORK_NAME = "door_monitor_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KioskWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
