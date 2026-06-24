# Architecture

Door Monitor follows **MVVM** with a long-running **foreground service** as the always-on
backbone. Commands from any transport are normalized into one handler so behaviour is
identical whether they arrive via HTTP, MQTT, or an HA webhook.

```
                          ┌───────────────────────────────────────────────┐
                          │            KioskForegroundService             │
                          │  (START_STICKY, foreground, owns long-lived    │
                          │   components; survives Activity recreation)    │
                          │                                                │
   HTTP  ───────────►  LocalHttpServer ─┐                                  │
   (NanoHTTPD, :2323)                   │                                  │
                                        ▼                                  │
   MQTT  ───────────►  MqttManager ──► CommandHandler ──► ScreenController │
   (HiveMQ)                             │   ▲                  │           │
                                        │   │                  │ system    │
   HA webhook ──────►  /event route ────┘   │                  ▼ level     │
                                            │            PowerManager,     │
   Battery broadcast ► BatteryMonitor ──────┘            Settings.System,  │
                                            │            DevicePolicyMgr   │
                                            │                              │
                                            ▼ window-level (KioskBus)      │
                          └───────────────────────────────────────────────┘
                                            │
                                            ▼
                          ┌───────────────────────────────────────────────┐
                          │                 MainActivity                  │
                          │  (immersive kiosk, NavHost: Dashboard/Settings)│
                          │   collects KioskBus → applies window flags,    │
                          │   brightness, launches CameraActivity          │
                          │                                                │
                          │   DashboardScreen → hardened WebView           │
                          │   SettingsScreen  → DataStore via MainViewModel│
                          └───────────────────────────────────────────────┘
```

## Components

| Layer | Class | Responsibility |
|-------|-------|----------------|
| App | `DoorMonitorApp` | Notification channels. |
| Service | `KioskForegroundService` | Owns MQTT/HTTP/Battery; observes settings; foreground notification. |
| Service | `KioskWatchdog` (WorkManager) | Periodic best-effort re-start of the service. |
| Boot | `BootReceiver` | Auto-start service + activity on boot. |
| Core | `CommandHandler` | Transport-agnostic command surface + `statusJson()`. |
| Core | `KioskBus` / `KioskCommand` | SharedFlow bridge from background → Activity window. |
| Screen | `ScreenController` | Wake locks, brightness (system + window), device-admin `lockNow()`, timeouts. |
| Kiosk | `KioskController` | Immersive flags + Lock Task Mode. |
| Kiosk | `DeviceAdminReceiver` | Device-owner hook. |
| Web | `DashboardWebViewFactory` | Hardened WebView, cookie persistence, crash recovery. |
| HTTP | `LocalHttpServer` | NanoHTTPD JSON API + auth. |
| MQTT | `MqttManager` | HiveMQ connect/subscribe/publish + HA discovery + LWT. |
| HA | `HomeAssistantClient` | Outbound webhook/event calls (OkHttp + long-lived token). |
| Battery | `BatteryMonitor` | `ACTION_BATTERY_CHANGED`, high-SoC detection. |
| Camera | `CameraActivity`, `CameraPlayers` | Full-screen player selection by protocol. |
| Data | `SettingsRepository`, `AppSettings`, `CameraDef` | DataStore-backed config. |
| UI | `MainActivity`, `DashboardScreen`, `SettingsScreen`, `MainViewModel`, theme | Compose. |

## Key flows

### Remote screen-on
`GET /screenOn` (or MQTT `…/command/screen ON`) → `CommandHandler.screenOn()` →
`ScreenController` acquires a momentary `FULL_WAKE_LOCK` (system wakes) **and** emits
`WindowScreenOn`/`WindowBrightness` on `KioskBus` → `MainActivity` sets
`setTurnScreenOn(true)` + `FLAG_KEEP_SCREEN_ON`, dismisses the keyguard, and applies window
brightness. The split is necessary because only the Activity can manipulate its window, while
the wake lock must be held by a non-UI component.

### Screen-off without root
`screenOff()` uses `DevicePolicyManager.lockNow()` when Door Monitor is device admin; otherwise
it forces window brightness to 0 and releases the keep-screen-on hold so the system timeout
sleeps the panel. True power-off of the panel is impossible from an unprivileged app.

### WebView crash recovery
`WebViewClient.onRenderProcessGone` destroys the dead view and bumps a Compose `key`, so the
`AndroidView` subtree is rebuilt and the dashboard reloads. Network loss sets a flag; a
`ConnectivityManager` default-network callback reloads when connectivity returns.

### Config propagation
`SettingsRepository.settings` (DataStore `Flow<AppSettings>`) is the single source of truth.
The service `collectLatest`s it and (re)applies the HTTP port, MQTT connection, battery
thresholds and lock-task flag only when the relevant fields change.

## Threading
- Service work runs on `lifecycleScope` (cancelled with the service).
- NanoHTTPD uses its own thread pool; handlers post window commands via `KioskBus.tryEmit`
  (non-blocking) and call `ScreenController` (thread-safe via `AtomicReference`).
- HiveMQ callbacks run on its internal threads; only thread-safe calls are made from them.
- All window/WebView mutations happen on the main thread inside `MainActivity`/Compose.

## Reliability model
1. **Foreground service + `START_STICKY`** — primary mechanism.
2. **`BootReceiver`** — survives reboots.
3. **`KioskWatchdog`** (15-min WorkManager) — best-effort restart.
4. **Device-owner mode** — the most robust option (removes background-FGS-start limits and
   enables Lock Task). Recommended for truly unattended installs.
