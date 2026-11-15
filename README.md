# MotoOBD

An Android 8+ app for reading supported OBD-II telemetry and diagnostic trouble codes from a paired ELM-compatible Bluetooth adapter. Android 10 is supported. The Android host uses Kotlin and a bundled, offline WebView interface.

## Use

1. Pair the adapter in Android Bluetooth settings, then open MotoOBD while stationary.
2. Choose **Connect**, refresh paired devices, select the adapter and connect. Android 12+ asks for Nearby devices permission.
3. View measured values in **Live** and read stored, pending and permanent codes in **Faults**. Unsupported or stale values appear unavailable. Sampling is sequential and is not a high-rate data logger.
4. Clearing codes requires explicit confirmation and a positive ECU response. It can erase freeze frames and reset readiness monitors; permanent codes cannot be cleared with Mode 04.

The standalone HTML is an offline UI/demo preview. Real hardware access requires the Android APK. Demo is clearly labeled and never writes to an ECU. Generic DTC references are guidance, not a diagnosis. This app does not measure compression, invent freeze frames, or infer cylinder/sensor health from ordinary OBD polling.

## Build and checks

Install JDK 17 (JDK 21 also works locally), Android SDK platform 35 and build tools 34.0.0. Set `ANDROID_HOME`, or create an ignored `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows use `gradlew.bat`. The wrapper pins Gradle 8.9 with SHA-256 verification; AGP 8.7.3 and Kotlin 2.0.21 are pinned. Compile SDK is 35, target SDK is 34, minimum SDK is 26. The target remains unchanged to avoid introducing Android 15 edge-to-edge behavior during this refactor. This is a sideload/debug app, not a Play Store release.

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug signed). No release signing keys are included.

```sh
npm ci
npx playwright install chromium
npm test
```

UI tests use mocked native bridge responses, with phone/tablet screenshots and error-path checks. To use installed Chrome locally: `PLAYWRIGHT_CHANNEL=chrome npm test` (PowerShell: `$env:PLAYWRIGHT_CHANNEL='chrome'; npm test`). CI runs native unit tests, lint, build and UI checks on pushes/PRs.

## Source map

- `app/src/main/java/com/motoobd/scanner/MainActivity.kt`: WebView host, permissions, serial ELM worker, Classic/BLE transport and lifecycle cancellation.
- `app/src/main/java/com/motoobd/scanner/ObdParser.kt`: pure JVM ELM payload/PID/DTC parser.
- `app/src/main/assets/index.html`: bundled UI; `web/obd2_diagnostic_app.html` must match byte-for-byte (tested).
- `HANDOFF.md`: contracts and validation limits.
- `docs/REVIEW.md`: review findings and changes.

Legacy `deploy_*`, `fix_*`, and push scripts are retired stubs. Earlier versions overwrote source or deleted Git history. Use the wrapper to build and ordinary Git branch/commit/push commands to publish.

## Hardware limits

No adapter or vehicle was available during the refactor. Classic/BLE compatibility, permission prompts on real Android 10/12+ devices, ECU timing, ignition-off behavior, and physical disconnect/reconnect remain field tests. BLE supports paired adapters exposing supported serial services and notifications; pairing/discovery happens in Android settings. Some BLE-only adapters cannot be paired that way and are not supported by this version.
