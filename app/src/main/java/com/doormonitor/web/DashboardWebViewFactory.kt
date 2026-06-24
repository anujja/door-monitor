package com.doormonitor.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
import com.doormonitor.BuildConfig

/**
 * Builds and configures the kiosk WebView. Responsibilities:
 *  - hide all browser UI (no chrome, no zoom controls, no text selection handles)
 *  - persist Home Assistant session cookies across restarts
 *  - disable pull-to-refresh and long-press context menus
 *  - auto-reload when the render process is killed (WebView crash recovery)
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                CookieManager.getInstance().flush()
                onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                // Inject CSS to suppress text selection and overscroll glow (pull-refresh).
                view.evaluateJavascript(KIOSK_CSS_INJECTION, null)
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
                Log.e(TAG, "WebView render process gone; didCrash=${detail?.didCrash()}")
                // Must destroy the dead WebView; host recreates.
                view.destroy()
                onRenderGone()
                return true
            }
        }

        webView.webChromeClient = WebChromeClient()
        return webView
    }

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
