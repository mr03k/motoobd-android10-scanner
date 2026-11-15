# Engineering handoff — 2.4.0

## Architecture

Activity owns a bundled offline WebView and serial scheduled worker. JavaScript reads cached telemetry and connection state; long operations return through the async bridge. Bluetooth I/O uses bounded prompt-delimited transactions. Leaving the Activity closes the session; it does not continue monitoring in the background. WebView navigation is restricted to its bundled page; HTTPS references open outside the bridged WebView.

## Bridge contract

`requestOperation(requestId, operation, argument)` reports `window.onObdResult(id, {ok, data, error})`. Operations: `listDevices`, `connect` (paired MAC), `disconnect`, `scanDtc`, `clearDtc`. Cache getters: `getConnectionStateJson()` and `getLiveTelemetryJson()`.

DTC results preserve mode labels, raw replies, per-mode errors and scan completeness. A partial/failed scan must never be presented as a confirmed fault-free ECU. Clear success requires a positive Mode 04 response; rescan afterward. Telemetry is null until actually read and after expiry/disconnect. There is no mechanical compression API: compatibility methods explicitly return unsupported.

Keep the web and bundled HTML copies identical. There are no runtime CDN dependencies. Test UI with a bridge fixture; do not mistake fixture success for physical Bluetooth/ECU validation.

## Follow-up device acceptance

- Android 10: paired Classic adapter, Bluetooth off/no device/no ECU, successful connect, live values, fault scan, background/return, reconnect.
- Android 12+: deny/grant Nearby devices; revoke permission during a session; reconnect.
- BLE: supported service, notification subscription, missing descriptor, disconnect mid-command.
- ECU: slow protocol auto-search, NO DATA/negative reply, multiple responders, permanent-mode unsupported, clear refusal and positive acknowledgement.
- Accessibility: TalkBack order/announcements, large system fonts, on-device WebView version, keyboard/Back and landscape.
- Compare PID values and DTCs with a trusted scanner before relying on readings.

Use `docs/REVIEW.md` for executed check evidence. Old loop-test claims are superseded; the original referenced test runner was absent from the repository.

## 2.5 bridge additions

`getLiveTelemetryJson()` now returns `parameters` (id, ecu, pid, name, unit, value or null, raw, status, supported, decoded, updatedAt, staleAfterMs, error) and `capabilities` (complete, supportedCount, errors), preserving compatibility dashboard fields.

`requestOperation(id, "readDiagnostics", "")` returns readiness[], freezeFrames[], vehicleInfo[], errors[], rawResponses[], updatedAt and complete. Each record is source-labeled; diagnostics must not be reconstructed from current live readings. Mode01 metadata/capability discovery is reset on reconnect, and queued snapshot work is bound to its session. UI ignores callbacks from replaced sessions.

## 2.5.1 bridge additions

`readMonitorTests` returns tests[], errors[], rawResponses[], updatedAt, complete and protocol information. Raw Mode06 values/limits are not OEM-scaled; legacy CID limit direction and CAN UASID are retained. Exact duplicate grouping occurs only in the UI/report and keeps occurrences. Clear invalidates prior monitor test snapshots.

Live parameter rows now include lastSuccessAt, lastAttemptAt and failureRaw. A retained last-known value after a failed attempt is explicitly not current. DTC scan results include readinessRaw/readiness and protocol/adapter identity as well as mode03/mode07/mode0A raw replies. These are essential for the unresolved Torque fault-list discrepancy on the user's Pride132.
