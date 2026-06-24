package com.doormonitor.core

import android.content.Context
import android.content.Intent
import android.os.Build
import com.doormonitor.MainActivity
import com.doormonitor.battery.BatteryMonitor
import com.doormonitor.data.AppSettings
import com.doormonitor.screen.ScreenController
import org.json.JSONObject

/**
 * High-level, transport-agnostic command surface. The HTTP API, MQTT subscriber and HA
 * webhook all funnel here, so behaviour is identical regardless of how a command arrives.
 *
 * The handler does not own configuration; the service keeps [settings] current by collecting
 * the settings Flow and assigning the latest snapshot.
 */
class CommandHandler(
    private val appContext: Context,
    private val screen: ScreenController,
    private val battery: BatteryMonitor
) {
    @Volatile
    var settings: AppSettings = AppSettings()

    fun screenOn() {
        screen.screenOn(
            permanent = settings.keepScreenOn,
            timeoutSeconds = settings.screenTimeoutSeconds
        )
        bringDashboardToFront()
    }

    fun screenOff() = screen.screenOff()

    fun setBrightness(value0to255: Int) = screen.setBrightness(value0to255)

    /** Permanent wake. */
    fun wake() {
        screen.permanentWake()
        bringDashboardToFront()
    }

    /** Temporary wake for [seconds] (defaults to configured motion duration). */
    fun wakeFor(seconds: Int = settings.motionWakeSeconds) {
        screen.temporaryWake(seconds)
        bringDashboardToFront()
    }

    fun sleep() = screen.screenOff()

    /**
     * Bring [MainActivity] to the foreground so it resumes and dismisses the (insecure) keyguard
     * left by a previous lockNow() screen-off — landing on the dashboard rather than the lock
     * screen. Works from the background thanks to the SYSTEM_ALERT_WINDOW grant.
     */
    private fun bringDashboardToFront() {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    fun reloadDashboard() = KioskBus.emit(KioskCommand.ReloadDashboard)

    /**
     * Show a camera. Launches [MainActivity] (singleTask) with the camera id so it works even
     * when the app is closed/backgrounded and the screen is off — the Activity's
     * turnScreenOn/showWhenLocked flags wake the display, and it then routes to the pre-warm
     * overlay or a full-screen CameraActivity. Requires the "display over other apps"
     * (SYSTEM_ALERT_WINDOW) permission for the background launch to be allowed.
     */
    fun showCamera(cameraId: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_SHOW_CAMERA, cameraId)
        }
        appContext.startActivity(intent)
    }

    /** Wake triggered by a motion/doorbell/webhook event. Honors per-trigger toggles. */
    fun motionWake() {
        if (settings.wakeOnMotion) wakeFor(settings.motionWakeSeconds)
    }

    fun doorbellWake() {
        if (settings.wakeOnDoorbell) {
            wakeFor(settings.motionWakeSeconds)
            // If a doorbell camera is defined, surface it immediately.
            settings.cameras.firstOrNull { it.id.contains("door", ignoreCase = true) }
                ?.let { showCamera(it.id) }
        }
    }

    /** Status snapshot shared by GET /status and MQTT state publishing. */
    fun statusJson(): JSONObject {
        val b = battery.current
        return JSONObject().apply {
            put("appId", appContext.packageName)
            put("deviceId", settings.deviceId)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("screenOn", screen.screenOn)
            put("brightness", settings.defaultBrightness)
            put("dashboardUrl", settings.dashboardUrl)
            put("batteryLevel", b.level)
            put("batteryCharging", b.charging)
            put("batteryHealth", b.healthLabel)
            put("batteryTemperatureC", b.temperatureC)
            put("plugged", b.plugged)
            put("highBatteryWarning", b.highWarningActive)
            put("mqttEnabled", settings.mqttEnabled)
            put("cameras", settings.cameras.joinToString(",") { it.id })
        }
    }
}
