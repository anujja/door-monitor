# MQTT Topics

Base topic = the **Device ID** from Settings (default `doormonitor`). Replace `doormonitor`
below with yours. The app speaks MQTT 3.1.1 and is Mosquitto-compatible.

## Command topics (HA → tablet)

| Topic | Payload | Action |
|-------|---------|--------|
| `doormonitor/command/screen` | `ON` / `OFF` | Screen on / off |
| `doormonitor/command/brightness` | `0`–`255` | Set brightness |
| `doormonitor/command/wake` | *(empty)* or seconds | Permanent wake / temporary wake for N s |
| `doormonitor/command/sleep` | *(any)* | Screen off |
| `doormonitor/command/reload` | *(any)* | Reload dashboard |
| `doormonitor/command/camera` | `<camera_id>` | Open camera full-screen |
| `doormonitor/command/motion` | *(any)* | Motion wake (honors `wakeOnMotion`) |
| `doormonitor/command/doorbell` | *(any)* | Doorbell wake (+ door camera if defined) |

## State / status topics (tablet → HA)

| Topic | Retained | Payload |
|-------|----------|---------|
| `doormonitor/availability` | yes | `online` / `offline` (LWT) |
| `doormonitor/state` | yes | JSON status snapshot (same shape as `GET /status`) |
| `doormonitor/battery_warning` | yes | `ON` / `OFF` (high-battery warning) |

`doormonitor/state` example:
```json
{
  "appId": "com.doormonitor",
  "deviceId": "doormonitor",
  "model": "Lenovo TB330FU",
  "androidVersion": "14",
  "screenOn": true,
  "brightness": 160,
  "dashboardUrl": "https://homeassistant.local:8123/lovelace/wall",
  "batteryLevel": 92,
  "batteryCharging": true,
  "batteryHealth": "good",
  "batteryTemperatureC": 29.5,
  "plugged": true,
  "highBatteryWarning": false,
  "mqttEnabled": true,
  "cameras": "front_door,garage"
}
```

## Discovery
On connect the app publishes retained HA MQTT-discovery configs under
`homeassistant/<component>/<deviceId>_<object>/config`, creating:
- `switch.doormonitor_screen`
- `number.doormonitor_brightness`
- `sensor.doormonitor_battery`
- `binary_sensor.doormonitor_batt_warn`

## Try it with mosquitto
```bash
# Turn the screen on
mosquitto_pub -h 192.168.1.10 -t doormonitor/command/screen -m ON

# Dim to 20
mosquitto_pub -h 192.168.1.10 -t doormonitor/command/brightness -m 20

# Show a camera
mosquitto_pub -h 192.168.1.10 -t doormonitor/command/camera -m front_door

# Watch status
mosquitto_sub -h 192.168.1.10 -t 'doormonitor/#' -v
```
