package com.doormonitor.mygate

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.doormonitor.core.DiskLog
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Relays the MyGate app's notifications (visitor at the gate, deliveries, emergencies…) so they
 * can be published over MQTT for anything else to act on.
 *
 * We read the notifications MyGate already posts on this tablet rather than talking to MyGate's
 * private API: no credentials to extract, no certificate pinning to fight, and it keeps working
 * as long as MyGate is signed in here. The catch is that we only see what MyGate puts in the
 * notification itself (title, text, action labels) — not the underlying visitor record.
 *
 * What MyGate posts (from decompiling com.mygate.user 7.39.0):
 *  - Gate approval requests ("X is at the gate", also kid-exit and smart-lock alerts) go to
 *    channel `mygate_approval_notification_channel<n>` / `mygate_approval_new_channel<n>`, in group
 *    `approval_notification_group`, category `call`, with Deny / Leave@Gate / Approve (or
 *    "View details") actions, a full-screen intent, and a `timeoutAfter` of `keep_alive` seconds.
 *    The notification id is MyGate's `request_id.hashCode()`.
 *  - Emergencies use `mygate_emergency_channel_new_3`; vehicle logs use
 *    `mygate_vehicle_notification_channel`; everything else uses `mygate_default_channel` or
 *    `mygate_silent_channel`.
 *  - While it processes an Approve/Deny tap, MyGate runs a foreground service whose notification
 *    repeats the approval title. That is filtered out here, as are group summaries.
 *
 * Requires Notification access (Settings → Notifications → Device & app notifications), or:
 *   adb shell cmd notification allow_listener <applicationId>/com.doormonitor.mygate.MyGateNotificationListener
 */
class MyGateNotificationListener : NotificationListenerService() {

    /** key → content signature of what we last published, to drop no-op re-posts. */
    private val published = HashMap<String, String>()

    override fun onListenerConnected() {
        DiskLog.i(TAG, "notification listener connected")
        // Seed with whatever MyGate already shows so a later re-post of it reads as an update
        // instead of a fresh visitor. These are not published: they predate the connection.
        runCatching {
            activeNotifications.orEmpty().filter(::isRelevant).forEach {
                published[it.key] = signature(it.notification)
            }
        }
    }

    override fun onListenerDisconnected() {
        DiskLog.w(TAG, "notification listener disconnected; requesting rebind")
        requestRebind(ComponentName(this, MyGateNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isRelevant(sbn)) return
        val sig = signature(sbn.notification)
        val previous = published.put(sbn.key, sig)
        if (previous == sig) return // re-post with identical content

        val payload = describe(sbn).put("update", previous != null)
        DiskLog.i(TAG, "posted ${payload.optString("event_type")}: ${payload.optString("title")}")
        MyGateBus.emit(MyGateEvent.Posted(payload))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!isRelevant(sbn)) return
        published.remove(sbn.key)

        val payload = describe(sbn).put("reason", reasonName(reason))
        DiskLog.i(TAG, "removed ${payload.optString("event_type")} (${payload.optString("reason")})")
        MyGateBus.emit(MyGateEvent.Removed(payload))
    }

    private fun isRelevant(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MYGATE_PACKAGE) return false
        val flags = sbn.notification.flags
        return flags and Notification.FLAG_FOREGROUND_SERVICE == 0 &&
            flags and Notification.FLAG_GROUP_SUMMARY == 0
    }

    private fun signature(n: Notification): String {
        val x = n.extras
        return listOf(
            x.getCharSequence(Notification.EXTRA_TITLE),
            x.getCharSequence(Notification.EXTRA_TEXT),
            x.getCharSequence(Notification.EXTRA_BIG_TEXT),
            n.actions?.joinToString("|") { it.title }
        ).joinToString("\u0000")
    }

    private fun describe(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val x = n.extras
        fun text(key: String) = x.getCharSequence(key)?.toString()?.trim() ?: JSONObject.NULL

        return JSONObject().apply {
            put("event_type", classify(n))
            put("key", sbn.key)
            put("id", sbn.id)
            put("postTime", sbn.postTime)
            put("time", Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault())
                .toOffsetDateTime().toString())
            put("title", text(Notification.EXTRA_TITLE))
            put("text", text(Notification.EXTRA_TEXT))
            put("bigText", text(Notification.EXTRA_BIG_TEXT))
            put("subText", text(Notification.EXTRA_SUB_TEXT))
            put("actions", JSONArray(n.actions.orEmpty().map { it.title.toString() }))
            put("channel", n.channelId ?: JSONObject.NULL)
            put("category", n.category ?: JSONObject.NULL)
            put("timeoutMs", n.timeoutAfter)
        }
    }

    /** Coarse type for subscribers (and the HA event entity), keyed off MyGate's channels. */
    private fun classify(n: Notification): String {
        val channel = n.channelId.orEmpty()
        return when {
            channel.startsWith("mygate_approval") || n.group == "approval_notification_group" ->
                TYPE_APPROVAL
            channel.startsWith("mygate_emergency") -> TYPE_EMERGENCY
            channel.startsWith("mygate_vehicle") -> TYPE_VEHICLE
            else -> TYPE_GENERAL
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        REASON_CLICK -> "click"
        REASON_CANCEL, REASON_CANCEL_ALL -> "dismissed"
        REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> "app_cancel"
        REASON_TIMEOUT -> "timeout"
        REASON_SNOOZED -> "snoozed"
        // MyGate toggling one of its own components; seen on a vehicle check-in notification.
        REASON_PACKAGE_CHANGED -> "package_changed"
        else -> "other_$reason"
    }

    companion object {
        private const val TAG = "MyGate"
        const val MYGATE_PACKAGE = "com.mygate.user"

        const val TYPE_APPROVAL = "approval"
        const val TYPE_EMERGENCY = "emergency"
        const val TYPE_VEHICLE = "vehicle"
        const val TYPE_GENERAL = "general"
        val EVENT_TYPES = listOf(TYPE_APPROVAL, TYPE_EMERGENCY, TYPE_VEHICLE, TYPE_GENERAL)
    }
}

sealed interface MyGateEvent {
    val payload: JSONObject

    data class Posted(override val payload: JSONObject) : MyGateEvent
    data class Removed(override val payload: JSONObject) : MyGateEvent
}

/**
 * Hands notification events from the system-bound listener to [KioskForegroundService], which
 * owns the MQTT connection. Events with no collector are dropped — a visitor alert that can't go
 * out now is not worth replaying later.
 */
object MyGateBus {
    private val _events = MutableSharedFlow<MyGateEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<MyGateEvent> = _events.asSharedFlow()

    fun emit(event: MyGateEvent) {
        _events.tryEmit(event)
    }
}
