# Build & APK Instructions

## Prerequisites
- **Android Studio** Koala (2024.1) or newer, **or** a standalone JDK 17 + Android SDK.
- **Android SDK Platform 35** and **Build-Tools 35**.
- A device or emulator running **Android 14 (API 34)** or newer.
- Internet access for the first Gradle sync (downloads AndroidX, HiveMQ, libVLC, etc.).

> This repository ships the full Gradle wrapper (`gradlew`, `gradlew.bat` and
> `gradle/wrapper/gradle-wrapper.jar`, Gradle 8.9). You can build immediately with `./gradlew`
> or just open the folder in Android Studio. The project has been verified to build both debug
> and release APKs with JDK 17/21, AGP 8.7.3 and compileSdk 35.

## 1. (Only if the wrapper is missing) regenerate it
```bash
cd door-monitor-2
gradle wrapper --gradle-version 8.9   # needs any local Gradle; usually unnecessary
```
Android Studio users can skip this — open the folder and let it sync.

## 2. Build a debug APK
Android Studio: **Build ▸ Build APK(s)**, or:
```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```
Install on a connected device:
```bash
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Build a release APK
Create `keystore.properties` in the project root (next to `settings.gradle.kts`):
```properties
storeFile=/absolute/path/to/door-monitor.keystore
storePassword=********
keyAlias=doormonitor
keyPassword=********
```
Generate a keystore if needed:
```bash
keytool -genkeypair -v -keystore door-monitor.keystore \
  -alias doormonitor -keyalg RSA -keysize 2048 -validity 10000
```
Then:
```bash
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```
> If `keystore.properties` is absent, the release variant falls back to the debug signing key
> so it still assembles (do **not** ship that build).

The release build enables R8 minification + resource shrinking and **disables WebView remote
debugging**.

### APK size
libVLC bundles native codec/demux libraries, so the APK is large. `app/build.gradle.kts`
restricts ABIs to `arm64-v8a` + `armeabi-v7a` (≈90 MB release). For the Lenovo Tab M11 you can
drop to **arm64 only** (≈45 MB) by editing `abiFilters` to `listOf("arm64-v8a")`. Add `x86_64`
if you build for an emulator.

## 4. First-run setup on the tablet
1. Install and open Door Monitor.
2. Grant the prompts:
   - **Notifications** (foreground service + alerts).
   - **Modify system settings** (`WRITE_SETTINGS`, needed for persistent brightness) — the app
     opens this screen automatically; toggle Door Monitor on and return.
3. Recommended for an always-on appliance:
   - **Settings ▸ Apps ▸ Door Monitor ▸ Battery ▸ Unrestricted**.
   - Disable any OEM "battery optimization"/"auto-start manager" restriction for the app.
4. Enter the dashboard URL and other settings.

## 5. (Optional) Device-owner / Lock Task kiosk
Strongest kiosk mode. The device must be **factory-reset with no accounts** added.
```bash
# Install the app first, then:
adb shell dpm set-device-owner com.doormonitor/.kiosk.DeviceAdminReceiver
```
Enable **Lock Task Mode** in Settings. To remove later:
```bash
adb shell dpm remove-active-admin com.doormonitor/.kiosk.DeviceAdminReceiver
```
> `set-device-owner` only succeeds on a freshly reset device with no Google account.

As device owner, Door Monitor automatically **removes the lock screen entirely** (via
`setKeyguardDisabled`), so waking from a screen-off lands directly on the dashboard with no
keyguard and no lock-screen flash. This only applies when there is **no secure lock**
(PIN/pattern/password) set, and is a no-op for a plain device admin. A regular device admin
can only *dismiss* an insecure keyguard on wake (brief flash); full removal needs device owner.

## 6. (Optional) Set as Home launcher
For a softer kiosk without device-owner, set Door Monitor as the default **Home** app
(Android **Settings ▸ Apps ▸ Default apps ▸ Home app**). It registers a HOME intent filter,
so pressing Home returns to the dashboard.

## Troubleshooting
| Symptom | Fix |
|---------|-----|
| Brightness commands ignored | Grant **Modify system settings**. |
| Screen won't turn off | Grant **device admin/owner** (without root, off relies on lock/ timeout). |
| Service dies overnight | Disable OEM battery optimization; prefer device-owner mode. |
| Cleartext camera/HA fails | Add the host to `app/src/main/res/xml/network_security_config.xml` and rebuild, or use HTTPS. |
| libVLC RTSP stutters | The player already forces `--rtsp-tcp`; raise `--network-caching` in `CameraPlayers.kt`. |
