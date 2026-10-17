package com.doormonitor.mygate

import com.doormonitor.core.DiskLog

/**
 * Which MyGate alerts the tablet shows: those whose text matches the pattern in Settings, so
 * MyGate's marketing pushes ("…Sale is Live!") stay off the screen. The pattern is a regular
 * expression, matched anywhere in the text and ignoring case. Blank shows everything.
 *
 * A visitor waiting for an answer and an emergency always show, whatever the pattern says: a
 * change in MyGate's wording must not hide either.
 */
class MyGateFilter(private val pattern: () -> String) {
    private var compiledFrom: String? = null
    private var compiled: Regex? = null

    /** [texts] are the alert's message, in whatever forms it came (text, expanded text). */
    fun shows(type: String, texts: List<String?>, awaitsAnswer: Boolean): Boolean {
        if (awaitsAnswer || type == MyGateNotificationListener.TYPE_EMERGENCY) return true
        val regex = regex() ?: return true
        return texts.any { it != null && regex.containsMatchIn(it) }
    }

    /** The current pattern, compiled once per change. Null (show everything) if blank or broken. */
    @Synchronized
    private fun regex(): Regex? {
        val source = pattern().trim()
        if (source != compiledFrom) {
            compiledFrom = source
            compiled = if (source.isEmpty()) null else runCatching { toRegex(source) }
                .onFailure { DiskLog.w(TAG, "pattern doesn't compile, showing every alert: ${it.message}") }
                .getOrNull()
        }
        return compiled
    }

    companion object {
        private const val TAG = "MyGateFilter"

        /**
         * Visitors checking in (and vehicles checking in or out), approval requests and their
         * outcomes, declined or denied entries, invites, and alerts about kids. The history card
         * on the dashboard defaults to the same.
         */
        const val DEFAULT_PATTERN = ".*(checked|approv|declined|denied|invite|kid).*"

        private fun toRegex(source: String) = Regex(source, RegexOption.IGNORE_CASE)

        /** Why [source] isn't a usable pattern, or null if it is. For the Settings screen. */
        fun patternError(source: String): String? =
            if (source.isBlank()) null
            else runCatching { toRegex(source.trim()) }.exceptionOrNull()
                ?.let { it.message?.lineSequence()?.firstOrNull() ?: "Invalid pattern" }
    }
}
