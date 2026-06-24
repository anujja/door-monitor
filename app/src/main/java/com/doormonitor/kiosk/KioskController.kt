package com.doormonitor.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Applies kiosk window behaviour to an [Activity]: sticky immersive full-screen (no status
 * or navigation bar) and, when the app is device owner, Lock Task Mode to block exits.
 *
 * Created per-Activity and given the Activity in [attach].
 */
class KioskController(private val activity: Activity) {

    private val dpm =
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(activity, DeviceAdminReceiver::class.java)

    /** Enable immersive full-screen and keep the screen on. Call from onCreate/onResume. */
    fun enterImmersive() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Also keep the legacy flag set so a transient swipe re-hides quickly on OEM skins.
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Start Lock Task Mode if this app is the device owner (or has been whitelisted for it).
     * No-op otherwise, so the app still works without provisioning.
     */
    fun startLockTaskIfPossible() {
        try {
            if (dpm.isDeviceOwnerApp(activity.packageName)) {
                dpm.setLockTaskPackages(adminComponent, arrayOf(activity.packageName))
            }
            // isLockTaskPermitted returns true for the device-owner package.
            if (dpm.isLockTaskPermitted(activity.packageName)) {
                activity.startLockTask()
            } else {
                Log.i(TAG, "Lock task not permitted (app is not device owner); skipping")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startLockTask failed", t)
        }
    }

    fun stopLockTask() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.stopLockTask()
            }
        }
    }

    companion object {
        private const val TAG = "KioskController"
    }
}
