package com.doormonitor.camera

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.doormonitor.core.DiskLog
import com.doormonitor.data.CameraDef

/**
 * Persistent overlay for a **WebRTC** camera. The WebView is created once and kept attached for
 * the whole time this is composed (while hidden it is shrunk to ~1dp and transparent), so its
 * render process is already up when a camera command arrives.
 *
 * The stream itself is only connected while someone can see it: while [visible] *and* the host
 * Activity is started, which it is not while the display is asleep. The rest of the time the
 * WebView sits on about:blank, so no peer connection is open — go2rtc stops pulling the camera,
 * and neither the tablet nor the NAS spends anything on video nobody is watching. Showing the
 * overlay loads the camera page fresh; on the LAN that is live in about 2s (go2rtc reconnects to
 * the camera, then waits for its next keyframe, which the door camera sends every second).
 *
 * An earlier version kept the connection open while hidden, to make showing it instant. It
 * streamed ~4 Mbit/s of 5MP video around the clock, and it did not deliver on the promise: a
 * hidden WebView is never drawn (alpha 0), so Chromium decodes every frame and then drops it, and
 * on about a third of shows the feed came back frozen on the last frame it had painted — a view
 * of the door from whenever the overlay was last on-screen — until the liveness watchdog noticed
 * and reloaded it ~10s later. A freshly loaded page has no old frame to show.
 *
 * RTSP/MJPEG/HLS cameras are NOT handled here — they are shown via the full-screen
 * [CameraActivity] (native libVLC), which renders reliably with correct aspect ratio and
 * without the surface-compositing issues of embedding a video surface in a toggled overlay.
 */
@Composable
fun PrewarmCameraOverlay(
    camera: CameraDef,
    visible: Boolean,
    onClose: () -> Unit
) {
    val holder = remember { WebViewHolder() }
    DisposableEffect(Unit) {
        onDispose {
            holder.webView?.apply { streamWatchdog?.stop(); stopLoading(); destroy() }
            holder.webView = null
        }
    }

    // Recreating the WebView (key bump) is how we recover from a dead render process.
    var recreateKey by remember { mutableIntStateOf(0) }

    // The Activity stops when the display sleeps (or something covers the kiosk), and a hidden
    // page is not decoded anyway, so there is nothing to stream to.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val streaming = visible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // Connect when the feed comes on-screen, disconnect when it leaves. Also keyed on recreateKey
    // so a WebView rebuilt after a render-process crash picks up the current state. Stall
    // enforcement follows the connection: an unconnected page has no video to judge.
    LaunchedEffect(streaming, recreateKey) {
        val webView = holder.webView ?: return@LaunchedEffect
        if (streaming) {
            DiskLog.i(TAG, "${camera.id}: overlay on screen; connecting stream")
            holder.connected = true
            webView.loadUrl(camera.url)
            webView.streamWatchdog?.setEnforcing(true)
        } else {
            webView.streamWatchdog?.setEnforcing(false)
            // Decided by what was requested, not by webView.url: that keeps reporting the old page
            // until a navigation commits, so a camera load still in flight would look blank and be
            // left to connect while hidden. Navigating away supersedes that load and tears down
            // the page's peer connection; go2rtc then drops the camera.
            if (holder.connected) {
                holder.connected = false
                DiskLog.i(TAG, "${camera.id}: overlay off screen; disconnecting stream")
                webView.loadUrl(BLANK_URL)
            }
        }
    }

    Box(
        modifier = if (visible) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.size(1.dp).alpha(0f)
        }
    ) {
        key(recreateKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Built idle: the effect above loads the page once the overlay is on screen.
                    buildWebRtcWebView(
                        ctx,
                        camera.url,
                        enforceLiveness = false,
                        load = false,
                        onRenderGone = { holder.webView = null; recreateKey++ }
                    ).also { holder.webView = it; holder.connected = false }
                }
            )
        }

        if (visible) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null

    // Whether the camera page has been requested since the WebView was built or last blanked.
    var connected = false
}

private const val TAG = "CameraOverlay"
private const val BLANK_URL = "about:blank"
