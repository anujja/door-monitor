package com.doormonitor.camera

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.doormonitor.data.CameraDef
import com.doormonitor.data.CameraType
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Renders a single camera by protocol:
 *  - WEBRTC -> WebView (negotiates WebRTC in-page, e.g. go2rtc / HA webrtc-camera)
 *  - HLS    -> Media3 / ExoPlayer
 *  - RTSP   -> libVLC (most tolerant, low latency with TCP)
 *  - MJPEG  -> libVLC (handles multipart JPEG demux)
 */
@Composable
fun CameraSurface(camera: CameraDef, modifier: Modifier = Modifier) {
    when (camera.type) {
        CameraType.WEBRTC -> WebRtcSurface(camera, modifier)
        CameraType.HLS -> Media3Surface(camera, modifier)
        CameraType.RTSP, CameraType.MJPEG -> VlcSurface(camera, modifier)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Media3Surface(camera: CameraDef, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val player = ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(camera.url))
                playWhenReady = true
                prepare()
            }
            PlayerView(ctx).apply {
                useController = false
                this.player = player
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        onRelease = { view -> view.player?.release(); view.player = null }
    )
}

@Composable
private fun VlcSurface(camera: CameraDef, modifier: Modifier) {
    // Hold VLC objects across recomposition and release them on dispose.
    val holder = rememberVlcHolder()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val args = arrayListOf(
                "--rtsp-tcp",           // RTSP over TCP: reliable on Wi-Fi
                "--network-caching=300", // stable buffer (lower values stutter on 5MP/Wi-Fi)
                "--no-audio",           // wall tablet: muted by default
                "--avcodec-hw=any"
            )
            val libVlc = LibVLC(ctx, args)
            val mediaPlayer = MediaPlayer(libVlc)
            val videoLayout = VLCVideoLayout(ctx)
            mediaPlayer.attachViews(videoLayout, null, false, false)
            val media = Media(libVlc, Uri.parse(camera.url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=300")
            }
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()

            holder.libVlc = libVlc
            holder.player = mediaPlayer
            videoLayout
        },
        onRelease = {
            holder.player?.apply { stop(); detachViews(); release() }
            holder.libVlc?.release()
            holder.player = null
            holder.libVlc = null
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebRtcSurface(camera: CameraDef, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> buildWebRtcWebView(ctx, camera.url) },
        onRelease = { it.destroy() }
    )
}

/**
 * Creates a WebView configured for a go2rtc/WebRTC camera page and starts loading it.
 * Shared by the full-screen [CameraActivity] surface and the persistent pre-warm overlay so
 * both behave identically.
 */
@SuppressLint("SetJavaScriptEnabled")
fun buildWebRtcWebView(context: android.content.Context, url: String): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(android.graphics.Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        loadUrl(url)
    }

/** Simple mutable container so VLC native objects survive recomposition. */
private class VlcHolder {
    var libVlc: LibVLC? = null
    var player: MediaPlayer? = null
}

@Composable
private fun rememberVlcHolder(): VlcHolder {
    val holder = androidx.compose.runtime.remember { VlcHolder() }
    DisposableEffect(Unit) {
        onDispose {
            holder.player?.apply { runCatching { stop(); detachViews(); release() } }
            holder.libVlc?.release()
        }
    }
    return holder
}
