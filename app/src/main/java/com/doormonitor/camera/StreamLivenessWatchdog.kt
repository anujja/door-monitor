package com.doormonitor.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 *
 * Two guards keep a *permanent* failure from turning into a reload storm. Both exist because of
 * an observed incident: the kiosk lost network for 11 hours overnight with the camera overlay
 * left open, and this watchdog reloaded the page 4040 times — roughly one every 10s, all night —
 * until the churn wedged the Chromium renderer and froze the UI.
 *  - **Screen gate**: stalls are only judged while the display is interactive, matching
 *    [com.doormonitor.web.WebViewHealthWatchdog]. Nobody is watching a feed on a dark screen, and
 *    a backgrounded renderer is legitimately throttled, so reloading then is pure cost. Coming
 *    back on-screen restarts the grace window rather than judging against pre-sleep timestamps.
 *  - **Backoff**: each reload that fails to restore the video doubles the cooldown, up to
 *    [MAX_RELOAD_COOLDOWN_MS]. A reload cannot fix an unreachable network, so retrying at a fixed
 *    10s forever just burns CPU and decoder resources; the first few retries stay fast for the
 *    common case (a genuinely stalled feed that one reload fixes). Any actual frame progress
 *    resets the backoff.
 */
class StreamLivenessWatchdog(
    private val webView: WebView,
    private val mode: Mode = Mode.ALWAYS_EXPECT_VIDEO,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    private val reloadCooldownMs: Long = RELOAD_COOLDOWN_MS,
    private val tag: String = "StreamWatchdog",
    /**
     * How to reconnect a stalled stream. [WebView.reload] repeats the *committed* page, which is
     * not always the stream: a host that swaps the page out while hidden (the pre-warm overlay
     * parks on about:blank) and is still waiting for the stream page to load when the stall timer
     * fires would have the reload discard that load and repeat the blank page instead. Such a
     * host passes a reconnect that loads the stream URL explicitly.
     */
    private val reconnect: (WebView) -> Unit = WebView::reload
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
    private val power =
        webView.context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Whether stalls should currently trigger a reload. The pre-warm overlay only enables this
    // while its stream is connected; the rest of the time its page has no video to judge.
    private var enforcing = false
    private var running = false

    // Tracks display state so the grace window can be restarted when the screen comes back on.
    private var wasInteractive = true

    // Consecutive reloads that have not restored frame progress; drives the cooldown backoff.
    private var failedReloads = 0

    // Last `currentTime` (ms) seen, and when it last advanced. `lastProgress < 0` means we have
    // no baseline yet (just started/reset), so the next poll only records, it does not judge.
    private var lastProgress = -1L
    private var lastLiveAt = 0L
    private var lastReloadAt = 0L

    // ONLY_IF_WAS_LIVE latch: have we ever seen this video actually progress?
    private var seenLive = false

    // When enforcement last switched on (the host has just connected the stream), until the first
    // frame progress after it is logged; 0 = nothing pending. Records time-to-live in DiskLog.
    private var enforcedSince = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val interactive = runCatching { power.isInteractive }.getOrDefault(true)
            if (interactive != wasInteractive) {
                wasInteractive = interactive
                // Coming back on-screen: judge the feed against a fresh window rather than
                // timestamps from before the display slept, which would look like a long stall.
                if (interactive) resetWindow()
            }
            if (enforcing && interactive) poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        wasInteractive = runCatching { power.isInteractive }.getOrDefault(true)
        resetWindow()
        handler.postDelayed(tick, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /**
     * Enable/disable stall enforcement (e.g. follow whether the pre-warm overlay is connected).
     * Enabling resets the grace window so a feed that comes on-screen gets a fresh
     * [stallTimeoutMs] before it can be judged stalled.
     */
    fun setEnforcing(value: Boolean) {
        if (value == enforcing) return
        enforcing = value
        // A feed coming (back) on-screen is worth one prompt retry, so clear any accumulated
        // backoff from the period when nobody was looking at it.
        if (value) {
            failedReloads = 0
            enforcedSince = SystemClock.uptimeMillis()
            resetWindow()
        }
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
                    // Resolution is the poll interval: this is when progress was seen, not the
                    // exact first frame.
                    if (enforcedSince > 0) {
                        DiskLog.i(tag, "stream live ${now - enforcedSince}ms after it was enabled")
                        enforcedSince = 0
                    }
                    // Frames are flowing again: the last reload worked (or none was needed), so
                    // the next stall gets a fast first retry.
                    if (failedReloads > 0) {
                        DiskLog.i(tag, "stream recovered after $failedReloads reload(s)")
                        failedReloads = 0
                    }
                    return@evaluateJavascript
                }

                // Not advancing. Skip a page that never had a live video when the mode says so
                // (avoids reloading a cameraless dashboard forever).
                if (mode == Mode.ONLY_IF_WAS_LIVE && !seenLive) return@evaluateJavascript

                val cooldown = currentCooldownMs()
                if (now - lastLiveAt > stallTimeoutMs && now - lastReloadAt > cooldown) {
                    failedReloads++
                    DiskLog.w(
                        tag,
                        "stream stalled ${now - lastLiveAt}ms; reloading to reconnect " +
                            "(attempt $failedReloads, next retry in ${currentCooldownMs() / 1000}s)"
                    )
                    lastReloadAt = now
                    resetWindow()
                    seenLive = false
                    reconnect(webView)
                }
            }
        }
    }

    /**
     * Cooldown before the next reload: [reloadCooldownMs] doubled once per consecutive reload that
     * did not bring frames back, capped at [MAX_RELOAD_COOLDOWN_MS]. With the defaults that is
     * 10s, 20s, 40s … 5min — so a feed one reload can fix still recovers in seconds, while an
     * unreachable camera settles into a cheap 5-minute retry instead of hammering forever.
     */
    private fun currentCooldownMs(): Long {
        val doublings = failedReloads.coerceIn(0, MAX_BACKOFF_DOUBLINGS)
        return (reloadCooldownMs shl doublings).coerceAtMost(MAX_RELOAD_COOLDOWN_MS)
    }

    companion object {
        const val STALL_TIMEOUT_MS = 6_000L
        const val RELOAD_COOLDOWN_MS = 10_000L
        const val MAX_RELOAD_COOLDOWN_MS = 5 * 60 * 1_000L
        private const val POLL_INTERVAL_MS = 1_000L

        // Bounds the shift itself so the doubling can never overflow a Long.
        private const val MAX_BACKOFF_DOUBLINGS = 16

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
