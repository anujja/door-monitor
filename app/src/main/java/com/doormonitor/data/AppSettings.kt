package com.doormonitor.data

/**
 * Immutable snapshot of all user configuration. Produced by [SettingsRepository] as a
 * cold Flow so any component (UI, service, MQTT, HTTP) reacts to changes consistently.
 */
data class AppSettings(
    // --- Dashboard ---
    val dashboardUrl: String = "",

    // --- Local HTTP API ---
    val httpEnabled: Boolean = true,
    val httpPort: Int = 2323,
    /** Empty = no password required. */
    val apiPassword: String = "",

    // --- MQTT ---
    val mqttEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val mqttUseTls: Boolean = false,
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    /** Unique per-device id; used as MQTT base topic and HA discovery object id. */
    val deviceId: String = "doormonitor",

    // --- Home Assistant integration ---
    val haBaseUrl: String = "",
    val haLongLivedToken: String = "",

    // --- Screen behaviour ---
    /** Seconds of inactivity before the screen sleeps after a *temporary* wake. 0 = never. */
    val screenTimeoutSeconds: Int = 60,
    /** Default brightness applied on screen-on (0..255). -1 = leave untouched. */
    val defaultBrightness: Int = 160,
    /** If true the screen stays on permanently (overrides timeout). */
    val keepScreenOn: Boolean = true,

    // --- Motion / wake triggers ---
    val wakeOnMotion: Boolean = true,
    val wakeOnDoorbell: Boolean = true,
    /** Seconds to keep screen on after a motion/doorbell wake. */
    val motionWakeSeconds: Int = 30,

    /**
     * Id of a camera to keep "pre-warmed" for instant display. When set, the dashboard holds a
     * persistent, already-connected WebView for this camera and shows it as an overlay (rather
     * than launching a fresh CameraActivity), so `command/camera <id>` appears with no
     * negotiation delay. Costs continuous decode while the dashboard is up. Blank = disabled.
     */
    val prewarmCameraId: String = "",

    // --- Kiosk ---
    /** Use device-owner lock-task mode when available. */
    val lockTaskEnabled: Boolean = false,

    // --- Battery protection ---
    /** Warn when the battery sits above this % for [batteryWarnAfterHours]. */
    val batteryHighThreshold: Int = 90,
    val batteryWarnAfterHours: Int = 24,

    // --- Cameras (stored as JSON, decoded here) ---
    val cameras: List<CameraDef> = emptyList()
)
