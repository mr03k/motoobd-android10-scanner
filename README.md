# MotoOBD Telemetry Pro — Android 10 Bluetooth OBD-II ECU Scanner & Live Dashboard

A self-contained **OBD-II Bluetooth ECU Diagnostic Scanner & Live Telemetry Cockpit** engineered for **Android 10 (API Level 29)**. It supports both **Bluetooth Classic SPP (RFCOMM)** and **Web Bluetooth (BLE 4.0+)** ELM327 adapters to display real-time ECU connection status, read and clear Diagnostic Trouble Codes (DTCs) with clickable online interpretations, and monitor live engine telemetry and sensor health.

---

## 1. Repository Structure

```text
motoobd-android10-scanner/
├── README.md                          # Project overview, setup & Android 10 usage guide
├── HANDOFF.md                         # Engineering architecture, PID/DTC specs & handoff guide
├── push_to_github.ps1                 # Windows PowerShell one-click GitHub push script
├── push_to_github.sh                  # Linux/macOS Bash one-click GitHub push script
├── web/
│   └── obd2_diagnostic_app.html       # Self-contained HTML5/JS/Tailwind/Chart.js Web App
└── app/                               # Native Android 10 (API 29) Kotlin WebView + SPP Host
    ├── build.gradle                   # Android Gradle config (compileSdk 29 / targetSdk 29)
    └── src/main/
        ├── AndroidManifest.xml        # Android 10 Bluetooth & Fine Location permissions
        ├── assets/index.html          # Embedded MotoOBD Telemetry Pro cockpit UI
        └── java/com/motoobd/scanner/
            └── MainActivity.kt        # Native RFCOMM BluetoothSocket (00001101-...) bridge
```

---

## 2. Core Features & Grounded Standards

1. **Bluetooth OBD-II & ECU Link State Machine**:
   - **ELM327 Initialization Sequence**: Executes `ATZ` (Reset), `ATE0` (Echo Off), `ATL0` (Linefeeds Off), and `ATSP0` (Automatic Protocol Detection — defaults to `ISO 15765-4 CAN 11-bit 500 kbaud` on ECU address `0x7E8`) per [BlueDriver ELM327 Protocol Notes](https://github.com/sjalkote/bluedriver-reverse-engineering) and [Zbotic ELM327 AT Command Guide](https://zbotic.in/obd-ii-elm327-read-car-diagnostic-codes-with-arduino/).
   - **Dual Transport Support on Android 10**:
     - **Web Bluetooth BLE**: Connects directly in Chrome on Android 10 to BLE 4.0+ adapters via GATT service UUIDs `0000fff0-0000-1000-8000-00805f9b34fb` and `0000ffe0-0000-1000-8000-00805f9b34fb`.
     - **Classic Bluetooth SPP (RFCOMM)**: Connects via `MainActivity.kt` (`AndroidObdBridge`) using standard Serial Port Profile UUID `00001101-0000-1000-8000-00805F9B34FB`.

2. **Live Dashboard ([SAE J1979 Mode 01 PIDs](https://en.wikipedia.org/wiki/OBD-II_PIDs))**:
   - **Vehicle Speed (`PID 010D`)**: Radial gauge + peak tracker (`A` km/h, `0–255 km/h`, with Metric/Imperial toggle).
   - **Engine Speed (`PID 010C`)**: Radial tachometer + crankshaft stability indicator (`(256*A + B) / 4` RPM).
   - **Control Module / Battery Voltage (`PID 0142` & `ATRV`)**: Live alternator charging check (`(256*A + B) / 1000` V) and minimum cranking sag tracker.
   - **Water / Engine Coolant Temperature (`PID 0105`)**: Live thermistor readout (`A - 40` °C) and closed-loop thermostat status.
   - **Intake Air Temperature (`PID 010F`)**: Live intake temperature (`A - 40` °C) paired with Mass Air Flow (`PID 0110`, `(256*A + B) / 100` g/s).
   - **Compression & Manifold Vacuum (`PID 010B` & `PID 0133`)**: Real-time Intake Manifold Absolute Pressure (`A` kPa), Barometric Pressure (`A` kPa), calculated manifold vacuum (`MAP - Baro`), and 4-cylinder relative compression balance.
   - **Sensors Health Overview**: Real-time validation across 8 primary engine management sensors and `PID 0101` readiness monitors.

3. **Fault Code Reader, Mode 04 Reset & Clickable Online Interpretations**:
   - **Read Faults (`Mode 03` Stored / `Mode 07` Pending / `Mode 0A` Permanent)**: Decodes 2-byte CAN trouble frames using [SAE J2012 bitwise rules](https://www.csselectronics.com/pages/obd-trouble-code-dtc-lookup-converter-tool) (`00=P`, `01=C`, `10=B`, `11=U`).
   - **Clear Faults (`Mode 04`)**: Includes an in-app confirmation modal warning that `Mode 04` extinguishes the Check Engine Light (MIL), erases `Mode 02` freeze frames, and resets `PID 0101` readiness monitors.
   - **Clickable Fault Cards**: Tapping any fault (`P0171`, `P0302`, `P0420`, `P0300`, `P0118`, `P0562`, `P0113`, `P0135`, `P0101`, `P0122`, `P0174`, `U0100`, or any custom searched code) opens a diagnostic drawer displaying the ECU trigger threshold, `Mode 02` Freeze Frame snapshot, common symptoms, probable causes, repair steps, and direct links to [AUTODOC OBD-II Club](https://club.autodoc.co.uk/obd-codes/p0171), [CSS Electronics](https://www.csselectronics.com/pages/obd-trouble-code-dtc-lookup-converter-tool), [CarAdvise](https://caradvise.com/simple-guide-to-the-check-engine-light/), and [TroubleshootYourVehicle](https://troubleshootyourvehicle.com/tools/obd-ii-error-code-lookup).

4. **Electronic Relative Compression & Vacuum Analyzer**:
   - Evaluates cylinder-to-cylinder mechanical compression balance without removing spark plugs by measuring starter cranking RPM drop (`PID 010C`), battery voltage sag (`PID 0142`), and intake manifold vacuum pulses (`PID 010B`) per [Pico Technology Compression Testing](https://www.picoauto.com/library/picoscope-automotive-training/training/compression-testing-by-pico-technology) and [Autoditex Relative Compression Analysis](https://autoditex.com/page/relative-compression-test-67-1.html).

---

## 3. Running on Android 10 (API Level 29)

Per [Android Developers Bluetooth Permissions Documentation](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), Android 10 requires:
1. **Manifest Permissions**: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (upgraded from coarse location in API 29), and `ACCESS_BACKGROUND_LOCATION` if scanning in background services.
2. **Hardware GPS Toggle**: Android 10 requires the phone's system **Location (GPS) switch** to be turned **ON** during Bluetooth discovery/scanning.
3. **Pairing the OBD-II Reader**:
   - Plug the ELM327 adapter into the vehicle's 16-pin SAE J1962 port and turn the ignition key to **ON / RUN**.
   - Open **Android 10 Settings → Connected devices → Pair new device**, select `OBDII` or `ELM327`, and enter PIN `1234` or `0000`.
   - Open the app, tap **Configure BT**, and select **Scan BLE OBD** (for BLE 4.0+ adapters in Chrome) or **Handshake ECU** (via the native Android 10 Kotlin APK).
