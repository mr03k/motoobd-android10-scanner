# Review and refactor — 2026-11-15

Reviewed baseline: `1e8216f6ffc9b908d3d3a517c472a24343ef40a0`. Work used an Android developer agent, UI/UX designer/developer agent and independent correctness reviewer. This is a code review and browser UI validation, not a vehicle certification or on-device Android usability test.

## Findings addressed

| Priority | Original problem | Result |
| --- | --- | --- |
| High | Plausible default voltages/temperatures and invented cylinder compression health could appear as measured diagnosis. | Only measured readings are shown; missing/stale values are null. Unsupported mechanical diagnosis and fabricated freeze frames removed. |
| High | BLE enabled local notifications without subscribing to the remote CCCD. | Discover supported serial service, configure notifications/indications and await descriptor acknowledgement. |
| High | Connection success did not establish a valid ECU session. | ELM setup and Mode 01 ECU handshake must succeed before connected status. |
| High | Queued work and late callbacks could survive leaving the Activity or cancelling a session. | Session generation checks, foreground checks, resource closure and serialized work reject stale operations. |
| High | Timeout could leave delayed replies available to the next request. | Transport failures close the session; prompt-delimited, bounded exchanges fail explicitly. |
| High | DTC bytes from unrelated ECU replies/status text could be combined; count/PCI bytes could become codes. | Pure parser handles boundaries, counts, ISO-TP and rejects malformed replies; protocol-aware validation preserves scan errors. |
| High | Negative/partial ECU replies could look like an empty scan or successful clearing. | Per-mode errors/partial state retained; clearing requires a positive response and rejects mixed negative responses. |
| High | Bridged WebView could navigate to external content. | Bundled-page navigation only; network loads blocked and reference URLs handled externally. |
| Medium | UI depended on CDN CSS, Chart.js and remote fonts. | Bundled CSS/system fonts/canvas, no runtime requests. |
| Medium | Complex dashboard obscured setup and reported unsupported health conclusions. | Three flows: Live data, Fault codes, Connections; clear next steps, explicit demo and unavailable values. |
| Medium | Failed-scan label vanished when filtering prior results. | Regression test now enforces persistent failure/previous-result context. |
| Medium | Missing wrapper/PR CI; old generators overwrote app and publishing scripts deleted Git history. | Checksum-pinned Gradle wrapper, unit/lint/build/UI CI; destructive legacy helpers retired. |

## Executed verification

- 12 JVM parser tests passed: echoed/status responses, insufficient PID bytes, DTC categories, CAN count/padding, multiple responders, ISO-TP ordering, numbered frames, empty/negative and mixed replies, voltage parsing.
- 13 Playwright tests passed in local headless Chrome: offline/demo, permission failure, measured/null/stale readings, disconnect/reconnect, DTC modes/filter, partial/failing scans, clear cancel/refusal/positive acknowledgement, retained report context and 320/412/768/1280px layouts. No page errors in exercised flows.
- Phone/tablet screenshots captured and visually inspected. Baseline source preview also captured. These are browser renders of the bundled HTML, not Android WebView screenshots.
- Kotlin compilation and debug APK packaging passed. APK signature verified with Android apksigner (v2).
- Local sandbox reports Java `File.canWrite()` false for directories where writes succeed. The final local build uses `-x validateSigningDebug` with a generated debug keystore; packaging/signature are separately verified. No signing-task bypass is committed in CI.
- Lint analysis completed; local report generation is blocked by that same sandbox writability check. Intermediate findings contain no errors, with expected old-target/usesPermissionFlags warnings. Full lint remains a CI check, not claimed passed locally.
- HTML copies identical; no CDN resources; Git whitespace check passed. npm audit reported zero vulnerabilities for the pinned test tooling.

## Visual flow review

1. Disconnected Live data: clear setup action, no fictitious readings; phone and tablet layout checked.
2. Connections: paired-adapter path, permission/no-device errors and explicit browser-only/demo explanation checked with mocked bridge.
3. Live session: prominent RPM/speed, secondary measurements, trend and data freshness; demo banner always visible. Physical ECU sampling unverified.
4. Fault codes: stored/pending/permanent labels, filters, accessible reference dialog and scan completeness checked.
5. Clearing/report: confirmation and ECU refusal/acknowledgement checked with fixture. Reports retain adapter, sample/scan time and previous-session context.

## Remaining field checks

No physical Android device, ELM327 adapter or vehicle was attached. Real Classic/BLE throughput and compatibility, TalkBack/system font behavior, permission revocation, foreground/background timing, ECU protocol timings and hardware clear behavior still require the acceptance checklist in HANDOFF.md. Never clear real ECU codes solely to test this build. Debug signing differs from any existing installation unless it used this same key; Android may reject an in-place update. Preserve any app data before replacing an installation.

Target SDK stays at 34; compile SDK moves to 35 with a supported pinned AGP/Gradle pair. Store publication and signed production release are outside this change.
