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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Who is at the gate, as far as MyGate says. Every field is optional. */
data class MyGateVisitor(
    val name: String?,
    /** Where they're from: "Swiggy", "Amazon"… */
    val company: String?,
    val companyLogo: String?,
    val photo: String?
)

/** How far an answer given on this tablet has got. */
sealed interface MyGateAnswer {
    data class Sending(val response: String) : MyGateAnswer
    data class Done(val message: String) : MyGateAnswer
    data class Failed(val message: String) : MyGateAnswer
}

/** A MyGate alert as shown on the kiosk's own screen. */
data class MyGateAlert(
    /** The alert's id; answers refer to it. */
    val id: String?,
    val type: String,
    val title: String,
    val text: String,
    val visitor: MyGateVisitor?,
    /** What can be sent back (`approve`, `deny`, `leave_at_gate`); empty = nothing to answer. */
    val responses: List<String>,
    val postTime: Long,
    /** When the card appeared, and when it goes away: drives the on-card countdown. */
    val shownAt: Long,
    val expiresAt: Long,
    val answer: MyGateAnswer? = null
)

/** The alert currently on screen, if any. Written by [MyGateAlerter], drawn by MainActivity. */
object MyGateAlerts {
    private val _current = MutableStateFlow<MyGateAlert?>(null)
    val current: StateFlow<MyGateAlert?> = _current.asStateFlow()

    private val _responses = MutableSharedFlow<String>(extraBufferCapacity = 4)
    internal val responses: SharedFlow<String> = _responses.asSharedFlow()

    internal fun show(alert: MyGateAlert) {
        _current.value = alert
    }

    internal fun update(transform: (MyGateAlert) -> MyGateAlert) {
        _current.update { it?.let(transform) }
    }

    fun dismiss() {
        _current.value = null
    }

    /** A card button: answer the visitor on screen. [MyGateAlerter] sends it. */
    fun respond(response: String) {
        _responses.tryEmit(response)
    }
}

/**
 * The kiosk's own reaction to MyGate alerts arriving on MQTT: wake the screen, ring a chime and
 * show a card, then let the screen sleep again.
 *
 * Driven by the `<base>/mygate/…` topics rather than by MyGate directly, so the tablet is just
 * one more subscriber. A visitor waiting at the gate gets Approve / Deny buttons when the alert
 * lists `responses` (the Home Assistant MyGate integration does). A tap publishes to
 * `<base>/mygate/respond`, and the card follows the outcome on `…/response` and
 * `…/notification_removed`. Such a card stays up until the visitor is answered (here or
 * anywhere else) or MyGate gives up; other alerts show for [DISPLAY_SECONDS].
 *
 * Only puts the screen back to sleep if an alert is what woke it: someone already using the
 * tablet gets the card and chime, but the screen is left on.
 */
