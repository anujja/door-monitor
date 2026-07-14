package com.doormonitor.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import com.doormonitor.BuildConfig
import com.doormonitor.R
import com.doormonitor.camera.StreamLivenessWatchdog
import com.doormonitor.core.DiskLog
import com.doormonitor.core.WebViewHealth
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds and configures the kiosk WebView. Responsibilities:
 *  - hide all browser UI (no chrome, no zoom controls, no text selection handles)
 *  - persist Home Assistant session cookies across restarts
 *  - disable pull-to-refresh and long-press context menus
 *  - auto-reload when the render process is killed (WebView crash recovery)
 *  - detect and recover a render process that is alive but *wedged* (see [WebViewHealthWatchdog])
 *  - disable remote debugging in release builds
 */
object DashboardWebViewFactory {

    private const val TAG = "DashboardWebView"

    /**
     * @param onRenderGone invoked when the WebView render process dies; the host should
     *        recreate/reload. Returning true tells the system we handled it.
     * @param onPageStarted invoked when a main-frame load begins, so the host can clear any
     *        previously shown error overlay.
     * @param onPageError invoked on a main-frame load failure with a human-readable reason, so
     *        the host can show a visible error instead of a silent black screen.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        onRenderGone: () -> Unit,
        onPageStarted: () -> Unit,
        onPageError: (String) -> Unit
    ): WebView {
        // Remote debugging only in debug builds (security requirement).
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isLongClickable = false
            // Block long-press selection/context menus (kiosk).
            setOnLongClickListener { true }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    requestFocus()
                }
                false
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // autoplay camera streams
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Identify clearly; some HA frontends tune layout for known UAs.
            userAgentString = "$userAgentString DoorMonitor/${BuildConfig.VERSION_NAME}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        // Persist cookies (HA auth session) to disk and flush eagerly.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Recover an embedded camera card (e.g. go2rtc WebRTC) that freezes on a frame. The
        // timeout is long and a full page reload is heavy, so the watchdog only acts once a video
        // has actually been progressing and then stalls (see StreamLivenessWatchdog.seenLive).
        val watchdog = StreamLivenessWatchdog(
            webView,
            stallTimeoutMs = DASHBOARD_STALL_TIMEOUT_MS,
            tag = "DashboardStreamWatchdog"
        )
        webView.setTag(R.id.tag_stream_watchdog, watchdog)

        // --- Wedged-renderer recovery ladder -------------------------------------------------
        // Escalates rather than jumping straight to the nuclear option, because a reload is
        // invisible to the user while a recreate flashes the dashboard:
        //   strike 1 -> reload() — may un-stick a partially wedged page.
        //   strike 2 -> terminate the render process. This is the one that always works: it runs
        //               in the browser process, so a hung renderer cannot ignore it. Killing it
        //               fires onRenderProcessGone below, which recreates the WebView through the
        //               existing crash-recovery path.
        // The cooldown gives a reload time to actually land before we escalate past it.
        var wedgeStrikes = 0
        var lastActionAt = 0L
        // The ladder needs to stop the health watchdog when it tears the WebView down, but the
        // watchdog is constructed below (it needs the ladder). Hold it indirectly.
        val healthRef = AtomicReference<WebViewHealthWatchdog?>()

        fun onWedgeDetected(source: String) {
            val now = SystemClock.uptimeMillis()
            if (lastActionAt != 0L && now - lastActionAt < RECOVERY_COOLDOWN_MS) return
            lastActionAt = now
            wedgeStrikes++

            if (wedgeStrikes == 1) {
                DiskLog.w(TAG, "wedged renderer ($source); reloading dashboard")
                WebViewHealth.onReload()
                runCatching { webView.reload() }
                return
            }

            DiskLog.e(
                TAG,
                "wedged renderer ($source) survived a reload; terminating render process"
            )
            val renderer: WebViewRenderProcess? = runCatching { webView.webViewRenderProcess }
                .getOrNull()
            // On success this fires onRenderProcessGone, which counts the recreate and rebuilds.
            val terminated = renderer?.let { runCatching { it.terminate() }.getOrDefault(false) }
                ?: false
            if (!terminated) {
                // No renderer handle (or terminate refused) — fall back to tearing the WebView
                // down directly so the host rebuilds it.
                DiskLog.e(TAG, "terminate() unavailable; destroying WebView directly")
                WebViewHealth.onRecreate()
                watchdog.stop()
                healthRef.get()?.stop()
                runCatching { webView.destroy() }
                onRenderGone()
            }
        }

        val health = WebViewHealthWatchdog(
            webView = webView,
            onWedged = { _ -> onWedgeDetected("js-ping") },
            onRecovered = { wedgeStrikes = 0 },
            tag = "DashboardHealth"
        )
        healthRef.set(health)
        webView.setTag(R.id.tag_health_watchdog, health)

        // Chromium's own input-ack based detector. Independent of the JS ping above and usually
        // faster; both feed the same ladder.
        val mainExecutor: Executor = Executor { r -> webView.post(r) }
        webView.setWebViewRenderProcessClient(
            mainExecutor,
            object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(
                    view: WebView,
                    renderer: WebViewRenderProcess?
                ) {
                    WebViewHealth.onWedged()
                    onWedgeDetected("chromium-unresponsive")
                }

                override fun onRenderProcessResponsive(
                    view: WebView,
                    renderer: WebViewRenderProcess?
                ) {
                    if (wedgeStrikes > 0) {
                        DiskLog.i(TAG, "render process responsive again")
                        wedgeStrikes = 0
                    }
                }
            }
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                CookieManager.getInstance().flush()
                onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                // Inject CSS to suppress text selection and overscroll glow (pull-refresh).
                view.evaluateJavascript(KIOSK_CSS_INJECTION, null)
                StreamLivenessWatchdog.injectInto(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    val code = error.errorCode
                    val desc = error.description
                    Log.w(TAG, "Main frame error: $code $desc")
                    // ERR_CLEARTEXT_NOT_PERMITTED surfaces here when loading http:// while
                    // cleartext is disabled — a common cause of a "black screen".
                    val hint = if (desc?.contains("CLEARTEXT", ignoreCase = true) == true) {
                        "\n\nThis URL uses plain http:// but cleartext traffic is blocked. " +
                            "Use https://, or allow the host in network_security_config.xml."
                    } else ""
                    onPageError("Failed to load (${desc ?: code}).$hint")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "Main frame HTTP ${errorResponse.statusCode}")
                    onPageError("Server returned HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase ?: ""}".trim())
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // SECURITY: do NOT silently proceed on certificate errors. Cancel and surface
                // it so the user can fix the cert / use a trusted CA instead of a silent fail.
                Log.w(TAG, "SSL error: ${error.primaryError} for ${error.url}")
                handler.cancel()
                onPageError(
                    "TLS certificate problem (${sslErrorText(error.primaryError)}).\n\n" +
                        "Home Assistant's certificate isn't trusted by Android. Use a valid " +
                        "certificate, or install your CA on the tablet."
                )
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // didCrash=false here means *we* killed it from the recovery ladder above.
                DiskLog.e(
                    TAG,
                    "render process gone; didCrash=${detail?.didCrash()} — recreating WebView"
                )
                WebViewHealth.onRecreate()
                // Must destroy the dead WebView; host recreates.
                watchdog.stop()
                healthRef.get()?.stop()
                view.destroy()
                onRenderGone()
                return true
            }
        }

        webView.webChromeClient = WebChromeClient()
        watchdog.start()
        watchdog.setEnforcing(true)
        health.start()
        return webView
    }

    /** Stalled-video timeout for the dashboard; long because reloading the whole HA page is heavy. */
    private const val DASHBOARD_STALL_TIMEOUT_MS = 15_000L

