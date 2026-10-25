# T-Dash — Free, Open-Source Tesla Dashboard App (Unofficial · BLE-only)

> English | [简体中文](README-CN.md)

T-Dash is a **free, open-source Tesla dashboard app** for Android (unofficial), built on a
**pure-Bluetooth** link: a digital cluster, vehicle controls, trip stats and a full suite of
driving tools, all talking to the car directly over BLE. **T-Dash has no server** — no developer account, no owner login, no subscription, and no
telemetry. The command layer is a from-scratch Kotlin implementation of the Vehicle Security /
Infotainment protocols defined by Tesla's official open-source library
[teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command); the `.proto`
definitions come from that repository.

> **Disclaimer**: This project is not affiliated with Tesla, Inc. It is intended for personal
> vehicle management and learning. By using it you acknowledge that sending commands to your
> vehicle is done at your own risk; never operate the phone while driving, and treat the
> vehicle's own behavior as the authoritative result.

## Scope and limits (read this first)

- **The phone must be near the car (~5 m)**: everything depends on the direct BLE link. Remote
  control is not offered in this version (the protocol layer reserves Fleet API HMAC
  authorization, currently not enabled).
- The car's built-in screen UI cannot be drawn by a third-party app; this app's "Cluster" page
  is a **phone-side** digital instrument.
- The network is used in exactly three places: AMap map tiles for the Stats-page track
  (public raster endpoint, disk cache), geocoding in "Tools → Accident assistant → Where am I",
  and the optional cluster weather (AMap, off by default). These requests go straight from the
  phone to AMap/OpenStreetMap and **never pass through T-Dash** — this project has no server.
  The vehicle link is BLE end to end.
- First-time pairing requires the **physical NFC key card**: after sending the pairing request,
  tap the card on the center-console reader and confirm on the car's screen.
- Compatibility: the protocol requires firmware ≥ 2023.38; encrypted command responses require
  ≥ 2024.38 (older firmware automatically falls back to plaintext). The phone needs Android 10
  (API 29) or newer with BLE support.
- Tested scope: a personal project, real-vehicle tested only on a Tesla Model 3 (Highland) and
  a vivo X100. Other vehicles or phones may hit uncovered issues — file an Issue, or patch the
  source yourself.

## Feature overview

- **Cluster (home page)**: big speed number with color bands (spot speeding at a glance;
  calibration can be aligned with the car's speedometer), power arc, threshold rail, gear;
  shifting to P switches to the parked layout (clock + battery / outside-temp / AC cards) while
  the driving layout shows a mini time + weather widget in the top-right corner; a "resume
  previous trip" pill; keeps listening with the screen off or in background while in D/R, and
  goes quiet automatically in P.
- **Controls**: lock / trunk / frunk / charge port / windows / climate / charging / Sentry /
  wake / honk & flash. Button availability follows the vehicle's real state (Unlock disabled
  while unlocked, closures disabled while driving, charge start/stop disabled without a cable);
  the last known state is shown instead of blanks, command failures are explained in place, and
  each command's measured latency is displayed.
- **Tools**: quick launch (opens AMap and other common apps), accident assistant (location +
  photo evidence checklist + one-tap A4 evidence PDF export), dashcam (reads the TeslaCam USB
  drive: event list, 1–4 camera synced playback, "jump to trigger moment", 2×2 stitched export
  with burned-in timecode), 0–100 timing (interpolated from the 250 ms speed stream, ±0.05 s),
  car wraps (compresses images and writes them to the USB `Wraps` folder for the car to apply).
- **Stats**: real-time speed/power curves (kept in full for the session), GPS track map colored
  by speed, trip switching and overlaying, local JSON persistence (crash recovery).
- **Settings**: connection fallback (manual MAC / custom broadcast name), AMap key (accident
  geocoding and optional weather), speed bands and five-band palette customization (±1 km/h
  steps, 8-color swatches), accident profile, local key and cache management, developer mode.

## Usage

### Install and permissions

- Install the Release APK, or build it yourself following "For developers" below. Requires
  Android 10 or newer.
- On first launch the app requests Bluetooth / "Nearby devices" (Android 12+) and **precise
  location**. Android 12+ BLE scanning can work without location at the OS level, but some OEM
  ROMs return restricted scan results when precise location is missing, so the app still asks.
  Location is only used for on-device tracks, accident positioning and optional weather —
  never uploaded.
