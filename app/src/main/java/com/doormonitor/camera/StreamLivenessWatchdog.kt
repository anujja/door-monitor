package com.doormonitor.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import com.doormonitor.core.DiskLog

/**
 * Detects a stalled WebRTC video inside a [WebView] and forces a reconnect by reloading the page.
 * A go2rtc WebRTC feed dies silently in two ways, both observed on the door camera:
 *  - it connects, then the media flow stops (the video track goes `muted`) — usually after the
 *    pre-warm WebView was hidden/throttled — and the `<video>` freezes on its last frame;
 *  - it connects but never delivers a single frame, so the `<video>` shows only its placeholder
 *    play-button poster (`currentTime` stuck at 0, `videoWidth` 0).
 * In both cases the only recovery is reloading the page; go2rtc's own page does not self-heal.
 *
 * Liveness is judged natively, on a main-thread [Handler] ticker, by polling the video element's
 * `currentTime`: a decoding stream advances it every tick, a stalled one does not. This is
 * deliberately self-contained — a single [evaluateJavascript] expression per poll, with no state
 * injected into the page. An earlier design injected a `requestVideoFrameCallback` frame counter,
 * but that counter (a) is wiped whenever go2rtc reloads the page to reconnect and (b) can never
 * advance for a stream that never delivers a first frame, so it could not see the second stall
 * above at all. Reading `currentTime` needs nothing injected and covers both cases.
 *
 * The ticker runs on the main thread on purpose: Android/Chromium throttles JS timers in
 * offscreen / low-visibility WebViews (the pre-warm-hidden case), so a pure-JS watchdog could
 * freeze along with the stream.
 */
class StreamLivenessWatchdog(
    private val webView: WebView,
    private val mode: Mode = Mode.ALWAYS_EXPECT_VIDEO,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    private val reloadCooldownMs: Long = RELOAD_COOLDOWN_MS,
    private val tag: String = "StreamWatchdog"
) {
    enum class Mode {
        /**
         * A dedicated camera page: a live stream is always expected. Reload whenever the video is
         * not advancing for longer than [stallTimeoutMs] — including a stream that connects but
         * never delivers its first frame.
         */
        ALWAYS_EXPECT_VIDEO,

        /**
         * An embedded page that may legitimately have no (or an idle) video, e.g. a Home Assistant
         * dashboard without a camera card. Only reload a video that was progressing and then
         * stalled; never reload a page that simply never had a live video.
         */
        ONLY_IF_WAS_LIVE
    }

    private val handler = Handler(Looper.getMainLooper())

    // Whether stalls should currently trigger a reload. Gated by visibility for the pre-warm
    // overlay so throttled frame delivery while hidden can't cause false reloads.
    private var enforcing = false
    private var running = false

    // Last `currentTime` (ms) seen, and when it last advanced. `lastProgress < 0` means we have
    // no baseline yet (just started/reset), so the next poll only records, it does not judge.
    private var lastProgress = -1L
    private var lastLiveAt = 0L
    private var lastReloadAt = 0L

    // ONLY_IF_WAS_LIVE latch: have we ever seen this video actually progress?
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
        lastProgress = -1L
        lastLiveAt = SystemClock.uptimeMillis()
    }

    private fun poll() {
        runCatching {
            webView.evaluateJavascript(PROGRESS_EXPR) { result ->
                if (!running || !enforcing) return@evaluateJavascript
                val progress = result?.trim('"')?.toLongOrNull() ?: return@evaluateJavascript
                val now = SystemClock.uptimeMillis()

                // First poll after a (re)start: establish a baseline, don't judge yet.
                if (lastProgress < 0) {
                    lastProgress = progress
                    lastLiveAt = now
                    return@evaluateJavascript
                }

                // currentTime jumped backwards: the page reloaded itself to reconnect (go2rtc
                // does this) and playback restarted from 0. Re-baseline rather than mistaking the
                // reset for a stall.
                if (progress < lastProgress) {
                    lastProgress = progress
                    lastLiveAt = now
                    return@evaluateJavascript
                }

                if (progress > lastProgress) {
                    lastProgress = progress
                    lastLiveAt = now
                    seenLive = true
                    return@evaluateJavascript
                }

                // Not advancing. Skip a page that never had a live video when the mode says so
                // (avoids reloading a cameraless dashboard forever).
                if (mode == Mode.ONLY_IF_WAS_LIVE && !seenLive) return@evaluateJavascript

                if (now - lastLiveAt > stallTimeoutMs && now - lastReloadAt > reloadCooldownMs) {
                    DiskLog.w(tag, "stream stalled ${now - lastLiveAt}ms; reloading to reconnect")
                    lastReloadAt = now
                    resetWindow()
                    seenLive = false
                    webView.reload()
                }
            }
        }
    }

    companion object {
        const val STALL_TIMEOUT_MS = 6_000L
        const val RELOAD_COOLDOWN_MS = 10_000L
        private const val POLL_INTERVAL_MS = 1_000L

        /**
         * Reports the furthest-progressed playback position (ms) across all non-paused `<video>`
         * elements, or 0 when none is progressing. A live decode advances this every poll; a
         * stalled or never-started stream (muted track, no frames) leaves it frozen.
         */
        private const val PROGRESS_EXPR =
            "(function(){var vs=document.querySelectorAll('video'),t=0;" +
                "for(var i=0;i<vs.length;i++){if(!vs[i].paused&&vs[i].currentTime>t)" +
                "t=vs[i].currentTime;}return Math.round(t*1000);})()"
    }
}
