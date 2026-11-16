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
| `doormonitor/command/dashboard` | *(any)* | Close any camera view and return to the dashboard |
| `doormonitor/command/navigate` | `<path or URL>` | Point the dashboard at a path (e.g. `/roo-bus`), then back |
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
| `doormonitor/roorides/bus` | yes | JSON — the school bus's latest position and status (Roo Commuter integration) |
| `doormonitor/roorides/available` | yes | `online` / `offline` — whether the Roo integration is running |

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

The tablet is one of the subscribers. On each message it plays a chime and shows a card. Entry
approvals (`event_type` `approval`, or any alert with `responses`) get their own brighter, rising
tone; everything else gets the usual ding-dong, and an `update` rings nothing. On the card, the
visitor's photo, name and where they're from (`visitor`) come first, whenever they are sent.
- An alert with `responses` (a visitor waiting at the gate) gets **Approve / Deny** buttons,
  plus **Leave at gate** for deliveries. It stays up until the visitor is answered, from the
  tablet or anywhere else, or `timeoutMs` runs out. A tap publishes to `…/mygate/respond`. The
  card shows "Sending…", then the outcome, or the error from `…/mygate/response`.
- While such a card is waiting, a screen-off from outside (`command/screen OFF`,
  `command/sleep`, the HTTP API) is held until the card closes.
- Any other alert shows for 30 s.
- Only alerts whose text matches the tablet's **MyGate alerts** setting are shown, so MyGate's
  marketing pushes stay off the screen. It's a regular expression, matched anywhere in the text
  and ignoring case; the default, `.*(checked|approv|declined|denied|invite|kid).*`, keeps
  check-ins (visitors, and vehicles in or out), approval requests and their outcomes, declined
  or denied entries, invites and alerts about kids. Blank shows everything. A visitor waiting
  for an answer and an emergency always show.

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
filter: "(checked|approv)"  # optional: only alerts whose text matches this regular expression
```

`filter` is matched anywhere in the alert's text, ignoring case. Left out, it's the tablet's
default above, which hides MyGate's marketing; `.*` shows everything. Visitors who could be
answered and emergencies always show.

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

# Follow the school bus (needs the Roo Commuter integration)
mosquitto_sub -h 192.168.1.10 -t 'doormonitor/roorides/#' -v
```

## Roo Commuter bus tracking

The **Roo Commuter integration** ([`custom_components/roorides`](custom_components/roorides))
signs in to the parent's Roo (roorides.com) account, polls the child's live tracking, and while
the bus is underway follows it — over Roo's live-tracking socket when it can — and publishes the
bus's position to `doormonitor/roorides/bus` (retained). It also exposes a `device_tracker`, a
status/ETA `sensor` and an "en route" `binary_sensor`. On the tablet, a dedicated full-screen
map **panel** dashboard shows the bus; an automation switches the tablet to it (and keeps the
screen on) while a trip is active, then back to the normal dashboard when it ends — using the
`navigate`/`dashboard`/`wake`/`sleep` commands above.

Roo only hands a parent the bus while the child's own trip is active; off-trip the position
stays at its last point and the bus is marked not running.

The integration's **Stop tracking** `button` (available only while a trip is active) drops the
journey in progress, e.g. when the child didn't board or the driver never ends the trip in Roo.
The bus is then marked not underway (`active: false`, status `Tracking stopped`), so the
en-route automation sends the tablet back to its dashboard. The integration keeps polling
slowly and starts tracking again by itself with the next journey, i.e. once Roo's trip status
moves off the stopped one (`Drop Ongoing` → ended, or → `Pickup Ongoing`). The stop survives a
Home Assistant restart.

`doormonitor/roorides/bus` example:
```json
{
  "active": true,
  "latitude": 28.4512,
  "longitude": 77.0731,
  "vehicle_id": "12345",
  "bus_number": "DL1PC0000",
  "driver_name": "…",
  "speed": 24.0,
  "address": "…",
  "eta": "7",
  "eta_unit": "min",
  "eta_message": "Vehicle scheduled to arrive by …",
  "status": "En route",
  "device_datetime": "2026-10-22T14:40:11",
  "updated": 1761143011.0
}
```

*(Field values are illustrative. The exact Roo field names inside `liveTrackings` are confirmed
from the first live trip; the integration logs that first payload and parses the names case-
insensitively, so the bus shows up regardless.)*

### Roo Commuter integration for Home Assistant

1. Copy [`custom_components/roorides`](custom_components/roorides) into your Home Assistant
   `custom_components/` and restart Home Assistant.
2. Settings → Devices & services → Add integration → **Roo Commuter**. Enter the phone number
   and password you use with the Roo Commuter app, then pick the child. Home Assistant signs in
   to your Roo account and keeps the session alive by refreshing its token.
3. For the tablet takeover: a **panel** dashboard (one full-width `map` card on
   `device_tracker.<child>_s_bus_location`) is the full-screen bus view the tablet switches to.
   An automation on the **en route** `binary_sensor` publishes `command/navigate` to that
   dashboard and `command/wake` on trip start, and `command/navigate` back plus `command/sleep`
   on trip end; a second keeps the screen on if anything sleeps it mid-trip; a third fires
   `command/dashboard` to close the doorbell camera when the visitor leaves. See the Roo
   automations in [`automations.yaml`](automations.yaml). HA's sections dashboards cap card
   width, so the bus map needs a **panel** view to go edge to edge.
4. Below the map, a `conditional` card (shown while the **en route** sensor is `on`) holding a
   `tile` for the **Stop tracking** button, with `tap_action: perform-action button.press` and a
   `confirmation`, lets you dismiss a journey from the tablet.

This is unofficial: it calls the same parent API the Roo app calls, worked out from the app and
Roo's web bundle. A Roo update can break it. Unlike some apps Roo uses refresh tokens, so Home
Assistant signs in only once and then refreshes — it should not sign your phone out.
