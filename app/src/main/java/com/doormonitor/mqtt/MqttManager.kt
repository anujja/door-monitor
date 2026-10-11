package com.doormonitor.mqtt

import android.util.Log
import com.doormonitor.core.CommandHandler
import com.doormonitor.core.DiskLog
import com.doormonitor.data.AppSettings
import com.doormonitor.mygate.MyGateNotificationListener
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

/**
 * MQTT integration using the HiveMQ client (MQTT 3.1.1, Mosquitto-compatible).
 *
 * Topic layout (base = settings.deviceId, default `doormonitor`):
 *   <base>/availability                 online | offline  (retained, LWT)
 *   <base>/state                        retained JSON status snapshot
 *   <base>/command/screen               ON | OFF
 *   <base>/command/brightness           0..255
 *   <base>/command/wake                 (optional seconds payload)
 *   <base>/command/sleep
 *   <base>/command/reload
 *   <base>/command/camera               <cameraId>
 *   <base>/command/motion               any payload -> motion wake
 *   <base>/command/doorbell             any payload -> doorbell wake
 *   <base>/mygate/notification          JSON, a MyGate notification was posted/updated (we
 *                                       also subscribe to this, to show it on our own screen)
 *   <base>/mygate/notification_removed  JSON, that notification went away (approved, timed out…)
 *
 * Also publishes Home Assistant MQTT Discovery configs so a switch/sensors appear
 * automatically (see publishDiscovery).
 */