- Reading the USB drive and saving evidence photos go through the system file picker and
  MediaStore — **no storage permission needed**.
- Until pairing has ever succeeded, opening the app lands on "Settings" with the first-run
  guide.

### First-time pairing (three steps)

1. **Stand next to the car**: stay within ~5 m, turn Bluetooth on. In "Settings" enter the
   17-digit VIN (on the vehicle label or registration) and tap "Scan and connect".
2. **Send one pairing request**: once the link shows "Connected", tap "Send pairing request".
3. **Tap the card and confirm**: hold the NFC key card on the center-console reader (the card
   slot in front of the cup holders) and tap "Confirm" on the car's screen, choosing the owner
   role. The app detects the result automatically and waits up to 2 minutes; tap "Retry" if it
   fails.

After pairing, only the public key is handed to the car; the private key never leaves the
phone. Subsequent app opens reconnect automatically.

### Daily use

- Opening the app connects to the car automatically; Cluster is the home page. The Cluster and
  Debug pages keep the screen awake.
- Shifting from P into R/D switches to the Cluster page automatically — no need to tap the tab.
- After manually tapping "Disconnect", auto-reconnect stays off until you tap "Scan and
  connect" again.
- Walking away from the car drops the link; come back and open the app. While driving in D/R
  the app keeps listening with the screen off or in background.

### What each page does

| Tab | Content |
| --- | --- |
| Controls | Locks, trunk/frunk, charge port, windows, climate, charging, Sentry, wake, honk & flash; buttons enable/disable with real vehicle state, failure reasons shown in place |
| Tools | Quick launch, accident assistant, dashcam, 0–100 timing, car wraps |
| Cluster | Speed/power/gear/heading; parked layout adds clock, battery, outside temp and AC, weather (if enabled) under the clock; driving layout adds a mini time + weather widget top-right; current-trip card and route mini-map |
| Stats | Speed and power curves, GPS track colored by speed, trip switching and overlay, trip deletion; without a network the basemap turns grey but tracks still draw |
| Settings | Connection, keys, AMap key, speed bands, evidence profile, local data; a "Debug" page appears when developer mode is on |

**Tools, more detail**:

- **Quick launch**: currently ships with AMap, Alipay, WeChat and Meituan. Apps that are not
  installed say so directly ("not found").
- **Accident assistant**: follow steps 1→6 (call police, share location, photo evidence, move
  the car, report insurance, …). Photos go to the system gallery under `Pictures/TeslaLink`;
  the PDF exports through the system share sheet. Fill in your name, phone, plate, insurer and
  license/registration photos first in "Settings → Personal profile".
- **Dashcam**: plug the TeslaCam USB drive into the phone via OTG and pick the `TeslaCam`
  directory (or the drive root). The drive is opened read-only. The event list filters by
  trigger type (AEB / Sentry / manual / rolling); 1–4 cameras play in sync, you can jump to the
  trigger moment, or stitch a single MP4 with burned-in timecode into the gallery at
  `Movies/TeslaLink`. Stitching decodes and re-encodes every frame and runs at roughly 1/2–1/3
  of real time.
- **0–100 timing**: after arming, the clock starts automatically when you launch from a
  standstill and stops at 100 km/h. Arming for 120 s without launching, or not reaching
  100 km/h within 60 s of launch, invalidates the run. Closed venues only.
- **Car wraps**: files go in a `Wraps` folder on the USB drive root — square PNGs, 512–1024 px
  per side, ≤1 MB each, up to 10 files. Non-1:1 images are cropped first; anything over 1 MB is
  compressed automatically. To apply, put the drive back in the glovebox and pick it in the
  car's "Toybox → Paint Shop → Wraps". Deleting a wrap really deletes the file (with a second
  confirmation) but never touches TeslaCam dashcam data.

### Settings reference

- **Connection fallback → Vehicle BLE address (MAC)**: fill this in when nRF Connect next to
  the car can see the vehicle (name like `S…C`) but this app cannot; after saving, the app
  dials the address directly and skips scanning. "Custom BLE broadcast name" only affects this
  app's matching, not the car's nickname.
