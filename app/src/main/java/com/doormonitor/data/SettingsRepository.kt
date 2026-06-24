package com.doormonitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Single source of truth for configuration, backed by Preferences DataStore.
 *
 * Exposes [settings] as a Flow of [AppSettings] and offers suspend setters. The repository
 * is intentionally stateless beyond the DataStore so it can be created freely wherever a
 * [Context] is available (service, ViewModel, receivers).
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toAppSettings() }

    private fun Preferences.toAppSettings(): AppSettings {
        val camerasJson = this[Keys.CAMERAS] ?: "[]"
        val cameras = runCatching {
            json.decodeFromString<List<CameraDef>>(camerasJson)
        }.getOrDefault(emptyList())

        return AppSettings(
            dashboardUrl = this[Keys.DASHBOARD_URL] ?: "",
            httpEnabled = this[Keys.HTTP_ENABLED] ?: true,
            httpPort = this[Keys.HTTP_PORT] ?: 2323,
            apiPassword = this[Keys.API_PASSWORD] ?: "",
            mqttEnabled = this[Keys.MQTT_ENABLED] ?: false,
            mqttHost = this[Keys.MQTT_HOST] ?: "",
            mqttPort = this[Keys.MQTT_PORT] ?: 1883,
            mqttUseTls = this[Keys.MQTT_TLS] ?: false,
            mqttUsername = this[Keys.MQTT_USER] ?: "",
            mqttPassword = this[Keys.MQTT_PASS] ?: "",
            deviceId = this[Keys.DEVICE_ID] ?: "doormonitor",
            haBaseUrl = this[Keys.HA_URL] ?: "",
            haLongLivedToken = this[Keys.HA_TOKEN] ?: "",
            screenTimeoutSeconds = this[Keys.SCREEN_TIMEOUT] ?: 60,
            defaultBrightness = this[Keys.DEFAULT_BRIGHTNESS] ?: 160,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: true,
            wakeOnMotion = this[Keys.WAKE_MOTION] ?: true,
            wakeOnDoorbell = this[Keys.WAKE_DOORBELL] ?: true,
            motionWakeSeconds = this[Keys.MOTION_WAKE_SECONDS] ?: 30,
            prewarmCameraId = this[Keys.PREWARM_CAMERA_ID] ?: "",
            lockTaskEnabled = this[Keys.LOCK_TASK] ?: false,
            batteryHighThreshold = this[Keys.BATT_HIGH] ?: 90,
            batteryWarnAfterHours = this[Keys.BATT_WARN_HOURS] ?: 24,
            cameras = cameras
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toAppSettings()
            val next = transform(current)
            prefs[Keys.DASHBOARD_URL] = next.dashboardUrl
            prefs[Keys.HTTP_ENABLED] = next.httpEnabled
            prefs[Keys.HTTP_PORT] = next.httpPort
            prefs[Keys.API_PASSWORD] = next.apiPassword
            prefs[Keys.MQTT_ENABLED] = next.mqttEnabled
            prefs[Keys.MQTT_HOST] = next.mqttHost
            prefs[Keys.MQTT_PORT] = next.mqttPort
            prefs[Keys.MQTT_TLS] = next.mqttUseTls
            prefs[Keys.MQTT_USER] = next.mqttUsername
            prefs[Keys.MQTT_PASS] = next.mqttPassword
            prefs[Keys.DEVICE_ID] = next.deviceId
            prefs[Keys.HA_URL] = next.haBaseUrl
            prefs[Keys.HA_TOKEN] = next.haLongLivedToken
            prefs[Keys.SCREEN_TIMEOUT] = next.screenTimeoutSeconds
            prefs[Keys.DEFAULT_BRIGHTNESS] = next.defaultBrightness
            prefs[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[Keys.WAKE_MOTION] = next.wakeOnMotion
            prefs[Keys.WAKE_DOORBELL] = next.wakeOnDoorbell
            prefs[Keys.MOTION_WAKE_SECONDS] = next.motionWakeSeconds
            prefs[Keys.PREWARM_CAMERA_ID] = next.prewarmCameraId
            prefs[Keys.LOCK_TASK] = next.lockTaskEnabled
            prefs[Keys.BATT_HIGH] = next.batteryHighThreshold
            prefs[Keys.BATT_WARN_HOURS] = next.batteryWarnAfterHours
            prefs[Keys.CAMERAS] = json.encodeToString(next.cameras)
        }
    }

    private object Keys {
        val DASHBOARD_URL = stringPreferencesKey("dashboard_url")
        val HTTP_ENABLED = booleanPreferencesKey("http_enabled")
        val HTTP_PORT = intPreferencesKey("http_port")
        val API_PASSWORD = stringPreferencesKey("api_password")
        val MQTT_ENABLED = booleanPreferencesKey("mqtt_enabled")
        val MQTT_HOST = stringPreferencesKey("mqtt_host")
        val MQTT_PORT = intPreferencesKey("mqtt_port")
        val MQTT_TLS = booleanPreferencesKey("mqtt_tls")
        val MQTT_USER = stringPreferencesKey("mqtt_user")
        val MQTT_PASS = stringPreferencesKey("mqtt_pass")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val HA_URL = stringPreferencesKey("ha_url")
        val HA_TOKEN = stringPreferencesKey("ha_token")
        val SCREEN_TIMEOUT = intPreferencesKey("screen_timeout")
        val DEFAULT_BRIGHTNESS = intPreferencesKey("default_brightness")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val WAKE_MOTION = booleanPreferencesKey("wake_motion")
        val WAKE_DOORBELL = booleanPreferencesKey("wake_doorbell")
        val MOTION_WAKE_SECONDS = intPreferencesKey("motion_wake_seconds")
        val PREWARM_CAMERA_ID = stringPreferencesKey("prewarm_camera_id")
        val LOCK_TASK = booleanPreferencesKey("lock_task")
        val BATT_HIGH = intPreferencesKey("batt_high")
        val BATT_WARN_HOURS = intPreferencesKey("batt_warn_hours")
        val CAMERAS = stringPreferencesKey("cameras_json")
    }
}
