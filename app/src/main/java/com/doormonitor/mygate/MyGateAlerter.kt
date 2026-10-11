package com.doormonitor.mygate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import com.doormonitor.R
import com.doormonitor.core.DiskLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A MyGate notification as shown on the kiosk's own screen. */
data class MyGateAlert(
    val type: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** Wall-clock time the card goes away; drives the on-card countdown. */
    val expiresAt: Long
)

/** The alert currently on screen, if any. Written by [MyGateAlerter], drawn by MainActivity. */
object MyGateAlerts {
    private val _current = MutableStateFlow<MyGateAlert?>(null)
    val current: StateFlow<MyGateAlert?> = _current.asStateFlow()

    internal fun show(alert: MyGateAlert) {
        _current.value = alert
    }

    fun dismiss() {
        _current.value = null
    }
}

/**
 * The kiosk's own reaction to a MyGate notification arriving on MQTT: wake the screen, ring a
 * chime and show a card for [DISPLAY_SECONDS], then let the screen sleep again.
 *
 * Driven by the `<base>/mygate/notification` topic rather than the notification listener
 * directly, so the tablet is just one more subscriber — anything else publishing there makes
 * it ring too.
 *
 * Only puts the screen back to sleep if an alert is what woke it: someone already using the
 * tablet gets the card and chime, but the screen is left on.
 */
class MyGateAlerter(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val wakeFor: (seconds: Int) -> Unit
) {
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var clearJob: Job? = null

    /** True while the screen is on because an alert woke it (so a follow-up alert extends it). */
    private var wokeScreen = false

    /** Safe to call from any thread (MQTT callbacks); all work is serialised on [scope]. */
    fun onNotification(payload: JSONObject) {
        scope.launch {
            val now = System.currentTimeMillis()
            val alert = MyGateAlert(
                type = payload.optString("event_type", MyGateNotificationListener.TYPE_GENERAL),
                title = payload.optStringOrNull("title") ?: "MyGate",
                text = payload.optStringOrNull("bigText")
                    ?: payload.optStringOrNull("text").orEmpty(),
                postTime = payload.optLong("postTime", now),
                expiresAt = now + DISPLAY_SECONDS * 1000L
            )
            val update = payload.optBoolean("update", false)
            DiskLog.i(TAG, "alert ${alert.type}${if (update) " (update)" else ""}: ${alert.title}")

            MyGateAlerts.show(alert)
            // An update is MyGate revising a notification it already showed: refresh the card,
            // but don't ring for the same visitor twice.
            if (!update) playChime()
            if (!power.isInteractive || wokeScreen) {
                wakeFor(DISPLAY_SECONDS)
                wokeScreen = true
            }

            clearJob?.cancel()
            clearJob = scope.launch {
                delay(DISPLAY_SECONDS * 1000L)
                MyGateAlerts.dismiss()
                wokeScreen = false
            }
        }
    }

    private fun playChime() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            MediaPlayer.create(appContext, R.raw.mygate_chime, attrs, audio.generateAudioSessionId())
                ?.apply {
                    setOnCompletionListener { it.release() }
                    start()
                }
        }.onFailure { DiskLog.e(TAG, "chime failed", it) }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).trim().ifEmpty { null }

    companion object {
        private const val TAG = "MyGateAlerter"
        const val DISPLAY_SECONDS = 15
    }
}
