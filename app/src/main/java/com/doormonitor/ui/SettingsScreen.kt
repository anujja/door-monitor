package com.doormonitor.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.doormonitor.data.AppSettings
import com.doormonitor.data.CameraDef
import com.doormonitor.data.CameraType
import com.doormonitor.kiosk.DeviceAdminReceiver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onAddCamera: (CameraDef) -> Unit,
    onRemoveCamera: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Door Monitor Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Raise the scroll viewport above the soft keyboard so focused fields
                // (especially the "Add camera" form at the bottom) stay visible and can
                // auto-scroll into view. Required because the app runs edge-to-edge and
                // therefore manages window insets itself.
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { SectionTitle("Dashboard") }
            item {
                TextSetting("Dashboard URL", settings.dashboardUrl) { v ->
                    onChange { it.copy(dashboardUrl = v) }
                }
            }

            item { SectionTitle("Local HTTP API") }
            item {
                ToggleSetting("Enable HTTP API", settings.httpEnabled) { v ->
                    onChange { it.copy(httpEnabled = v) }
                }
            }
            item {
                NumberSetting("HTTP Port", settings.httpPort) { v ->
                    onChange { it.copy(httpPort = v) }
                }
            }
            item {
                TextSetting("API Password (blank = none)", settings.apiPassword, isPassword = true) { v ->
                    onChange { it.copy(apiPassword = v) }
                }
            }

            item { SectionTitle("Home Assistant") }
            item {
                TextSetting("HA Base URL", settings.haBaseUrl) { v ->
                    onChange { it.copy(haBaseUrl = v) }
                }
            }
            item {
                TextSetting("Long-Lived Access Token", settings.haLongLivedToken, isPassword = true) { v ->
                    onChange { it.copy(haLongLivedToken = v) }
                }
            }

            item { SectionTitle("MQTT") }
            item {
                ToggleSetting("Enable MQTT", settings.mqttEnabled) { v ->
                    onChange { it.copy(mqttEnabled = v) }
                }
            }
            item {
                TextSetting("MQTT Host", settings.mqttHost) { v ->
                    onChange { it.copy(mqttHost = v) }
                }
            }
            item {
                NumberSetting("MQTT Port", settings.mqttPort) { v ->
                    onChange { it.copy(mqttPort = v) }
                }
            }
            item {
                ToggleSetting("Use TLS", settings.mqttUseTls) { v ->
                    onChange { it.copy(mqttUseTls = v) }
                }
            }
            item {
                TextSetting("MQTT Username", settings.mqttUsername) { v ->
                    onChange { it.copy(mqttUsername = v) }
                }
            }
            item {
                TextSetting("MQTT Password", settings.mqttPassword, isPassword = true) { v ->
                    onChange { it.copy(mqttPassword = v) }
                }
            }
            item {
                TextSetting("Device ID (MQTT base topic)", settings.deviceId) { v ->
                    onChange { it.copy(deviceId = v) }
                }
            }

            item { SectionTitle("Screen") }
            item {
                ToggleSetting("Keep screen on permanently", settings.keepScreenOn) { v ->
                    onChange { it.copy(keepScreenOn = v) }
                }
            }
            item {
                NumberSetting("Screen timeout (seconds, 0 = never)", settings.screenTimeoutSeconds) { v ->
                    onChange { it.copy(screenTimeoutSeconds = v) }
                }
            }
            item {
                NumberSetting("Default brightness (0-255)", settings.defaultBrightness) { v ->
                    onChange { it.copy(defaultBrightness = v.coerceIn(0, 255)) }
                }
            }
            item { DeviceAdminSetting() }

            item { SectionTitle("Motion / Wake") }
            item {
                ToggleSetting("Wake on motion", settings.wakeOnMotion) { v ->
                    onChange { it.copy(wakeOnMotion = v) }
                }
            }
            item {
                ToggleSetting("Wake on doorbell", settings.wakeOnDoorbell) { v ->
                    onChange { it.copy(wakeOnDoorbell = v) }
                }
            }
            item {
                NumberSetting("Motion wake duration (seconds)", settings.motionWakeSeconds) { v ->
                    onChange { it.copy(motionWakeSeconds = v) }
                }
            }

            item { SectionTitle("Kiosk") }
            item {
                ToggleSetting("Lock Task Mode (requires device owner)", settings.lockTaskEnabled) { v ->
                    onChange { it.copy(lockTaskEnabled = v) }
                }
            }

            item { SectionTitle("Battery Protection") }
            item {
                NumberSetting("High battery threshold (%)", settings.batteryHighThreshold) { v ->
                    onChange { it.copy(batteryHighThreshold = v.coerceIn(50, 100)) }
                }
            }
            item {
                NumberSetting("Warn after hours above threshold", settings.batteryWarnAfterHours) { v ->
                    onChange { it.copy(batteryWarnAfterHours = v) }
                }
            }

            item { SectionTitle("Cameras") }
            item {
                TextSetting(
                    "Pre-warm camera id (instant WebRTC, blank = off)",
                    settings.prewarmCameraId
                ) { v -> onChange { it.copy(prewarmCameraId = v.trim()) } }
            }
            items(settings.cameras) { cam ->
                CameraRow(cam, onRemove = { onRemoveCamera(cam.id) })
            }
            item { AddCameraForm(onAddCamera) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    // The displayed text is driven by LOCAL state so each keystroke is reflected
    // synchronously. Persistence (DataStore) is async; if we bound the field directly to the
    // persisted value, recomposition would briefly show the stale value and the cursor would
    // jump back / drop characters. We only adopt the external value when this field is not
    // focused, so in-progress typing is never reverted by a late persistence emission.
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    if (!focused && text != value) text = value

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    )
}

