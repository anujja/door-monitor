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
| `doormonitor/mygate/notification` | no | JSON — a MyGate notification was posted or updated |
| `doormonitor/mygate/notification_removed` | no | JSON — that notification went away |

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

## MyGate notifications

When MyGate is installed and signed in on the tablet, and Door Monitor has **Notification
access** (Settings card under MQTT, or
`adb shell cmd notification allow_listener com.doormonitor/com.doormonitor.mygate.MyGateNotificationListener`),
every notification MyGate shows is published as JSON for anything to subscribe to.

The tablet is one of those subscribers. On each message it plays a chime and shows the
notification as a card for 15 s. If the screen was off, it wakes it and switches it back off
after the 15 s; if someone is already using the tablet, the screen is left on. `update: true`
messages refresh the card without ringing again. Anything else that publishes this JSON shape
to the topic (at minimum `title`) gets the same treatment.

`doormonitor/mygate/notification` example (a guest at the gate):
```json
{
  "event_type": "approval",
  "key": "0|com.mygate.user|-1520851200|null|10254",
  "id": -1520851200,
  "postTime": 1791712345678,
  "time": "2026-10-11T16:05:45.678+05:30",
  "title": "Ramesh (Guest) is at the gate",
  "text": "Tap to allow or deny entry",
  "bigText": "Tap to allow or deny entry",
  "subText": null,
  "actions": ["Deny", "Approve"],
  "channel": "mygate_approval_notification_channel1",
  "category": "call",
  "timeoutMs": 60000,
  "update": false
}
```
*(Field values are illustrative. Title and text are whatever MyGate's server sends.)*

| Field | Meaning |
|-------|---------|
| `event_type` | `approval` (someone at the gate needs Allow/Deny, plus kid-exit and smart-lock alerts), `emergency`, `vehicle`, or `general` (entries, deliveries left at gate, notices…) |
| `key` | Stable per notification. Pair a `notification` with its `notification_removed`. |
| `update` | `true` when MyGate changed a notification it already showed. Ignore it to react once per visitor. |
| `actions` | Labels of MyGate's buttons, e.g. `Deny`, `Leave@Gate`, `Approve`, `View details` |
| `timeoutMs` | How long MyGate keeps an approval request up (`0` = no timeout) |

`doormonitor/mygate/notification_removed` has the same fields plus `reason`: `app_cancel`
(answered on another phone or by the guard), `timeout`, `click`, `dismissed`,
`package_changed`, or `other_<n>`.

Not retained: a subscriber that connects later does not get stale visitor alerts.

## Discovery
On connect the app publishes retained HA MQTT-discovery configs under
`homeassistant/<component>/<deviceId>_<object>/config`, creating:
- `switch.doormonitor_screen`
- `number.doormonitor_brightness`
- `sensor.doormonitor_battery`
- `binary_sensor.doormonitor_batt_warn`
- `event.doormonitor_mygate`, which fires on each MyGate notification (`event_type` as above,
  other fields as event attributes)

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

# Watch MyGate visitor alerts
mosquitto_sub -h 192.168.1.10 -t 'doormonitor/mygate/#' -v
```
