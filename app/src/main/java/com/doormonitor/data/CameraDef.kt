package com.doormonitor.data

import kotlinx.serialization.Serializable

/** Streaming protocol used to render a camera feed. */
enum class CameraType {
    /** Low-latency WebRTC, rendered inside a WebView (e.g. go2rtc / HA `webrtc-camera`). */
    WEBRTC,

    /** HTTP Live Streaming (.m3u8) via Media3/ExoPlayer. */
    HLS,

    /** Motion JPEG stream via Media3 (progressive) or WebView fallback. */
    MJPEG,

    /** RTSP via libVLC (most tolerant) with a Media3 fallback. */
    RTSP
}

/**
 * A single camera definition. Persisted as JSON inside the settings DataStore.
 *
 * @param id stable identifier used in API/MQTT (`/camera?id=front_door`).
 * @param name human readable label shown in the UI.
 * @param type streaming protocol.
 * @param url stream/page URL. For WEBRTC this is a webpage URL that negotiates WebRTC.
 */
@Serializable
data class CameraDef(
    val id: String,
    val name: String,
    val type: CameraType,
    val url: String
)
