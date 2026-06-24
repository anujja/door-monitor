package com.doormonitor.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Window/Activity-level command. Commands arrive on background components (HTTP server,
 * MQTT, webhook) but some actions must run on the foreground Activity (WebView reload,
 * launching the camera screen, applying window flags). Those are emitted here and the
 * Activity collects them while resumed.
 */
sealed interface KioskCommand {
    data object ReloadDashboard : KioskCommand
    data object LoadDashboard : KioskCommand

    /** Turn the screen on at the window level (FLAG_TURN_SCREEN_ON + keyguard dismiss). */
    data object WindowScreenOn : KioskCommand

    /** Release the window keep-screen-on hold so the system can sleep. */
    data object WindowAllowSleep : KioskCommand

    /** Re-apply immersive kiosk flags (e.g. after returning from camera). */
    data object ReapplyImmersive : KioskCommand
}

/**
 * Process-wide single-subscriber-friendly bus. Uses a small replay buffer so a command
 * issued microseconds before the Activity resumes is not lost.
 */
object KioskBus {
    private val _commands = MutableSharedFlow<KioskCommand>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val commands: SharedFlow<KioskCommand> = _commands.asSharedFlow()

    fun emit(command: KioskCommand) {
        _commands.tryEmit(command)
    }
}
