package com.doormonitor.core

import android.os.SystemClock

/**
 * Live health snapshot of the dashboard WebView, published by
 * [com.doormonitor.web.WebViewHealthWatchdog] and read by the service heartbeat so the periodic
 * log line records what the dashboard was doing in the run-up to a failure.
 */
object WebViewHealth {

    enum class State { UNKNOWN, OK, WEDGED }

    @Volatile
    var state: State = State.UNKNOWN
        private set

    /** uptimeMillis of the last successful JS round-trip ("the renderer answered"). */
    @Volatile
    var lastPongAt: Long = 0L
        private set

    /** Round-trip time of the last successful JS ping, in ms. */
    @Volatile
    var lastPingRttMs: Long = -1L
        private set

    /** How many times we've reloaded / recreated the WebView to recover from a wedge. */
    @Volatile
    var reloads: Int = 0
        private set

    @Volatile
    var recreates: Int = 0
        private set

    fun onPong(rttMs: Long) {
        state = State.OK
        lastPongAt = SystemClock.uptimeMillis()
        lastPingRttMs = rttMs
    }

    fun onWedged() {
        state = State.WEDGED
    }

    fun onReload() {
        reloads++
    }

    fun onRecreate() {
        recreates++
    }

    /** Milliseconds since the renderer last answered, or -1 if it never has. */
    fun sinceLastPongMs(): Long =
        if (lastPongAt == 0L) -1L else SystemClock.uptimeMillis() - lastPongAt

    fun summary(): String =
        "webview=$state lastPong=${sinceLastPongMs()}ms ago rtt=${lastPingRttMs}ms " +
            "reloads=$reloads recreates=$recreates"
}
