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
| `doormonitor/mygate/notification` | no | JSON — a MyGate alert (from the MyGate integration, or from the tablet) |
| `doormonitor/mygate/notification_removed` | no | JSON — that alert was answered or went away |
| `doormonitor/mygate/response` | no | JSON — how an answer to a visitor went (MyGate integration) |

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

Two things can publish MyGate's alerts to these topics, in the same JSON shape:

- **The MyGate integration for Home Assistant** ([`custom_components/mygate`](custom_components/mygate),
  set up [below](#mygate-integration-for-home-assistant)). It signs in to MyGate as its own
  device and receives MyGate's push alerts directly, so the MyGate app isn't needed on the
  tablet. It can also answer gate approvals.
- **The tablet**, when MyGate is installed and signed in there and Door Monitor has
  **Notification access** (Settings card under MQTT, or
  `adb shell cmd notification allow_listener com.doormonitor/com.doormonitor.mygate.MyGateNotificationListener`).
  It relays every notification MyGate shows, but can't answer them.

Use one or the other. With both, every visitor is announced twice.

The tablet is one of the subscribers. On each message it plays a chime and shows a card: the
visitor's photo, name and where they're from (`visitor`) come first, whenever they are sent.
- An alert with `responses` (a visitor waiting at the gate) gets **Approve / Deny** buttons,
  plus **Leave at gate** for deliveries. It stays up until the visitor is answered, from the
  tablet or anywhere else, or `timeoutMs` runs out. A tap publishes to `…/mygate/respond`. The
  card shows "Sending…", then the outcome, or the error from `…/mygate/response`.
- While such a card is waiting, a screen-off from outside (`command/screen OFF`,
  `command/sleep`, the HTTP API) is held until the card closes.
- Any other alert shows for 30 s.

If the screen was off, the tablet wakes it and switches it back off when the card closes. If
someone is already using the tablet, the screen is left on. `update: true` messages refresh the
card without ringing again. Anything else that publishes this JSON shape to the topic (at
minimum `title`) gets the same treatment.

`doormonitor/mygate/notification` example (a guest at the gate, from the integration):
```json
{
  "event_type": "approval",
  "key": "mygate:6703f1c2a9e4b1d0c8a7e512",
  "id": "6703f1c2a9e4b1d0c8a7e512",
  "postTime": 1792040569918,
  "time": "2026-10-15T10:32:49.918000+05:30",
  "title": "Ramesh (Guest) is at the gate",
  "text": "Ramesh sought approval for entry to A-1203",
  "bigText": null,
  "subText": "is waiting at the Main Gate",
  "actions": ["Approve", "Deny"],
  "channel": "mygate_default_channel",
  "category": "com.mygate.user.action.mygate_validate_visitor",
  "timeoutMs": 60000,
  "update": false,
  "visitor": {
    "name": "Ramesh",
    "company": "Swiggy",
    "company_logo": "https://…/swiggy.png",
    "image": "https://…/visitor-photo.jpg",
    "type": "delivery",
    "nature": "Delivery"
  },
  "responses": ["approve", "deny"],
  "test": false,
  "source": "home_assistant"
}
```
*(Field values are illustrative. Title and text are whatever MyGate's server sends.)*

| Field | Meaning |
|-------|---------|
| `event_type` | `approval` (someone at the gate needs Allow/Deny, plus kid-exit and smart-lock alerts), `emergency`, `vehicle`, or `general` (entries, deliveries left at gate, notices…) |
| `key` | Stable per alert. Pair a `notification` with its `notification_removed`. |
| `id` | Integration: the visitor's id, used to answer them. Tablet: Android's notification id. |
| `update` | `true` when MyGate changed an alert it already sent, or for the outcome of one (e.g. "approved by …"). Ignore it to react once per visitor. |
| `actions` | Labels of MyGate's buttons, e.g. `Approve`, `Deny`, `Leave at gate`, `View details` |
| `timeoutMs` | How long MyGate keeps an approval request up (`0` = no timeout) |
| `responses` | Integration only: what can be sent back for this visitor (`approve`, `deny`, and `leave_at_gate` for deliveries). Empty when the alert can't be answered. |
| `visitor` | Integration only, each field when MyGate sends it: `name`; `company`, where they're from (`Swiggy`, `Amazon`…) with its `company_logo` URL; `image`, the guard's photo of the visitor; `type` (`guest`, `delivery`, `cab`, or MyGate's numeric type); `nature`, MyGate's description of the visit. Check-in notices name the visitor only in their text ("Ramesh from Blinkit has checked in…"), so `name` and `company` are read from it. |
| `test` | Integration only: `true` for MyGate's test alert |

`doormonitor/mygate/notification_removed` has the same fields plus `reason`:
- from the integration: `responded` (with `response`: what was sent), `answered_elsewhere`
  (another resident or the guard answered), or `timeout`;
- from the tablet: `app_cancel` (answered on another phone or by the guard), `timeout`,
  `click`, `dismissed`, `package_changed`, or `other_<n>`.

Not retained: a subscriber that connects later does not get stale visitor alerts.

### Answering a visitor (integration only)

Publish to `doormonitor/mygate/respond`:
- `approve`, `deny` or `leave_at_gate` answers the most recent visitor still waiting;
- `{"action": "approve", "id": "<id>"}` answers a specific one.

Or call the `mygate.respond` action with the same `action` and optional `id`. A response must
be in that visitor's `responses`, and must arrive before `timeoutMs` runs out.

Every attempt is reported on `doormonitor/mygate/response`:
`{"id": "…", "action": "approve", "ok": true}`, or `"ok": false` with an `error` such as
`No pending MyGate visitor with id …`. A successful one is followed by `notification_removed`
with `reason: responded`.

### MyGate integration for Home Assistant

1. Copy [`custom_components/mygate`](custom_components/mygate) into the `custom_components`
   folder of your Home Assistant config.
2. In that folder, copy `identity.example.py` to `identity.py` and fill it in. It holds:
   - MyGate's Firebase values, from the MyGate app;
   - your phone's details: MyGate's version on it, its model, its Android SDK level, and its
     network name.

   The integration presents itself to MyGate as that phone. `identity.py` is kept out of git.
   Then restart Home Assistant.
3. Settings → Devices & services → Add integration → **MyGate**. Enter the mobile number you
   use with MyGate, then the OTP MyGate texts you. Home Assistant joins your MyGate account as
   one more phone.
4. To test it, run the **MyGate: Send test alert** action. MyGate pushes its visitor test alert
   to Home Assistant only. Answering a test alert stays inside Home Assistant: MyGate's own app
   doesn't send test answers to MyGate either.

It adds **MyGate Alerts connected**, a connectivity sensor that is off while MyGate's alerts
can't reach Home Assistant. It's worth an automation that tells you when it goes off. MyGate
can sign Home Assistant out on its side, and then simply stops sending alerts. The integration
checks for this hourly; when it happens, the sensor goes off and Home Assistant asks you to sign
in again. Deleting the integration signs it out of MyGate.

It keeps a history of the latest 100 alerts across restarts, with how each visitor was answered:
approved or denied (here or on another phone, and by whom when MyGate says), left at the gate,
or not answered. Test alerts are included, marked as such. To show it, add the **MyGate
history** card to a dashboard (Edit dashboard → Add card; it comes with the integration, there's
nothing else to install). Each alert shows the visitor's photo, name and where they're from,
with the time and outcome; tap one for its full text and a large photo. Options, in YAML or the
card's editor:

```yaml
type: custom:mygate-history-card
title: Visitors         # optional; "" for no header
limit: 10               # alerts shown before "Show more"
types: [approval]       # optional: only approval, general, vehicle and/or emergency alerts
```

The history lives in the `history` attribute of **MyGate Last alert**, a sensor holding when the
latest alert came, with its visitor's photo as the entity picture. The recorder leaves `history`
out.

This is unofficial. It sends MyGate's servers what the MyGate app sends, worked out from the
app's code, and identifies itself as your phone. MyGate's terms probably don't allow that, and a
MyGate update can break it. MyGate may also sign out every device on the account. That happened
once while this was being built, back when it still called itself "Home Assistant". Keep MyGate
on a phone as a backup.

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

# Approve the visitor waiting at the gate (needs the MyGate integration)
mosquitto_pub -h 192.168.1.10 -t doormonitor/mygate/respond -m approve
```
