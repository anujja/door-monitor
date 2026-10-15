# Door Monitor

A lightweight, open-source **Fully Kiosk Browser replacement** for a permanently wall-mounted
Home Assistant tablet. Built for **Android 14+** (developed against the **Lenovo Tab M11**),
designed to run unattended for months while staying plugged in 24/7.

The tablet normally shows a full-screen Home Assistant dashboard. When Home Assistant sends a
command — over **MQTT**, an **HA webhook**, or the **local HTTP API** — the tablet wakes
instantly and shows the dashboard and camera feeds.

> **Non-goals:** this is an appliance, not a browser. No tabs, no downloads, no file manager,
> no ads, no analytics, no cloud services.

---

## Features

### Dashboard
- Full-screen, chrome-less **WebView** (no address bar, no nav controls, no pull-to-refresh).
- Configurable dashboard URL.
- **Home Assistant authentication** via persisted session cookies + a long-lived token — one
  sign-in, no repeated logins.
- Auto-reload on **WebView render-process crash**.
- Auto-reload after **network outages**.

### Kiosk mode
- Sticky **immersive full-screen** (status + navigation bars hidden).
- Accidental-exit protection (back button swallowed, long-press/selection disabled).
- Optional **device-owner Lock Task Mode** for tamper resistance.
- Hidden **5-tap top-left corner** gesture to open Settings.

### Screen control (remote)
- `screen on` / `screen off`
- `set brightness` (0–255)
- `temporary wake` (auto-sleep after N seconds)
- `permanent wake`
- Wakes **immediately** when a command arrives.

### Local HTTP API (Fully-Kiosk style)
```
GET /status
GET /screenOn
GET /screenOff
GET /brightness?value=20
GET /wake            (permanent)   GET /wake?seconds=30   (temporary)
GET /sleep
GET /reload
GET /camera?id=front_door
GET /event?type=motion|doorbell
```
All responses are JSON. Optional password (`?password=…` or `Authorization: Bearer …`).

### Home Assistant integration
- **MQTT** with auto-discovery (a Screen switch, Brightness number, Battery sensor and a
  Battery-High binary_sensor appear automatically).
- **HA webhooks** (inbound, via `/event`) and **HA REST/events** (outbound).

### MyGate visitor alerts → MQTT (optional)
- MyGate alerts (visitor at the gate, delivery, emergency…) are published to
  `<deviceId>/mygate/notification` as JSON, and to `…/notification_removed` when answered or
  timed out. Also exposed as an HA `event` entity. They come from either:
  - the **MyGate integration for Home Assistant**
    ([`homeassistant/custom_components/mygate`](homeassistant/custom_components/mygate)). It
    receives MyGate's alerts directly, so MyGate needn't be installed on the tablet, and it can
    approve or deny visitors (`mygate.respond`, or MQTT `…/mygate/respond`); or
  - the tablet itself, relaying MyGate's notifications when MyGate is signed in on it and
    **Notification access** is granted.
- The tablet subscribes to that topic too. It rings a chime, wakes the screen and shows the
  alert as a card led by the visitor's photo, name and company (Swiggy, Amazon…). A visitor
  waiting at the gate gets **Approve / Deny** buttons and stays on screen until answered; other
  alerts show for 15 s. Then the screen sleeps again. See
  [MQTT topics](homeassistant/mqtt-topics.md#mygate-notifications).

### Motion-based wake (optional)
- Wake on **camera motion** (MQTT), **doorbell press**, or **HA webhook**.

### Cameras
- **WebRTC** (in-page), **HLS** (Media3/ExoPlayer), **MJPEG** & **RTSP** (libVLC).
- Dedicated full-screen camera activity (`/camera?id=…`).

### Reliability
- **Auto-start after boot**.
- Crash recovery (`START_STICKY` foreground service + WorkManager watchdog).
- WebView crash recovery and Wi-Fi reconnect.

### Battery protection
- Charge-level, health, temperature and plugged-state monitoring.
- Raises a warning (notification + MQTT + HA event) when the battery sits above a configurable
  threshold (default **90%**) for a configurable time (default **24 h**), so an HA automation
  can cut power via a smart plug. *(Android cannot cap charging without root — this detects the
  condition for you to act on.)*

### Security
- **Cleartext traffic disabled by default** (opt-in per host in `network_security_config.xml`).
- HTTPS supported.
- Optional API authentication.
- **WebView remote debugging disabled in release builds**.

---

## Quick start
1. Build & install (see [docs/BUILD.md](docs/BUILD.md)) or sideload the APK.
2. Launch **Door Monitor**. With no dashboard set it opens **Settings**.
3. Enter your **Dashboard URL** (e.g. `https://homeassistant.local:8123/lovelace/wall`).
4. (Optional) enable **MQTT**, set the **API password**, define **cameras**.
5. Sign in to Home Assistant once in the WebView — the session persists.
6. Mount the tablet. Control it from HA (see [`homeassistant/`](homeassistant/)).

Find the tablet IP and test the API:
```bash
curl http://<tablet-ip>:2323/status
curl http://<tablet-ip>:2323/screenOff
curl "http://<tablet-ip>:2323/brightness?value=20"
```

## Documentation
- [Architecture](docs/ARCHITECTURE.md)
- [Build & APK instructions](docs/BUILD.md)
- [HA configuration](homeassistant/configuration.yaml) ·
  [Automations](homeassistant/automations.yaml) ·
  [MQTT topics](homeassistant/mqtt-topics.md) ·
  [REST API](homeassistant/rest-api.md)

## Technology
Kotlin · Jetpack Compose · AndroidX · MVVM · Coroutines/Flow · DataStore · WorkManager ·
Media3 ExoPlayer + libVLC · HiveMQ MQTT client · NanoHTTPD · OkHttp.

## License
MIT — see `LICENSE`. Not affiliated with Fully Kiosk or Home Assistant.