- **AMap key (general)**: only affects geocoding in "Accident assistant → Where am I" and the
  optional cluster weather; the Stats basemap needs no key. Apply for a **Web service** type
  key on the AMap open platform, entering just the key — no "security secret", and don't check
  digital signing. The wrong platform type fails with 10009. Without a key, geocoding falls
  back to OpenStreetMap (often unreachable inside mainland China).
- **Cluster weather**: off by default. Turning it on requires a saved key and location
  permission; the phone's most recent fix resolves the city and fetches weather, refreshed
  about every 30 minutes. Fetching continues while driving (phone network only — no Bluetooth
  requests, no car-server load). The parked layout shows it under the clock; the driving layout
  shows a mini time + weather widget in the top-right corner. If any step fails the whole block
  is hidden — no placeholders — and the driving widget then shows just the time.
- **Cluster speed bands**: defaults 66 / 88 / 110 / 132 km/h, adjacent bands at least 1 apart.
  Changes take effect immediately; the big number, glow and threshold rail change color
  together. Restorable to defaults.
- **Personal profile (for accident evidence)**: read only by the evidence PDF; text goes into
  DataStore and ID photos are copied into app-private storage. Nothing leaves the device.
- **Local key and cache**: shows the last four digits of the key fingerprint. Only regenerate
  the key when the Debug page says the local key cannot be decrypted — this replaces the local
  key, after which you must re-pair with the NFC card and delete the old entry in the car's
  "Controls → Locks → Keys". Routine app updates and phone reboots never change the key.
  "Protocol session cache" speeds up the first command; clear and rebuild it if signing/counter
  errors persist.
- **Developer mode**: adds a "Debug" tab to the bottom bar. Not needed for daily use.

### Troubleshooting

- **Can't find the vehicle**: ① the official Tesla app may be holding one of the car's three
  BLE connections — quit it completely; ② while awake or driving, VCSEC may stop advertising —
  lock the car and wait 2–3 minutes; ③ check that the system granted location permission, not
  just "Nearby devices" (some Chinese ROMs return restricted scan results otherwise); ④ fall
  back to the MAC address.
- **Pairing fails**: make sure the card is on the reader, "Confirm" was tapped on the car's
  screen and the owner role was chosen; retry after the 2-minute timeout. Firmware below
  2023.38 is incompatible.
- **Controls slow or unresponsive**: check the link status on Settings. A sleeping car must be
  woken first, which costs one extra round trip on the first command; distance from the car and
  the official app holding a connection both affect latency. The Controls page shows measured
  milliseconds and failure reasons per command.
- **Weather doesn't show**: check that weather is on in Settings, the key is a Web service key,
  location permission is granted and the network works. Parked layout: under the clock.
  Driving layout: the top-right mini widget (it shows the time even when weather is
  unavailable). Hiding the block when any precondition is missing is by design, not a bug.
- **Address is only coordinates**: the AMap key is missing or the wrong platform type (commonly
  10009); the OSM fallback is often unreachable on mainland networks.
- **Dashcam finds nothing / won't play**: confirm the drive contains `TeslaCam/RecentClips`,
  `SentryClips` and `SavedClips`; re-grant the folder after unplugging and replugging;
  "Rescan" only re-lists the current directory, it does not re-pick one.
- **No track on Stats**: tracks are recorded on the Cluster or Stats page while speed is above
  2 km/h; location permission is required. Indoor GPS drift points are filtered, but this is
  not a substitute for a dedicated recorder.
- **New phone / reinstall / cleared data**: the local key is gone — re-pair, and delete the old
  entry in the car's "Controls → Locks → Keys" so the key list doesn't fill up.

## Why latency is low

| Channel | Typical latency | Depends on |
| --- | --- | --- |
| **Direct BLE (the only channel)** | **~50–300 ms** | nothing |

- **Session cache**: handshake results (epoch/counter/domain keys) persist to disk, saving one
  round trip on the first command after a restart.
- **VCSEC without wake-up**: lock/unlock and door state go through the body security controller
  and answer instantly even while the vehicle sleeps.
- **Driving-time bandwidth allocation**: the speed lane owns the fast path (250 ms, single
  attempt with a short timeout); the slow lane goes completely silent while driving, and slow
  failures never block the speed readout.