class MyGateAlerter(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val wakeFor: (seconds: Int) -> Unit,
    /** Switches the screen off directly, past [holdScreenOff]. */
    private val screenOff: () -> Unit,
    /** Publishes an answer; false when it can't (MQTT down). */
    private val publishResponse: (id: String, response: String) -> Boolean
) {
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var clearJob: Job? = null
    private var sendTimeoutJob: Job? = null

    /** True while the screen is on because an alert woke it (so a follow-up alert extends it). */
    private var wokeScreen = false

    /** A screen-off was asked for while a visitor waited; carried out when the card goes. */
    @Volatile
    private var screenOffHeld = false

    init {
        scope.launch { MyGateAlerts.responses.collect(::respond) }
        scope.launch {
            MyGateAlerts.current.collect { alert ->
                if (alert == null && screenOffHeld) {
                    screenOffHeld = false
                    DiskLog.i(TAG, "visitor card closed; carrying out the held screen-off")
                    screenOff()
                }
            }
        }
    }

    /**
     * For [com.doormonitor.core.CommandHandler.screenOffHold]: while a visitor is waiting for an
     * answer, hold an outside screen-off (say, an automation's) until the card closes, so
     * nobody misses the chance to let them in. Safe to call from any thread.
     */
    fun holdScreenOff(): Boolean {
        val alert = MyGateAlerts.current.value ?: return false
        if (alert.responses.isEmpty() || alert.answer is MyGateAnswer.Done) return false
        screenOffHeld = true
        DiskLog.i(TAG, "holding a screen-off until visitor ${alert.id}'s card closes")
        return true
    }

    /** One message from `<base>/mygate/<subtopic>`. Safe to call from any thread. */
    fun onMqtt(subtopic: String, payload: JSONObject) {
        scope.launch {
            when (subtopic) {
                "notification" -> show(payload)
                "notification_removed" -> removed(payload)
                "response" -> responseResult(payload)
            }
        }
    }

    private fun show(payload: JSONObject) {
        val now = System.currentTimeMillis()
        val id = payload.optStringOrNull("id")
        val responses = payload.optJSONArray("responses")
            ?.let { a -> List(a.length()) { a.optString(it) } }
            ?.filter { it in RESPONSE_LABELS }
            .orEmpty()
        val postTime = payload.optLong("postTime", now)
        val timeoutMs = payload.optLong("timeoutMs", 0)
        val expiresAt = if (responses.isNotEmpty() && timeoutMs > 0) {
            (postTime + timeoutMs).coerceIn(now + MIN_ANSWER_MS, now + MAX_ANSWER_MS)
        } else {
            now + DISPLAY_SECONDS * 1000L
        }
        // MyGate re-sending the visitor we're already answering keeps the answer's progress.
        val previous = MyGateAlerts.current.value
            ?.takeIf { id != null && it.id == id && responses.isNotEmpty() }
        val alert = MyGateAlert(
            id = id,
            type = payload.optString("event_type", MyGateNotificationListener.TYPE_GENERAL),
            title = payload.optStringOrNull("title") ?: "MyGate",
            text = payload.optStringOrNull("bigText") ?: payload.optStringOrNull("text").orEmpty(),
            visitor = payload.optJSONObject("visitor")?.let { v ->
                MyGateVisitor(
                    name = v.optStringOrNull("name"),
                    company = v.optStringOrNull("company"),
                    companyLogo = v.optStringOrNull("company_logo"),
                    photo = v.optStringOrNull("image")
                ).takeIf { it.name != null || it.company != null || it.photo != null }
            },
            responses = responses,
            postTime = postTime,
            shownAt = previous?.shownAt ?: now,
            expiresAt = expiresAt,
            answer = previous?.answer
        )
        val update = payload.optBoolean("update", false)
        DiskLog.i(TAG, "alert ${alert.type}${if (update) " (update)" else ""}: ${alert.title}")

        MyGateAlerts.show(alert)
        // An update is MyGate revising (or resolving) an alert it already sent: refresh the
        // card, but don't ring for the same visitor twice.
        if (!update) playChime()
        keepUpUntil(expiresAt)
    }

    private fun removed(payload: JSONObject) {
        val alert = matchingAnsweredCard(payload) ?: return
        when (payload.optString("reason")) {
            "responded" -> finish(DONE_LABELS[payload.optString("response")] ?: "Answered")
            "answered_elsewhere" -> finish("Answered on another device")
            else -> {
                DiskLog.i(TAG, "visitor ${alert.id} closed: ${payload.optString("reason")}")
                closeIn(0)
            }
        }
    }

    private fun responseResult(payload: JSONObject) {
        val alert = matchingAnsweredCard(payload) ?: return
        if (payload.optBoolean("ok", true)) {
            // notification_removed normally says the same; this covers it going missing.
            if (alert.answer is MyGateAnswer.Sending) {
                finish(DONE_LABELS[payload.optString("action")] ?: "Answered")
            }
        } else {
            val error = payload.optStringOrNull("error") ?: "MyGate didn't accept the answer"
            DiskLog.w(TAG, "answer for ${alert.id} failed: $error")
            setAnswer(MyGateAnswer.Failed(error))
        }
    }

    private fun respond(response: String) {
        val alert = MyGateAlerts.current.value ?: return
        val id = alert.id ?: return
        if (response !in alert.responses) return
        if (alert.answer is MyGateAnswer.Sending || alert.answer is MyGateAnswer.Done) return

        DiskLog.i(TAG, "answering visitor $id: $response")
        if (!publishResponse(id, response)) {
            setAnswer(MyGateAnswer.Failed("Not connected to MQTT"))
            return
        }
        setAnswer(MyGateAnswer.Sending(response))
        // Stay up for the reply even if MyGate's own deadline is about to pass.
        val replyBy = System.currentTimeMillis() + SEND_TIMEOUT_MS
        if (alert.expiresAt < replyBy + OUTCOME_SECONDS * 1000L) {
            MyGateAlerts.update { it.copy(expiresAt = replyBy + OUTCOME_SECONDS * 1000L) }
            keepUpUntil(replyBy + OUTCOME_SECONDS * 1000L)
        }
        sendTimeoutJob?.cancel()
        sendTimeoutJob = scope.launch {
            delay(SEND_TIMEOUT_MS)
            if (MyGateAlerts.current.value?.answer == MyGateAnswer.Sending(response)) {
                setAnswer(MyGateAnswer.Failed("No reply from Home Assistant"))
            }
        }
    }

    /** The card on screen, if [payload] is about it and it is one that can be answered. */
    private fun matchingAnsweredCard(payload: JSONObject): MyGateAlert? {
        val alert = MyGateAlerts.current.value ?: return null
        val id = payload.optStringOrNull("id") ?: return null
        return alert.takeIf { it.responses.isNotEmpty() && it.id == id }
    }

    private fun setAnswer(answer: MyGateAnswer) {
        if (answer !is MyGateAnswer.Sending) sendTimeoutJob?.cancel()
        MyGateAlerts.update { it.copy(answer = answer) }
    }

    /** Show the outcome briefly, then close the card. */
    private fun finish(message: String) {
        setAnswer(MyGateAnswer.Done(message))
        closeIn(OUTCOME_SECONDS * 1000L)
    }

    /** Wake the screen (if it was asleep, or we woke it) and close the card at [until]. */
    private fun keepUpUntil(until: Long) {
        val ms = (until - System.currentTimeMillis()).coerceAtLeast(0)
        if (!power.isInteractive || wokeScreen) {
            wakeFor(secondsFor(ms))
            wokeScreen = true
        }
        scheduleClear(ms)
    }

    /** Close the card sooner than planned, and bring the screen's sleep forward with it. */
    private fun closeIn(ms: Long) {
        if (wokeScreen) wakeFor(secondsFor(ms))
        scheduleClear(ms)
    }

    private fun scheduleClear(ms: Long) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(ms)
            MyGateAlerts.dismiss()
            sendTimeoutJob?.cancel()
            wokeScreen = false
        }
    }

    private fun secondsFor(ms: Long) = ((ms + 999) / 1000).toInt().coerceAtLeast(1)

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

        /** How long the outcome ("Approved") stays up before the card closes. */
        private const val OUTCOME_SECONDS = 4
        private const val SEND_TIMEOUT_MS = 15_000L
        private const val MIN_ANSWER_MS = 10_000L
        private const val MAX_ANSWER_MS = 5 * 60_000L

        val RESPONSE_LABELS = mapOf(
            "approve" to "Approve",
            "deny" to "Deny",
            "leave_at_gate" to "Leave at gate"
        )
        private val DONE_LABELS = mapOf(
            "approve" to "Approved",
            "deny" to "Denied",
            "leave_at_gate" to "Left at the gate"
        )
    }
}
