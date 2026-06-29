package com.doormonitor.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView

/**
 * Detects a stalled WebRTC video inside a [WebView] and forces a reconnect by reloading the
 * page. This exists because a go2rtc WebRTC feed can silently die (Wi-Fi blip, ICE timeout,
 * server restart) while the `<video>` element keeps painting its last decoded frame forever —
 * the only thing that previously recovered it was destroying the WebView (force-closing the app).
 *
 * The decision of *whether* the stream is stalled is made here, in native code on a main-thread
 * [Handler] ticker, on purpose: Android/Chromium throttles JS timers in offscreen / low-visibility
 * WebViews (exactly the pre-warm-hidden case), so a pure-JS watchdog could freeze along with the
 * stream. The injected JS ([WATCH_JS]) only maintains an event-driven, per-decoded-frame counter;
 * the native ticker polls it.
 */
class StreamLivenessWatchdog(
    private val webView: WebView,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    private val reloadCooldownMs: Long = RELOAD_COOLDOWN_MS,
    private val tag: String = "StreamWatchdog"
) {
    private val handler = Handler(Looper.getMainLooper())

    // Whether stalls should currently trigger a reload. Gated by visibility for the pre-warm
    // overlay so throttled frame callbacks while hidden can't cause false reloads.
    private var enforcing = false
    private var running = false

    // Last liveness value seen from JS, and when it last advanced.
    private var lastLiveness: Long? = null
    private var lastProgressAt = 0L
    private var lastReloadAt = 0L

    // Latch: only reload once we've actually seen a video progressing. Prevents reloading pages
    // that simply have no video (e.g. a Home Assistant dashboard without a camera card).
    private var seenLive = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (enforcing) poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        resetWindow()
        handler.postDelayed(tick, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /**
     * Enable/disable stall enforcement (e.g. follow the pre-warm overlay's visibility). Enabling
     * resets the grace window so a feed that comes on-screen gets a fresh [stallTimeoutMs] before
     * it can be judged stalled.
     */
    fun setEnforcing(value: Boolean) {
        if (value == enforcing) return
        enforcing = value
        if (value) resetWindow()
    }

    private fun resetWindow() {
        lastLiveness = null
        lastProgressAt = SystemClock.uptimeMillis()
        seenLive = false
    }

    private fun poll() {
        webView.evaluateJavascript(LIVENESS_EXPR) { result ->
            if (!running || !enforcing) return@evaluateJavascript
            val value = result?.trim('"')?.toLongOrNull()
            val now = SystemClock.uptimeMillis()
            if (value != null && value > 0L) {
                seenLive = true
                if (value != lastLiveness) {
                    lastLiveness = value
                    lastProgressAt = now
                    return@evaluateJavascript
                }
            }
            // Only treat a lack of progress as a stall once a video was actually live; otherwise a
            // page with no video would reload forever.
            if (!seenLive) return@evaluateJavascript
            if (now - lastProgressAt > stallTimeoutMs && now - lastReloadAt > reloadCooldownMs) {
                Log.w(tag, "Stream stalled ${now - lastProgressAt}ms; reloading WebView to reconnect")
                lastReloadAt = now
                resetWindow()
                webView.reload()
            }
        }
    }

    companion object {
        const val STALL_TIMEOUT_MS = 6_000L
        const val RELOAD_COOLDOWN_MS = 10_000L
        private const val POLL_INTERVAL_MS = 1_000L

        private const val LIVENESS_EXPR = "(window.__dmLiveness?window.__dmLiveness():0)"

        /**
         * Injected after each page load. Maintains [window.__dmLiveness], a number that strictly
         * increases while any `<video>` is actually decoding frames:
         *  - primary: an event-driven `requestVideoFrameCallback` loop per `<video>`, so the count
         *    reflects real frame delivery (not a timer that throttling could stop);
         *  - fallback: the max `video.currentTime` (ms) for engines without `requestVideoFrameCallback`.
         * A `MutationObserver` hooks `<video>` elements added later (go2rtc creates the element
         * after WebRTC negotiation completes).
         */
        const val WATCH_JS = """
            (function() {
              if (window.__dmWatchInstalled) return;
              window.__dmWatchInstalled = true;
              window.__dmFrames = 0;
              function hook(v) {
                if (!v || v.__dmHooked) return;
                v.__dmHooked = true;
                if (typeof v.requestVideoFrameCallback === 'function') {
                  var cb = function() {
                    window.__dmFrames++;
                    try { v.requestVideoFrameCallback(cb); } catch (e) {}
                  };
                  try { v.requestVideoFrameCallback(cb); } catch (e) {}
                }
              }
              function scan() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) hook(vids[i]);
              }
              window.__dmLiveness = function() {
                if (window.__dmFrames > 0) return window.__dmFrames;
                var vids = document.querySelectorAll('video');
                var t = 0;
                for (var i = 0; i < vids.length; i++) {
                  if (!vids[i].paused && vids[i].currentTime > t) t = vids[i].currentTime;
                }
                return Math.floor(t * 1000);
              };
              scan();
              try {
                new MutationObserver(scan).observe(
                  document.documentElement, { childList: true, subtree: true });
              } catch (e) {}
            })();
        """

        /** Run [WATCH_JS] in [webView] (call from WebViewClient.onPageFinished). */
        fun injectInto(webView: WebView) {
            webView.evaluateJavascript(WATCH_JS, null)
        }
    }
}
