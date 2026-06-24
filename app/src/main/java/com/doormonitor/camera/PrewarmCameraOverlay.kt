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
 * A persistent, pre-warmed WebRTC camera surface.
 *
 * The WebView is created once and **kept attached to the window for the whole time this
 * composable is present** — even when [visible] is false (it is just shrunk to ~1dp and made
 * transparent). That keeps the go2rtc WebRTC peer connection alive and decoding, so toggling
 * [visible] to true shows the live feed instantly, with none of the per-open negotiation delay
 * that a freshly-created WebView incurs.
 *
 * Trade-off: the stream decodes continuously while configured, which uses some CPU/power on the
 * (permanently powered) tablet. It is therefore opt-in via the "pre-warm camera" setting.
 */
@Composable
fun PrewarmCameraOverlay(
    camera: CameraDef,
    visible: Boolean,
    onClose: () -> Unit
) {
    // Hold the WebView across recompositions and destroy it only when this leaves composition
    // (e.g. the pre-warm camera setting is cleared).
    val webViewHolder = remember { WebViewHolder() }
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder.webView?.apply { stopLoading(); destroy() }
            webViewHolder.webView = null
        }
    }

    Box(
        modifier = if (visible) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            // Still attached and decoding, but effectively invisible and out of the way.
            Modifier.size(1.dp).alpha(0f)
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                buildWebRtcWebView(ctx, camera.url).also { webViewHolder.webView = it }
            },
            update = { wv ->
                // Reconnect if the camera URL changed while pre-warmed.
                if (wv.url == null || (wv.url != camera.url && !wv.url!!.startsWith(camera.url))) {
                    wv.loadUrl(camera.url)
                }
            }
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