    /**
     * Minimum gap between two recovery actions on a wedged renderer. Long enough for a reload to
     * actually land (and for the renderer to answer a ping again) before we escalate to killing it.
     */
    private const val RECOVERY_COOLDOWN_MS = 30_000L

    private fun sslErrorText(primaryError: Int): String = when (primaryError) {
        android.net.http.SslError.SSL_UNTRUSTED -> "untrusted certificate"
        android.net.http.SslError.SSL_EXPIRED -> "expired"
        android.net.http.SslError.SSL_IDMISMATCH -> "hostname mismatch"
        android.net.http.SslError.SSL_NOTYETVALID -> "not yet valid"
        android.net.http.SslError.SSL_DATE_INVALID -> "invalid date"
        else -> "error $primaryError"
    }

    /** CSS injected after each load to enforce kiosk feel (no selection, no overscroll). */
    private val KIOSK_CSS_INJECTION = """
        (function() {
          var style = document.getElementById('doormonitor-kiosk-style');
          if (!style) {
            style = document.createElement('style');
            style.id = 'doormonitor-kiosk-style';
            style.innerHTML = `
              * { -webkit-user-select: none !important; user-select: none !important;
                  -webkit-touch-callout: none !important; }
              html, body { overscroll-behavior: none !important; }
            `;
            document.head.appendChild(style);
          }
        })();
    """.trimIndent()
}
