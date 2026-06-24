package com.doormonitor.screen

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.doormonitor.core.KioskBus
import com.doormonitor.core.KioskCommand
import com.doormonitor.kiosk.DeviceAdminReceiver
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns all screen power + brightness behaviour. Designed to be created once (in the
 * foreground service) with the application context. Window-level effects are routed
 * through [KioskBus] to the Activity; system-level effects (wake lock, brightness,
 * device-admin lock) are applied directly here.
 *
 * Without root Android cannot truly cut power to the panel from an app, so "screen off"
 * uses device-admin `lockNow()` when available and otherwise drives brightness to zero
 * and lets the system timeout take over.
 */
class ScreenController(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(appContext, DeviceAdminReceiver::class.java)

    private val wakeLock = AtomicReference<PowerManager.WakeLock?>(null)
    private val sleepJob = AtomicReference<Job?>(null)

    @Volatile
    var screenOn: Boolean = true
        private set

    /**
     * Bring the screen on immediately. [permanent] keeps it on indefinitely; otherwise it
     * sleeps after [timeoutSeconds] (0 = use a momentary wake only).
     */
    fun screenOn(permanent: Boolean, timeoutSeconds: Int) {
        cancelScheduledSleep()
        acquireMomentaryWake()
        KioskBus.emit(KioskCommand.WindowScreenOn)
        screenOn = true

        if (!permanent && timeoutSeconds > 0) {
            scheduleSleep(timeoutSeconds)
        }
    }

    /** Permanent wake — screen stays on until an explicit screenOff/sleep. */
    fun permanentWake() = screenOn(permanent = true, timeoutSeconds = 0)

    /** Temporary wake — screen sleeps again after [seconds]. */
    fun temporaryWake(seconds: Int) = screenOn(permanent = false, timeoutSeconds = seconds)

    /** Turn the screen off now. Uses device-admin lock when granted. */
    fun screenOff() {
        cancelScheduledSleep()
        KioskBus.emit(KioskCommand.WindowAllowSleep)
        screenOn = false
        if (isDeviceAdminActive()) {
            runCatching { dpm.lockNow() }
                .onFailure { Log.w(TAG, "lockNow failed", it) }
        } else {
            Log.i(TAG, "Device admin not active; cannot power screen off without it")
        }
        releaseWake()
    }

    /**
     * Set brightness in the 0..255 range via the persistent system setting (requires the
     * WRITE_SETTINGS special access). No window-level override is used, so the app never leaves
     * its window stuck dim.
     */
    fun setBrightness(value0to255: Int) {
        val v = value0to255.coerceIn(0, 255)
        if (canWriteSettings()) {
            runCatching {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    v
                )
            }.onFailure { Log.w(TAG, "Failed writing system brightness", it) }
        }
    }

    fun canWriteSettings(): Boolean = Settings.System.canWrite(appContext)

    fun isDeviceAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    /**
     * Fully remove (or restore) the lock screen. Only possible when Door Monitor is the
     * **device owner**; a plain device admin cannot do this. Has no effect if a secure lock
     * (PIN/pattern/password) is set. Returns true if applied.
     *
     * Call with true at startup so waking from a lockNow() screen-off lands straight on the
     * dashboard with no keyguard at all (and no lock-screen flash).
     */
    fun setKeyguardDisabled(disabled: Boolean): Boolean {
        if (!isDeviceOwner()) return false
        return runCatching { dpm.setKeyguardDisabled(adminComponent, disabled) }
            .onFailure { Log.w(TAG, "setKeyguardDisabled failed", it) }
            .getOrDefault(false)
    }

    // --- internals ---

    private fun scheduleSleep(seconds: Int) {
        val job = scope.launch(Dispatchers.Default) {
            delay(seconds * 1000L)
            screenOff()
        }
        sleepJob.getAndSet(job)?.cancel()
    }

    private fun cancelScheduledSleep() {
        sleepJob.getAndSet(null)?.cancel()
    }

    /**
     * Briefly acquire a wake lock that causes the device to wake. Released shortly after so
     * the window flags (keep-screen-on) take over the long-term hold.
     */
    @Suppress("DEPRECATION")
    private fun acquireMomentaryWake() {
        runCatching {
            val lock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "DoorMonitor:wake"
            )
            lock.acquire(3000L)
            wakeLock.getAndSet(lock)?.let { old -> if (old.isHeld) old.release() }
        }.onFailure { Log.w(TAG, "wake lock failed", it) }
    }

    private fun releaseWake() {
        wakeLock.getAndSet(null)?.let { if (it.isHeld) runCatching { it.release() } }
    }

    companion object {
        private const val TAG = "ScreenController"
    }
}
