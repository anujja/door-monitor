package com.doormonitor.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.doormonitor.core.KioskBus
import com.doormonitor.core.KioskCommand
import com.doormonitor.web.DashboardWebViewFactory
import kotlinx.coroutines.flow.filterIsInstance

/**
 * The kiosk dashboard. Hosts the configured Home Assistant URL in a hardened WebView,
 * recovers from render-process crashes by recreating the view, reloads after network
 * outages, and offers a hidden 5-tap corner gesture to open Settings.
 */
@Composable
fun DashboardScreen(
    url: String,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current

    // Recreating the WebView (key bump) is how we recover from a dead render process.
    var recreateKey by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadFailed by remember { mutableStateOf(false) }
    // Non-null while the last load failed: drives a visible error overlay so the dashboard is
    // never a silent black screen.
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // React to ReloadDashboard / LoadDashboard commands from API/MQTT.
    LaunchedEffect(Unit) {
        KioskBus.commands.filterIsInstance<KioskCommand>().collect { cmd ->
            when (cmd) {
                KioskCommand.ReloadDashboard -> webViewRef?.reload()
                KioskCommand.LoadDashboard -> webViewRef?.loadUrl(url)
                else -> Unit
            }
        }
    }

    // Reload when connectivity returns after an outage.
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (lastLoadFailed) {
                    webViewRef?.post { webViewRef?.reload() }
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url.isNotBlank()) {
            key(recreateKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        DashboardWebViewFactory.create(
                            context = ctx,
                            onRenderGone = {
                                webViewRef = null
                                recreateKey++ // rebuild the WebView subtree
                            },
                            onPageStarted = {
                                lastLoadFailed = false
                                errorMessage = null
                            },
                            onPageError = { msg ->
                                lastLoadFailed = true
                                errorMessage = msg
                            }
                        ).also { wv ->
                            webViewRef = wv
                            wv.loadUrl(url)
                        }
                    },
                    update = { wv ->
                        webViewRef = wv
                        val currentEmpty = wv.url.isNullOrBlank()
                        if (currentEmpty || wv.url == "about:blank") {
                            lastLoadFailed = false
                            errorMessage = null
                            wv.loadUrl(url)
                        }
                    }
                )
            }
        }

        // Visible state for the two "black screen" cases: no URL configured, or a load failure.
        when {
            url.isBlank() -> DashboardMessage(
                title = "No dashboard configured",
                detail = "Set your Home Assistant dashboard URL in Settings to get started.",
                primaryLabel = "Open Settings",
                onPrimary = onOpenSettings
            )
            errorMessage != null -> DashboardMessage(
                title = "Couldn't load the dashboard",
                detail = "$url\n\n${errorMessage}",
                primaryLabel = "Retry",
                onPrimary = {
                    errorMessage = null
                    webViewRef?.loadUrl(url) ?: run { recreateKey++ }
                },
                secondaryLabel = "Settings",
                onSecondary = onOpenSettings
            )
        }

        // Hidden settings gesture: 5 taps within 3s on the top-left corner.
        HiddenSettingsHotspot(
            modifier = Modifier.align(Alignment.TopStart),
            onActivate = onOpenSettings
        )
    }
}

@Composable
private fun BoxScope.DashboardMessage(
    title: String,
    detail: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .widthIn(max = 480.dp)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
            Button(onClick = onPrimary) { Text(primaryLabel) }
        }
    }
}

@Composable
private fun HiddenSettingsHotspot(modifier: Modifier, onActivate: () -> Unit) {
    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }
    Box(
        modifier = modifier
            .size(72.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - firstTapAt > 3000) {
                        firstTapAt = now
                        tapCount = 1
                    } else {
                        tapCount++
                    }
                    if (tapCount >= 5) {
                        tapCount = 0
                        onActivate()
                    }
                })
            }
    )
}