- The Controls page shows measured per-command latency.

## For developers

### Requirements

- **JDK 17** (`compileOptions` / `jvmTarget` are both 17).
- **Android SDK Platform 35** + **Build-Tools 35.0.1**; `compileSdk 35`, `targetSdk 35`,
  `minSdk 29`.
- The latest stable Android Studio works; pure command-line builds work too.
- Versions are managed in `gradle/libs.versions.toml`: AGP 8.13.0, Kotlin 2.2.20,
  Compose BOM 2024.12.01, protobuf 4.29.3.
- Create `local.properties` at the repo root with your SDK path (not committed):
  ```properties
  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  ```
- The Gradle Wrapper is 8.13; distribution downloads use a Tencent mirror and dependencies are
  mirrored via Aliyun in `settings.gradle.kts` — outside mainland China, feel free to switch
  back to `google()` / `mavenCentral()`.

### Build and unit tests

```bash
./gradlew :app:assembleDebug        # output: app/build/outputs/apk/debug/
./gradlew :app:installDebug         # install to a connected device
./gradlew :app:testDebugUnitTest    # protocol test vectors + trip persistence, JVM tests
./gradlew :app:assembleRelease      # needs signing config, see next section
```

The first build downloads `protoc` via the protobuf plugin and generates lite Java bindings
from `app/src/main/proto/` (outputs live under `build/`, do not edit by hand).

### Release signing

The release signature is read only from `keystore.properties` at the repo root; that file plus
`*.jks` / `*.keystore` are excluded by `.gitignore` — **never commit them**:

```properties
storeFile=../release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

- `storeFile` resolves relative to the `app` module directory; absolute paths also work.
- Without that file the `release` build simply carries no signing config and produces an
  unsigned APK; use `assembleDebug` for day-to-day work.
- `versionCode` / `versionName` live in `app/build.gradle.kts`. Version text is also hardcoded
  in two more places: the "About" section of `SettingsScreen.kt` and the `promo/index.html`
  footer — update all three together on release.

### Protocol implementation and tests

- The `.proto` files under `app/src/main/proto/` come from `teslamotors/vehicle-command`; the
  build generates lite bindings such as `*OuterClass`.
- The protocol core is in `protocol/`: `Crypto.kt` (P-256 ECDH, AES-GCM, HMAC-SHA256),
  `Session.kt` (command signing / response decryption, session export and restore),
  `Dispatcher.kt` (session management, request-response matching, caching and resync),
  `Metadata.kt` (TLV), `Errors.kt` (error classification, retry decisions, anti-replay sliding
  window).
- `app/src/test/` is a regression net that needs no real car: `ProtocolVectorsTest` ports the
  official test vectors (ECDH/HMAC/TLV/GCM and more), plus trip persistence, drift filtering
  and resume cases. After touching protocol or crypto code, `:app:testDebugUnitTest` must be
  green first.

### Code layout

```
app/src/main/proto/             official .proto (universal_message / vcsec / car_server …)
app/src/main/java/com/teslalink/app/
  protocol/                     Vehicle Command protocol core
  ble/BleTransport.kt           GATT scan/connect/MTU/framing
  vehicle/                      payload building and high-level commands (retry, wake, pairing, telemetry)
  store/                        AndroidKeyStore key custody, DataStore prefs and session cache
  weather/WeatherService.kt     AMap geocoding + weather (caller refreshes ~every 30 min, silent on failure)
  vm/                           LinkViewModel / DashboardViewModel / TripRecorder / ...
  ui/                           Compose UI (Controls / Tools / Cluster / Stats / Settings / Debug)
  tools/                        dashcam scan & stitch export, evidence PDF, wraps writer, coordinate conversion
  debug/                        leveled logging and crash dumps
