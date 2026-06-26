package com.doormonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.doormonitor.DoorMonitorApp
import com.doormonitor.MainActivity
import com.doormonitor.R
import com.doormonitor.SettingsHolder
import com.doormonitor.battery.BatteryMonitor
import com.doormonitor.core.CommandHandler
import com.doormonitor.data.AppSettings
import com.doormonitor.data.SettingsRepository
import com.doormonitor.ha.HomeAssistantClient
import com.doormonitor.http.LocalHttpServer
import com.doormonitor.mqtt.MqttManager
import com.doormonitor.screen.ScreenController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The always-on heart of the app. Owns the screen controller, battery monitor, local HTTP
 * API and MQTT client so they keep running independent of the Activity lifecycle — for
 * months, unattended. Uses START_STICKY plus a WorkManager watchdog (see [KioskWatchdog])
 * so the OS restarts it if it is ever killed.
 */
class KioskForegroundService : LifecycleService() {

    private lateinit var repo: SettingsRepository
    private lateinit var screen: ScreenController
    private lateinit var battery: BatteryMonitor
    private lateinit var handler: CommandHandler
    private lateinit var mqtt: MqttManager
    private lateinit var ha: HomeAssistantClient

    private var httpServer: LocalHttpServer? = null
    private var httpPort: Int = -1
    private var httpEnabled: Boolean = false

    // Track last MQTT-affecting config to avoid needless reconnects.
    private var lastMqttSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()

        repo = SettingsRepository(applicationContext)
        screen = ScreenController(applicationContext, lifecycleScope)
        // When provisioned as device owner, remove the lock screen entirely so waking from a
        // screen-off never shows the keyguard. No-op for a plain device admin. Re-applied here
        // on every (re)start, including boot.
        screen.setKeyguardDisabled(true)
        ha = HomeAssistantClient { handler.settings }
        battery = BatteryMonitor(
            appContext = applicationContext,
            onWarning = { active -> onBatteryWarning(active) }
        ).also { it.start() }

        handler = CommandHandler(applicationContext, screen, battery)
        mqtt = MqttManager(handler)

        observeSettings()
        KioskWatchdog.schedule(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY // restart if the process is killed
    }

    /**
     * Called when the user swipes Door Monitor out of Recents. We are a foreground service with
     * stopWithTask=false, so this normally won't stop us — but schedule a prompt restart as a
     * safety net so MQTT/HTTP keep running even on aggressive OEM task killers.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = PendingIntent.getService(
            applicationContext,
            1,
            Intent(applicationContext, KioskForegroundService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        runCatching {
            alarm.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 1000L,
                restart
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            repo.settings.collectLatest { s ->
                handler.settings = s
                SettingsHolder.lockTaskEnabled = s.lockTaskEnabled
                battery.highThreshold = s.batteryHighThreshold
                battery.warnAfterMillis = s.batteryWarnAfterHours * 60L * 60L * 1000L
                applyHttp(s)
                applyMqtt(s)
            }
        }
    }

    private fun applyHttp(s: AppSettings) {
        val needRestart = s.httpEnabled != httpEnabled || s.httpPort != httpPort
        if (!needRestart) return
        httpServer?.stop()
        httpServer = null
        httpEnabled = s.httpEnabled
        httpPort = s.httpPort
        if (s.httpEnabled) {
            httpServer = LocalHttpServer(
                port = s.httpPort,
                handler = handler,
                passwordProvider = { handler.settings.apiPassword }
            ).also { it.startSafely() }
            Log.i(TAG, "HTTP API listening on ${s.httpPort}")
        }
    }

    private fun applyMqtt(s: AppSettings) {
        val sig = listOf(
            s.mqttEnabled, s.mqttHost, s.mqttPort, s.mqttUseTls,
            s.mqttUsername, s.mqttPassword, s.deviceId
        ).joinToString("|")
        if (sig == lastMqttSignature) {
            // No connection-relevant change: just refresh published state.
            mqtt.publishState()
            return
        }
        lastMqttSignature = sig
        mqtt.connect(s)
    }

    private fun onBatteryWarning(active: Boolean) {
        Log.w(TAG, "High battery warning active=$active")
        mqtt.publishHighBatteryWarning(active)
        ha.fireEvent("door_monitor_battery_warning", """{"active":$active}""")
        if (active) {
            val n = NotificationCompat.Builder(this, DoorMonitorApp.CHANNEL_ALERTS)
                .setContentTitle("Battery above threshold")
                .setContentText("Tablet has been highly charged for a long time. Consider cutting power to reduce wear.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            androidx.core.app.NotificationManagerCompat.from(this).also {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    it.notify(NOTIF_ALERT_ID, n)
                }
            }
        }
    }

    /**
     * Start in the foreground, but never let a foreground-start restriction crash the process
     * (which would crash-loop via START_STICKY/watchdog). If it fails, log and stop cleanly so
     * the watchdog can retry later from an allowed context.
     */
    private fun startForegroundSafely() {
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed; stopping to avoid a crash loop", t)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, DoorMonitorApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // specialUse has no per-day time limit, unlike dataSync on Android 15 which crashes a
        // perpetual service with "Time limit already exhausted". (minSdk 34 supports this type.)
        startForeground(
            NOTIF_SERVICE_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        mqtt.disconnect()
        battery.stop()
    }

    companion object {
        private const val TAG = "KioskService"
        private const val NOTIF_SERVICE_ID = 1001
        private const val NOTIF_ALERT_ID = 1002

        /** Safe start: foreground from boot/Activity contexts. */
        fun start(context: Context) {
            val intent = Intent(context, KioskForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
