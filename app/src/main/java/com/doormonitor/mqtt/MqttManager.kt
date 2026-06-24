package com.doormonitor.mqtt

import android.util.Log
import com.doormonitor.core.CommandHandler
import com.doormonitor.data.AppSettings
import com.hivemq.client.mqtt.MqttClient
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
 *
 * Also publishes Home Assistant MQTT Discovery configs so a switch/sensors appear
 * automatically (see publishDiscovery).
 */
class MqttManager(
    private val handler: CommandHandler
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
            .addConnectedListener { Log.i(TAG, "MQTT connected"); onConnected(settings) }
            .addDisconnectedListener {
                connected = false
                Log.w(TAG, "MQTT disconnected: ${it.cause.message}")
            }

        if (settings.mqttUseTls) builder.sslWithDefaultConfig()

        val async = builder.buildAsync()
        client = async

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
        subscribeCommands(c)
        publishDiscovery(settings)
        publishState()
    }

    private fun subscribeCommands(c: Mqtt3AsyncClient) {
        c.subscribeWith()
            .topicFilter("$base/command/#")
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish -> onCommand(publish) }
            .send()
            .whenComplete { _, t -> if (t != null) Log.e(TAG, "subscribe failed", t) }
    }

    private fun onCommand(publish: Mqtt3Publish) {
        val topic = publish.topic.toString()
        val payload = publish.payloadAsBytes.toString(StandardCharsets.UTF_8).trim()
        val cmd = topic.removePrefix("$base/command/")
        Log.i(TAG, "MQTT command: $cmd = '$payload'")
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