app/src/test/                   official protocol test vectors + trip regressions (runs without a car)
```

### Conventions

- **The vehicle link is BLE only; the project has no server.** `INTERNET` is allowed only for
  the Stats basemap, geocoding and optional weather, sent straight from the phone to the third
  party. Never forward vehicle commands, telemetry or tracks to any server, and never introduce
  your own backend, accounts, or telemetry/crash-reporting SDKs (`CrashLog` stays on-device).
  New network touchpoints must update the Security & privacy section here, the Settings "About"
  copy and the promo page's network description.
- **Don't "clean up" the permission posture**: `BLUETOOTH_SCAN` intentionally omits
  `neverForLocation`, and `ACCESS_FINE_LOCATION` is still requested on Android 12+ — some OEMs
  (e.g. OriginOS) return restricted scan results under `neverForLocation`, potentially hiding
  the vehicle's advertisements entirely. Read the comments in `AndroidManifest.xml` before
  changing it.
- **Adding a quick-launch app**: Android 11+ package visibility requires declaring the package
  in `<queries>` in `AndroidManifest.xml` and adding an entry to the list in `ToolsScreen.kt`;
  otherwise `getLaunchIntentForPackage` returns null and the button silently does nothing.
- **The AMap key is runtime input only**: stored in on-device DataStore (`amap_key`); never put
  it in source, `BuildConfig` or logs. Weather is off by default; with no key, no location
  permission, or a failed request the whole block stays hidden — that's a product decision, not
  something to "fix" with a placeholder.
- **Delete operations must not touch user files**: removing/clearing evidence photos only edits
  the on-page list, never the gallery originals; the dashcam is read-only toward the USB
  drive; the wraps tool only operates on `Wraps` and never touches `TeslaCam`.
- **Logs**: never print private keys, Keystore passwords or the user's AMap key; keys may only
  appear as the last four fingerprint digits.

### Debugging

1. "Settings → About and developer" — enable **developer mode**; a "Debug" tab appears (it
   keeps the screen awake).
2. "Run self-check" walks 9 steps: VCSEC session, key chain, local key enrollment, infotainment
   handshake, wake-up, infotainment retry, Ping, single-category telemetry, all-category
   telemetry — about a minute, all failure codes logged.
3. For deeper troubleshooting enable "Protocol trace" to record every BLE/protocol message and
   frame hex (it floods during polling — keep it off normally). Logs filter by tag, copy-all
   and clear.
4. The last abnormal exit restores the crash scene at the top of the Debug page with one-tap
   copy; `debug/CrashLog.kt` writes the dump, cleared via "Got it".
5. Without a car, protocol and trip logic are fully covered by JVM unit tests; BLE behavior
   needs the real vehicle — nRF Connect helps verify the broadcast name and MAC.

## Security and privacy: T-Dash has no server

**T-Dash has no backend.** The project runs no application backend, API endpoint, relay or
cloud sync: no account system, no telemetry reporting, and no admin console through which a
developer could query user data. The phone and the vehicle talk over direct BLE only, so no
central database or API holding owner data exists, and there is no project server to attack
remotely.

- **The key never leaves the phone**: the private key (P-256) is stored only on-device,
  encrypted by AndroidKeyStore; pairing hands the car the public key only. Neither the
  developer nor any server can obtain the private key.
- **Data never leaves the phone**: trip tracks, personal info, the protocol session cache and
  crash logs live in app-private storage and disappear on uninstall or data clearing. Evidence
  photos live in the system gallery at `Pictures/TeslaLink` and exported videos at
  `Movies/TeslaLink`, managed by the system gallery — uninstalling the app does not remove
  them, you clean those up yourself. In-app delete actions never proactively remove those
  gallery files.
- **Network never passes through T-Dash**: only the Stats basemap, geocoding and optional
  weather touch the network, straight from the phone to AMap or OpenStreetMap (geocoding falls
  back to OSM without a key). What leaves the device is only tile numbers, coordinates or a
  city code — never the VIN, speed or track files; no request passes any endpoint under this
  project's control.
- **No telemetry, no accounts, no cloud**: the app requires no login, touches no Tesla account,
  and offers no "upload diagnostics" switch; debug logs are viewed and copied on the phone only.
- **Logs are minimized**: only the last four digits of key fingerprints ever appear in logs.

## Acknowledgements

- [teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command) (Apache-2.0) —
  source of the protocol definitions and `.proto` files.
- [esphome-tesla-ble](https://github.com/esphome/tesla-ble) and its community forks
  (AGPL-3.0) — used only as a behavioral reference while debugging; no code was copied. This
  project's protocol implementation is an independent Kotlin implementation.

## License

[Apache License 2.0](LICENSE)
