# Engineering Handoff Document — MotoOBD Telemetry Pro (Android 10)

## 1. Executive Architecture Overview

**MotoOBD Telemetry Pro** is built using a hybrid dual-target architecture so that the entire diagnostic UI can run standalone in any modern mobile browser (`web/obd2_diagnostic_app.html`) OR compiled into a native **Android 10 (API 29)** APK (`app/src/main/java/com/motoobd/scanner/MainActivity.kt`) with full Classic Bluetooth RFCOMM support:

- **Typography & UI Design System**:
  - **Display & Numeric Readouts**: `Space Grotesk` (`500, 600, 700`) with `font-variant-numeric: tabular-nums` to prevent horizontal layout jitter during 4 Hz live telemetry updates.
  - **UI Body & Controls**: `Plus Jakarta Sans` (`400–800`) for high-contrast readability across phone and tablet viewports.
  - **HEX Frames, PIDs & Terminal**: `JetBrains Mono` (`400–700`) for CAN bus hex payloads and AT commands.
- **State Management & Simulation Engine**:
  - Centralized `appState` store in JavaScript managing active connection state, unit conversion (`Metric: km/h, °C, kPa` vs `Imperial: mph, °F, PSI` persisted in `localStorage`), 24-point rolling telemetry ring buffers for Chart.js, active DTC arrays, and 4 pre-calibrated vehicle diagnostic profiles (`fault_misfire`, `healthy_idle`, `highway_cruise`, `low_volt_hot`).

---

## 2. SAE J1979 Mode 01 PID Implementation Matrix

