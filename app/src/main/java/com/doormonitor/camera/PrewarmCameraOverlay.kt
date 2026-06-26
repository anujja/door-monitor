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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.doormonitor.data.CameraDef

/**
 * Persistent, pre-warmed overlay for a **WebRTC** camera. The WebView is created once and kept
 * attached for the whole time this is composed (even while hidden, shrunk to ~1dp and
 * transparent) so the WebRTC connection stays warm and toggling [visible] shows it instantly.
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
            holder.webView?.apply { stopLoading(); destroy() }
            holder.webView = null
        }
    }

    Box(
        modifier = if (visible) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.size(1.dp).alpha(0f)
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> buildWebRtcWebView(ctx, camera.url).also { holder.webView = it } }
        )

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
}