@Composable
private fun NumberSetting(label: String, value: Int, onValueChange: (Int) -> Unit) {
    // Same focus-aware local-state pattern as TextSetting (see note there).
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    if (!focused && text.toIntOrNull() != value) text = value.toString()

    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val filtered = t.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    )
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * One-tap control for the device-admin grant that powers true screen-off
 * (DevicePolicyManager.lockNow). Shows live status and offers Enable/Disable. Status is
 * re-read whenever the screen resumes (e.g. after the system admin prompt closes).
 */
@Composable
private fun DeviceAdminSetting() {
    val context = LocalContext.current
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val admin = remember { ComponentName(context, DeviceAdminReceiver::class.java) }

    var active by remember { mutableStateOf(dpm.isAdminActive(admin)) }
    val isDeviceOwner = remember { dpm.isDeviceOwnerApp(context.packageName) }

    // The admin prompt is a separate Activity; refresh status on its result and on resume.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { active = dpm.isAdminActive(admin) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) active = dpm.isAdminActive(admin)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50)
                    )
                }
                Text(
                    text = when {
                        isDeviceOwner -> "Screen control active (device owner)"
                        active -> "Screen control active (device admin)"
                        else -> "Screen-off needs device admin"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = if (active) 8.dp else 0.dp)
                )
            }
            Text(
                text = if (active) {
                    "The app can turn the screen off on command (screenOff / sleep). " +
                        "Tip: set the tablet's screen lock to None or Swipe so waking returns " +
                        "straight to the dashboard."
                } else {
                    "Without this grant, \"screen off\" can only dim the backlight. Enable device " +
                        "admin so commands can actually power the panel off (uses lockNow)."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!active) {
                    Button(onClick = {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Door Monitor uses this to turn the screen off on command from " +
                                    "Home Assistant (MQTT / HTTP)."
                            )
                        }
                        launcher.launch(intent)
                    }) { Text("Enable screen control") }
                } else if (!isDeviceOwner) {
                    // Device owner can't be removed here; only plain admins can self-disable.
                    TextButton(onClick = {
                        runCatching { dpm.removeActiveAdmin(admin) }
                        active = dpm.isAdminActive(admin)
                    }) { Text("Disable") }
                }
            }
        }
    }
}

@Composable
private fun CameraRow(cam: CameraDef, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(cam.name, style = MaterialTheme.typography.titleSmall)
                Text("${cam.id} · ${cam.type}", style = MaterialTheme.typography.bodySmall)
                Text(cam.url, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun AddCameraForm(onAdd: (CameraDef) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CameraType.RTSP) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add camera", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(id, { id = it.lowercase().replace(" ", "_") }, label = { Text("ID (e.g. front_door)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("Stream URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraType.entries.forEach { t ->
                    val selected = t == type
                    Button(
                        onClick = { type = t },
                        colors = if (selected) androidx.compose.material3.ButtonDefaults.buttonColors()
                        else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    ) { Text(t.name) }
                }
            }
            Button(
                onClick = {
                    if (id.isNotBlank() && url.isNotBlank()) {
                        onAdd(CameraDef(id, name.ifBlank { id }, type, url))
                        id = ""; name = ""; url = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add") }
        }
    }
}