All live dashboard gauges and sensor health rows implement standard [SAE J1979 Mode 01 formulas](https://en.wikipedia.org/wiki/OBD-II_PIDs):

| Parameter | Mode & PID | Bytes | Decoding Formula | Units | Normal Operating Range |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Engine RPM** | `01 0C` | 2 (`A, B`) | `(256 * A + B) / 4` | `RPM` | `700 – 6,500 RPM` |
| **Vehicle Speed** | `01 0D` | 1 (`A`) | `A` | `km/h` | `0 – 255 km/h` |
| **Water / Coolant Temp (ECT)** | `01 05` | 1 (`A`) | `A - 40` | `°C` | `82 – 100 °C` |
| **Intake Air Temp (IAT)** | `01 0F` | 1 (`A`) | `A - 40` | `°C` | `10 – 50 °C` |
| **Control Module Voltage** | `01 42` / `ATRV` | 2 (`A, B`) | `(256 * A + B) / 1000` | `V` | `13.5 – 14.5 V` (Running) |
| **Intake Manifold Pressure (MAP)** | `01 0B` | 1 (`A`) | `A` | `kPa` | `28 – 35 kPa` (Warm Idle) |
| **Barometric Pressure (BARO)** | `01 33` | 1 (`A`) | `A` | `kPa` | `~101 kPa` (Sea Level) |
| **Calculated Engine Load** | `01 04` | 1 (`A`) | `(100 / 255) * A` | `%` | `15 – 25%` (Idle) |
| **Throttle Position (TPS)** | `01 11` | 1 (`A`) | `(100 / 255) * A` | `%` | `12 – 95%` |
| **Mass Air Flow (MAF)** | `01 10` | 2 (`A, B`) | `(256 * A + B) / 100` | `g/s` | `2.0 – 25.0 g/s` |
| **Short-Term Fuel Trim (B1)** | `01 06` | 1 (`A`) | `(100 / 128) * A - 100` | `%` | `-10% to +10%` |
| **Long-Term Fuel Trim (B1)** | `01 07` | 1 (`A`) | `(100 / 128) * A - 100` | `%` | `-10% to +10%` |
| **O2 Sensor B1S1 (Upstream)** | `01 14` | 2 (`A, B`) | `A / 200` | `V` | `0.10 – 0.90 V` (Oscillating) |
| **O2 Sensor B1S2 (Downstream)** | `01 15` | 2 (`A, B`) | `A / 200` | `V` | `0.60 – 0.75 V` (Steady) |

---

## 3. SAE J2012 DTC Decoding & Online Interpretation Architecture

When the user triggers **Read ECU Faults (`Mode 03 / Mode 07`)** or enters a 4-character hex string (such as `C100`) into the decoder bar, `decodeHexToDtc()` parses the two bytes `[A, B]` per [SAE J2012 / ISO 15031-6](https://www.csselectronics.com/pages/obd-trouble-code-dtc-lookup-converter-tool):

- **Bits `A7..A6` (System Prefix)**: `00` = `P` (Powertrain), `01` = `C` (Chassis), `10` = `B` (Body), `11` = `U` (Network).
- **Bits `A5..A4` (Code Type)**: `0` = SAE Standardized, `1` = Manufacturer Specific, `2/3` = Subsystem/Joint defined.
- **Bits `A3..A0` (Subsystem)**: Fuel/Air (`1–2`), Ignition/Misfire (`3`), Auxiliary Emissions (`4`), Speed/Idle (`5`), ECU (`6`), Transmission (`7–8`).
- **Byte `B` (`B7..B0`)**: 2-digit hexadecimal fault index (`00–FF`).

### Verified Clickable DTC Reference URLs Embedded in App
- `P0171` (System Too Lean Bank 1): [AUTODOC P0171](https://club.autodoc.co.uk/obd-codes/p0171) • [CarAdvise](https://caradvise.com/simple-guide-to-the-check-engine-light/)
- `P0302` (Cylinder 2 Misfire): [AUTODOC P0302](https://club.autodoc.co.uk/obd-codes/p0302) • [Pico Auto Compression](https://www.picoauto.com/library/picoscope-automotive-training/training/compression-testing-by-pico-technology)
- `P0420` (Catalyst System Efficiency Below Threshold): [AUTODOC P0420](https://club.autodoc.co.uk/obd-codes/p0420) • [Snap-on DTCs](https://www.snapon.com/EN/US/Diagnostics/OffNav/Diagnostic-Trouble-Codes)
- `P0300` (Random/Multiple Cylinder Misfire): [AUTODOC P0300](https://club.autodoc.co.uk/obd-codes/p0300)
- `P0118` (ECT Sensor Circuit High): [AUTODOC P0118](https://club.autodoc.co.uk/obd-codes/p0118)
- `P0562` (System Voltage Low): [AUTODOC P0562](https://club.autodoc.co.uk/obd-codes/p0562)
- `P0113` (IAT Sensor Circuit High): [AUTODOC P0113](https://club.autodoc.co.uk/obd-codes/p0113)
- `P0135` (O2 Heater Circuit Bank 1 Sensor 1): [AUTODOC P0135](https://club.autodoc.co.uk/obd-codes/p0135)
- `P0101` (MAF Sensor Range/Performance): [AUTODOC P0101](https://club.autodoc.co.uk/obd-codes/p0101)
- `P0122` (TPS Circuit Low Input): [AUTODOC P0122](https://club.autodoc.co.uk/obd-codes/p0122)
- `P0174` (System Too Lean Bank 2): [AUTODOC P0174](https://club.autodoc.co.uk/obd-codes/p0174)
- `U0100` (Lost Communication With ECM/PCM): [AUTODOC U0100](https://club.autodoc.co.uk/obd-codes/u0100) • [CSS Electronics](https://www.csselectronics.com/pages/obd-trouble-code-dtc-lookup-converter-tool)

---

## 4. Android 10 Native Bridge & Build Instructions

1. **Android Studio Build**:
   - Extract `motoobd_android10_github_repo.zip` and open the folder in Android Studio.
   - Confirm `compileSdk 29` and `targetSdk 29` in `app/build.gradle`.
   - Connect your Android 10 phone with USB Debugging enabled and run `./gradlew installDebug`.
2. **Connecting Live ELM327 Hardware from JavaScript**:
   - When running inside the native Android 10 WebView, `window.AndroidObdBridge.connectPairedObdDevice("OBDII")` opens the RFCOMM socket (`00001101-0000-1000-8000-00805F9B34FB`), runs `ATZ`, `ATE0`, `ATL0`, `ATSP0`, and exposes `window.AndroidObdBridge.sendElmCommand(hexCmd)` for synchronous PID polling.
