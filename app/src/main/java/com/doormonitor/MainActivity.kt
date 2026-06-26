package com.doormonitor

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.doormonitor.camera.CameraActivity
import com.doormonitor.camera.PrewarmCameraOverlay
import com.doormonitor.core.KioskBus
import com.doormonitor.core.KioskCommand
import com.doormonitor.data.CameraType
import com.doormonitor.kiosk.KioskController
import com.doormonitor.service.KioskForegroundService
import com.doormonitor.ui.DashboardScreen
import com.doormonitor.ui.MainViewModel
import com.doormonitor.ui.SettingsScreen
import com.doormonitor.ui.theme.DoorMonitorTheme
import kotlinx.coroutines.launch

/**
 * Single kiosk activity. Hosts the dashboard + settings Compose screens, applies immersive
 * full-screen, blocks accidental exits, and executes window-level [KioskCommand]s that the
 * background command sources cannot perform on their own (screen-on flags, brightness,
 * launching the camera activity).
 */
class MainActivity : ComponentActivity() {

    private lateinit var kiosk: KioskController

    // Which camera is currently shown in the persistent pre-warm overlay (null = none).
    // Held as Compose state so the UI reacts, and so the back handler can dismiss it.
    private val cameraOverlayId = mutableStateOf<String?>(null)

    // A camera-show request that arrived via Intent (from the service / MQTT / HTTP), pending
    // routing once settings are loaded. Lets camera commands work even when launched cold.
    private val pendingCameraId = mutableStateOf<String?>(null)

    // Legacy keyguard suppression for the non-device-owner case (held for the app's lifetime).
    @Suppress("DEPRECATION")
    private var keyguardLock: android.app.KeyguardManager.KeyguardLock? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kiosk = KioskController(this)

        // A camera command may have launched us cold.
        intent?.getStringExtra(EXTRA_SHOW_CAMERA)?.let { pendingCameraId.value = it }

        // Make sure the long-running service is up (boot receiver also does this).
        KioskForegroundService.start(this)

        requestRuntimePermissions()
        kiosk.enterImmersive()
        suppressInsecureKeyguard()
        collectWindowCommands()

        // Block accidental exits: back closes the camera overlay if open, else does nothing.
        onBackPressedDispatcher.addCallback(this) {
            if (cameraOverlayId.value != null) cameraOverlayId.value = null
        }

        setContent {
            DoorMonitorTheme {
                val nav = rememberNavController()
                val vm: MainViewModel = viewModel()
                val settings by vm.settings.collectAsState()
                val s = settings

                if (s == null) {
                    // Settings not loaded yet — hold on a black screen so we don't flash the
                    // wrong destination (and route only once we know the real config).
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    return@DoorMonitorTheme
                }

                // The camera to keep pre-warmed in the on-dashboard overlay. Only WEBRTC cameras
                // use the overlay (their connection setup is slow, so warming pays off). RTSP/
                // HLS/MJPEG cameras are shown via the full-screen CameraActivity (native libVLC),
                // which renders reliably; they ignore the pre-warm setting.
                val prewarmCamera = remember(s.cameras, s.prewarmCameraId) {
                    s.prewarmCameraId.takeIf { it.isNotBlank() }?.let { id ->
                        s.cameras.firstOrNull { it.id == id && it.type == CameraType.WEBRTC }
                    }
                }

                // Start on the dashboard when a URL is configured; otherwise Settings. Decided
                // from the loaded value, so it's correct on first composition.
                val startDestination = if (s.dashboardUrl.isBlank()) "settings" else "dashboard"

                Box(Modifier.fillMaxSize()) {
                    NavHost(navController = nav, startDestination = startDestination) {
                        composable("dashboard") {
                            DashboardScreen(
                                url = s.dashboardUrl,
                                onOpenSettings = { nav.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                settings = s,
                                onChange = { transform -> vm.update(transform) },
                                onAddCamera = { vm.upsertCamera(it) },
                                onRemoveCamera = { vm.removeCamera(it) },
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }

                    // Persistent, always-connected overlay for the pre-warm camera. Stays in the
                    // composition (so the WebRTC connection stays warm) whenever a pre-warm camera
                    // is configured; only its visibility changes — making display instant.
                    if (prewarmCamera != null) {
                        PrewarmCameraOverlay(
                            camera = prewarmCamera,
                            visible = cameraOverlayId.value == prewarmCamera.id,
                            onClose = { cameraOverlayId.value = null }
                        )
                    }
                }

                // Route a pending camera-show request (from Intent / MQTT / HTTP): the pre-warm
                // camera shows instantly via the overlay; any other camera falls back to a fresh
                // full-screen CameraActivity. Runs only once settings are loaded (here).
                LaunchedEffect(pendingCameraId.value, prewarmCamera?.id) {
                    val id = pendingCameraId.value ?: return@LaunchedEffect
                    if (prewarmCamera != null && id == prewarmCamera.id) {
                        cameraOverlayId.value = id
                    } else {
                        openCamera(id)
                    }
                    pendingCameraId.value = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Camera command delivered while the app is already running (singleTask).
        intent.getStringExtra(EXTRA_SHOW_CAMERA)?.let { pendingCameraId.value = it }
    }

    override fun onResume() {
        super.onResume()
        kiosk.enterImmersive()
        // After a device-admin lockNow() screen-off, waking shows the keyguard. With no secure
        // lock set this dismisses it automatically so we land on the dashboard, not the lock
        // screen. (For a secure lock the system still requires the user to authenticate.)
        dismissKeyguardAndShow()
        lifecycleScope.launch {
            // Re-apply lock task if enabled in settings.
            val locked = SettingsHolder.lockTaskEnabled
            if (locked) kiosk.startLockTaskIfPossible()
        }
    }

    private fun collectWindowCommands() {
        lifecycleScope.launch {
            KioskBus.commands.collect { cmd ->
                when (cmd) {
                    KioskCommand.WindowScreenOn -> applyScreenOnFlags()
                    KioskCommand.WindowAllowSleep -> clearKeepScreenOn()
                    KioskCommand.ReapplyImmersive -> kiosk.enterImmersive()
                    else -> Unit
                }
            }
        }
    }

    private fun applyScreenOnFlags() {
        dismissKeyguardAndShow()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Best-effort keyguard removal for devices that are NOT device owner. The legacy
     * KeyguardLock.disableKeyguard() suppresses a non-secure keyguard (no PIN/pattern/password)
     * for as long as the lock is held — preventing the lock screen from appearing after a
     * lockNow() screen-off. Has no effect on a secure keyguard. Held for the app's lifetime.
     */
    @Suppress("DEPRECATION")
    private fun suppressInsecureKeyguard() {
        if (keyguardLock != null) return
        runCatching {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = km.newKeyguardLock("DoorMonitor").also { it.disableKeyguard() }
        }
    }

    /** Show over (and dismiss, if insecure) the keyguard and ensure the screen turns on. */
    private fun dismissKeyguardAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun openCamera(cameraId: String) {
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_CAMERA_ID, cameraId)
        )
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // WRITE_SETTINGS is a special access; prompt once if missing (needed for brightness).
        if (!Settings.System.canWrite(this)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        // "Display over other apps" lets the service launch the camera/dashboard from the
        // background (e.g. on a doorbell MQTT command while the app is closed / screen off).
        if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        /** Intent extra carrying a camera id to display (used for background launches). */
        const val EXTRA_SHOW_CAMERA = "show_camera_id"
    }
}

/** Tiny holder updated by the service so the Activity can read the lock-task flag cheaply. */
object SettingsHolder {
    @Volatile
    var lockTaskEnabled: Boolean = false
}
