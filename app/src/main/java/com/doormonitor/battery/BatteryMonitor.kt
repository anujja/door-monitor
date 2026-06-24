package com.doormonitor.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Immutable battery snapshot exposed to the rest of the app. */
data class BatteryState(
    val level: Int = -1,
    val charging: Boolean = false,
    val plugged: Boolean = false,
    val health: Int = BatteryManager.BATTERY_HEALTH_UNKNOWN,
    val temperatureC: Float = 0f,
    val voltage: Int = 0,
    val highWarningActive: Boolean = false
) {
    val healthLabel: String
        get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
}

/**
 * Monitors battery via the sticky ACTION_BATTERY_CHANGED broadcast.
 *
 * For a permanently-mounted, always-plugged tablet the main wear risk is sitting at a high
 * state-of-charge for long periods. Android cannot cap charging without root, so this class
 * *detects* the condition: when the level stays above [highThreshold] for longer than
 * [warnAfterMillis] it raises [BatteryState.highWarningActive] (and notifies via [onWarning]),
 * which Home Assistant automations can act on (e.g. switch a smart plug off).
 */
class BatteryMonitor(
    private val appContext: Context,
    @Volatile var highThreshold: Int = 90,
    @Volatile var warnAfterMillis: Long = 24 * 60 * 60 * 1000L,
    private val onWarning: (Boolean) -> Unit = {}
) {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state
    val current: BatteryState get() = _state.value

    private var aboveThresholdSince: Long = 0L
    private var warningRaised = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        val sticky = appContext.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        sticky?.let { handle(it) }
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun handle(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val health = intent.getIntExtra(
            BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN
        )
        val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        evaluateHighSoc(pct)

        _state.value = BatteryState(
            level = pct,
            charging = charging,
            plugged = plugged != 0,
            health = health,
            temperatureC = tempTenthsC / 10f,
            voltage = voltage,
            highWarningActive = warningRaised
        )
    }

    private fun evaluateHighSoc(pct: Int) {
        val now = System.currentTimeMillis()
        if (pct in highThreshold..100) {
            if (aboveThresholdSince == 0L) aboveThresholdSince = now
            val elapsed = now - aboveThresholdSince
            if (!warningRaised && elapsed >= warnAfterMillis) {
                warningRaised = true
                onWarning(true)
            }
        } else {
            aboveThresholdSince = 0L
            if (warningRaised) {
                warningRaised = false
                onWarning(false)
            }
        }
    }
}
