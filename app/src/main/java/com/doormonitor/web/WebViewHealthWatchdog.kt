package com.doormonitor.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import com.doormonitor.core.DiskLog
import com.doormonitor.core.WebViewHealth

/**
 * Detects a dashboard WebView whose render process is *alive but wedged* — painted, frozen on
 * stale content, ignoring touches — and drives recovery.
 *
 * This is the gap that let the kiosk die silently. The existing recovery paths cannot see it:
 *  - [android.webkit.WebViewClient.onRenderProcessGone] only fires when the renderer actually
 *    *dies*. A renderer stuck in a loop never dies, so it never fires.
 *  - [com.doormonitor.camera.StreamLivenessWatchdog] only watches video liveness, so a dashboard
 *    whose renderer is wedged but whose (cameraless) page has no video to judge is unprotected.
 *  - No ANR is raised either: the renderer is a *separate sandboxed process*, so its main thread
 *    hanging never trips the app process's input-dispatch watchdog. The app looks perfectly
 *    healthy from the outside while the screen is frozen — which is exactly what we observed.
 *
 * Detection is a JS round-trip with a timeout: we ask the renderer to evaluate a trivial
 * expression and require an answer within [UNRESPONSIVE_MS]. A wedged renderer never calls the
 * callback back. That timeout is the whole trick — [WebView.evaluateJavascript] has no built-in
 * one, so a naive JS heartbeat just stops silently along with the page.
 *
 * Enforcement is gated on the screen being interactive: the kiosk turns the display off, and a
 * throttled/backgrounded renderer is legitimately slow to answer. Acting then would mean
 * reloading the dashboard all night for no reason.
 */
class WebViewHealthWatchdog(
    private val webView: WebView,
    private val onWedged: (strike: Int) -> Unit,
    private val onRecovered: () -> Unit,
    private val tag: String = "WebViewHealth"
) {
    private val handler = Handler(Looper.getMainLooper())
    private val power =
        webView.context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var running = false

    /** uptimeMillis the outstanding ping was sent, or 0 when no ping is in flight. */
    private var pingSentAt = 0L

    /** Escalation counter for the current wedge; reset when the renderer answers again. */
    private var strikes = 0

    private var wasInteractive = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        strikes = 0
        pingSentAt = 0L
        wasInteractive = power.isInteractive
        handler.postDelayed(tick, PING_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun poll() {
        val interactive = power.isInteractive

        // Coming back from screen-off: the renderer may need a moment to spin back up. Drop any
        // in-flight ping and give it a fresh window rather than judging it on a stale timer.
        if (interactive && !wasInteractive) {
            pingSentAt = 0L
            strikes = 0
        }
        wasInteractive = interactive
        if (!interactive) {
            pingSentAt = 0L
            return
        }

        val now = SystemClock.uptimeMillis()

        if (pingSentAt != 0L) {
            val waiting = now - pingSentAt
            if (waiting > UNRESPONSIVE_MS) {
                strikes++
                WebViewHealth.onWedged()
                DiskLog.e(
                    tag,
                    "renderer unresponsive: no JS answer in ${waiting}ms (strike $strikes) " +
                        "url=${runCatching { webView.url }.getOrNull()}"
                )
                // Re-arm so the next escalation is another full timeout away, and let the host
                // act (reload, then kill the renderer).
                pingSentAt = now
                onWedged(strikes)
            }
            return // a ping is already in flight; don't pile more on
        }

        pingSentAt = now
        val sentAt = now
        runCatching {
            webView.evaluateJavascript(PING_EXPR) {
                // A wedged renderer simply never gets here.
                if (!running) return@evaluateJavascript
                val rtt = SystemClock.uptimeMillis() - sentAt
                pingSentAt = 0L
                val recovering = strikes > 0
                strikes = 0
                WebViewHealth.onPong(rtt)
                if (recovering) {
                    DiskLog.i(tag, "renderer responsive again after ${rtt}ms")
                    onRecovered()
                }
            }
        }.onFailure {
            pingSentAt = 0L
            DiskLog.w(tag, "ping failed to dispatch: ${it.message}")
        }
    }

    companion object {
        /** How often we ask the renderer to prove it is alive. */
        private const val PING_INTERVAL_MS = 15_000L

        /**
         * How long a trivial JS evaluation may take before we call the renderer wedged. Generous:
         * a busy-but-healthy renderer answers in milliseconds, so anything past this is a hang,
         * not slowness.
         */
        private const val UNRESPONSIVE_MS = 20_000L

        private const val PING_EXPR = "1"
    }
}
