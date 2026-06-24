package com.doormonitor.kiosk

import android.app.admin.DeviceAdminReceiver as AndroidDeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device-admin / device-owner receiver. When Door Monitor is set as device owner
 * (`adb shell dpm set-device-owner com.doormonitor/.kiosk.DeviceAdminReceiver`) the app can
 * use Lock Task Mode for a tamper-resistant kiosk. See docs/BUILD.md.
 */
class DeviceAdminReceiver : AndroidDeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "DoorMonitorAdmin"
    }
}