class MqttManager(
    private val handler: CommandHandler,
    private val onMyGateNotification: (JSONObject) -> Unit = {}
) {
    private var client: Mqtt3AsyncClient? = null
    private var base: String = "doormonitor"

    @Volatile
    var connected: Boolean = false
        private set

    fun connect(settings: AppSettings) {
        disconnect() // ensure clean restart on settings change
        if (!settings.mqttEnabled || settings.mqttHost.isBlank()) return

        base = settings.deviceId.ifBlank { "doormonitor" }
        val availabilityTopic = "$base/availability"

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("${base}_${UUID.randomUUID().toString().take(8)}")
            .serverHost(settings.mqttHost)
            .serverPort(settings.mqttPort)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                DiskLog.i(TAG, "MQTT connected to ${settings.mqttHost}:${settings.mqttPort}")
                onConnected(settings)
            }
            .addDisconnectedListener {
                connected = false
                DiskLog.w(TAG, "MQTT disconnected: ${it.cause.message} (source=${it.source})")
            }

        if (settings.mqttUseTls) builder.sslWithDefaultConfig()

        val async = builder.buildAsync()
        client = async

        // Deliver commands through ONE global consumer, registered once per client.
        //
        // The obvious alternative — `.callback {}` on the subscription itself — silently
        // duplicates work: `automaticReconnectWithDefaultConfig` re-runs the connect listener on
        // every reconnect, `cleanSession` forces a re-subscribe each time, and HiveMQ *keeps every
        // per-subscription callback ever registered*. Each reconnect therefore added another
        // handler for the same topic filter. A tablet up for 26 days had accumulated 15 of them
        // and was running every single command 15 times (verified: one open socket, fifteen
        // `MQTT command: screen = 'OFF'` log lines within 44ms).
        async.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish -> onMessage(publish) }

        val connectBuilder = async.connectWith()
            .cleanSession(true)
            .keepAlive(30)

        if (settings.mqttUsername.isNotBlank()) {
            connectBuilder.simpleAuth()
                .username(settings.mqttUsername)
                .password(settings.mqttPassword.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
        }

        connectBuilder.willPublish()
            .topic(availabilityTopic)
            .payload("offline".toByteArray(StandardCharsets.UTF_8))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) Log.e(TAG, "MQTT connect failed", throwable)
            }
    }

    private fun onConnected(settings: AppSettings) {
        connected = true
        val c = client ?: return
        publish("$base/availability", "online", retain = true)
        subscribe(c)
        publishDiscovery(settings)
        publishState()
    }

    /**
     * (Re-)establishes the broker-side subscriptions. Must run on every connect because the
     * session is clean, but deliberately registers no callback — [connect] wires up a single
     * global consumer instead, so reconnects cannot stack up duplicate handlers.
     */
    private fun subscribe(c: Mqtt3AsyncClient) {
        for (filter in listOf("$base/command/#", "$base/mygate/notification")) {
            c.subscribeWith()
                .topicFilter(filter)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .whenComplete { _, t -> if (t != null) Log.e(TAG, "subscribe $filter failed", t) }
        }
    }

    private fun onMessage(publish: Mqtt3Publish) {
        val topic = publish.topic.toString()
        val payload = publish.payloadAsBytes.toString(StandardCharsets.UTF_8).trim()
        if (topic == "$base/mygate/notification") {
            runCatching { JSONObject(payload) }
                .onSuccess(onMyGateNotification)
                .onFailure { DiskLog.w(TAG, "ignoring malformed MyGate payload: ${it.message}") }
            return
        }
        val cmd = topic.removePrefix("$base/command/")
        DiskLog.i(TAG, "MQTT command: $cmd = '$payload'")
        when (cmd) {
            "screen" -> if (payload.equals("OFF", true)) handler.screenOff() else handler.screenOn()
            "brightness" -> payload.toIntOrNull()?.let { handler.setBrightness(it) }
            "wake" -> payload.toIntOrNull()?.let { handler.wakeFor(it) } ?: handler.wake()
            "sleep" -> handler.screenOff()
            "reload" -> handler.reloadDashboard()
            "camera" -> if (payload.isNotEmpty()) handler.showCamera(payload)
            "motion" -> handler.motionWake()
            "doorbell" -> handler.doorbellWake()
            else -> Log.w(TAG, "unknown MQTT command: $cmd")
        }
        // Reflect any state change back out.
        publishState()
    }

    /** Publish the current status snapshot (retained) for HA sensors. */
    fun publishState() {
        if (!connected) return
        publish("$base/state", handler.statusJson().toString(), retain = true)
    }

    fun publishHighBatteryWarning(active: Boolean) {
        publish("$base/battery_warning", if (active) "ON" else "OFF", retain = true)
    }

    /** Not retained: a visitor alert replayed to a late subscriber would be stale. */
    fun publishMyGateNotification(payload: JSONObject) {
        publish("$base/mygate/notification", payload.toString())
    }

    fun publishMyGateNotificationRemoved(payload: JSONObject) {
        publish("$base/mygate/notification_removed", payload.toString())
    }

    private fun publish(topic: String, payload: String, retain: Boolean = false) {
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.retain(retain)
            ?.send()
    }

    /**
     * Publish Home Assistant MQTT Discovery so entities appear with no manual YAML:
     *  - a switch for the screen
     *  - sensors for battery level / health / state
     *  - a binary_sensor for the high-battery warning
     *  - an event entity that fires for each MyGate notification
     */
    private fun publishDiscovery(settings: AppSettings) {
        val nodeId = base
        val device = JSONObject().apply {
            put("identifiers", org.json.JSONArray(listOf(nodeId)))
            put("name", "Door Monitor ($nodeId)")
            put("manufacturer", "Door Monitor")
            put("model", "Wall Tablet")
        }
        val availability = JSONObject().put("topic", "$base/availability")

        // Screen switch
        publishDiscoveryConfig(
            component = "switch", objectId = "${nodeId}_screen",
            config = JSONObject().apply {
                put("name", "Screen")
                put("unique_id", "${nodeId}_screen")
                put("command_topic", "$base/command/screen")
                put("state_topic", "$base/state")
                put("value_template", "{{ 'ON' if value_json.screenOn else 'OFF' }}")
                put("payload_on", "ON")
                put("payload_off", "OFF")
                put("icon", "mdi:tablet-dashboard")
                put("availability", org.json.JSONArray(listOf(availability)))
                put("device", device)
            }
        )
        // Brightness number
        publishDiscoveryConfig(
            component = "number", objectId = "${nodeId}_brightness",
            config = JSONObject().apply {
                put("name", "Brightness")
                put("unique_id", "${nodeId}_brightness")
                put("command_topic", "$base/command/brightness")
                put("min", 0); put("max", 255); put("step", 1)
                put("icon", "mdi:brightness-6")
                put("availability", org.json.JSONArray(listOf(availability)))
                put("device", device)
            }
        )
        // Battery level sensor
        publishDiscoveryConfig(
            component = "sensor", objectId = "${nodeId}_battery",
            config = JSONObject().apply {
                put("name", "Battery")
                put("unique_id", "${nodeId}_battery")
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.batteryLevel }}")
                put("unit_of_measurement", "%")
                put("device_class", "battery")
                put("availability", org.json.JSONArray(listOf(availability)))
                put("device", device)
            }
        )
        // High-battery warning binary sensor
        publishDiscoveryConfig(
            component = "binary_sensor", objectId = "${nodeId}_batt_warn",
            config = JSONObject().apply {
                put("name", "Battery High Warning")
                put("unique_id", "${nodeId}_batt_warn")
                put("state_topic", "$base/battery_warning")
                put("payload_on", "ON"); put("payload_off", "OFF")
                put("device_class", "problem")
                put("availability", org.json.JSONArray(listOf(availability)))
                put("device", device)
            }
        )
        // MyGate notifications. HA reads `event_type` from the payload and exposes the other
        // fields (title, text, actions…) as attributes of the fired event.
        publishDiscoveryConfig(
            component = "event", objectId = "${nodeId}_mygate",
            config = JSONObject().apply {
                put("name", "MyGate")
                put("unique_id", "${nodeId}_mygate")
                put("state_topic", "$base/mygate/notification")
                put("event_types", org.json.JSONArray(MyGateNotificationListener.EVENT_TYPES))
                put("icon", "mdi:gate")
                put("availability", org.json.JSONArray(listOf(availability)))
                put("device", device)
            }
        )
    }

    private fun publishDiscoveryConfig(component: String, objectId: String, config: JSONObject) {
        publish("homeassistant/$component/$objectId/config", config.toString(), retain = true)
    }

    fun disconnect() {
        runCatching {
            if (connected) publish("$base/availability", "offline", retain = true)
            client?.disconnect()
        }
        connected = false
        client = null
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}
