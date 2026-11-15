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

## Capability and diagnostic expansion — 2.5.0

Mode 01 discovery follows support masks from 00 through E0, preserves per-responder ranges, and schedules every advertised non-bitmap PID. A conservative catalogue decodes common standard values; unknown payloads remain raw. Unsupported is only inferred from a successfully received bitmap range. Missing continuation pages remain incomplete coverage. CAN responder identity is retained; ambiguous legacy responses are labeled rather than combined into an invented ECU.

The parameter browser exposes per-PID sample time, raw bytes, support/decoder availability and fresh/stale/read-failed status. The freshness interval accounts for long polling sweeps. Broad coverage trades refresh rate for completeness; the dashboard is not a scope or a cylinder compression tester.

Read-only snapshot operations add MIL/DTC count and spark/compression-ignition readiness, frame 00 trigger code and supported frozen parameters, and Mode 09 advertised vehicle data. Unknown records are raw. Mode02 no-frame is explicitly different from unsupported and failed reads. Diagnostic operations have a time budget, return incomplete coverage when exhausted, and can be interrupted by disconnect. Clear invalidates the prior snapshot. Exports preserve snapshot times and raw replies, and can contain VIN or other vehicle identifiers.

References: ELM Electronics ELM327 datasheet (https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf), Au Electronics OBD simulator service examples (https://www.auelectronics.com/downloads/usermanual_simobd2can.pdf). Manufacturer-specific diagnostics and additional freeze-frame indices remain outside validated coverage.

The user's KONNWEI report establishes that the previous build received live values from their adapter; it does not validate this expanded build. No adapter is attached to this development environment. Field validation is still required for discovery coverage, multiple ECU attribution, broad-PID refresh timing, readiness and freeze-frame decoding on the user's ECU.

Executed expansion checks: 24 JVM tests passed (12 original parser + 6 capability discovery + 6 catalogue/readiness/information decoders). 22 Playwright tests passed, including per-PID freshness, unsupported/failed/raw states, stored snapshot export, no-frame handling, clear invalidation, late callback rejection and persistent raw disclosures. Phone/tablet renders inspected at 412/768px; responsive suite also covers320/1280px. Kotlin compiled, local debug packaging passed, bundled asset verified against source. Full lint/normal signing-validation remain CI checks because of the previously documented Windows File.canWrite sandbox issue.

## Torque comparison follow-up — 2.5.1

Field evidence: Pride132, model year1398. User reports Torque tested immediately after MotoOBD without clearing codes. Torque returned calibration identifier MXGITP50---- and legacy Mode06 TID/CID result rows, while MotoOBD's Mode09 support parser read `49 00 01 FC 00 00 00` as PIDs08–0E. The extra legacy message counter must be removed before reading the four-byte bitmap. MotoOBD had not implemented Mode06 at all.

Torque's screenshot lists10 DTCs: P0230,P0449,P0645,P0650,P1665,P3001,P3005,P3089,P3190,P3381. The current parser decodes synthetic wire equivalents of this list under both legacy pair and CAN count formats. This establishes parser coverage, not that the field discrepancy is resolved: actual raw Mode03/07 replies and readinessDTCcount from the same scan are needed. The update preserves that evidence and protocol metadata.

Torque explicitly warns its Mode06 units/scaling are unavailable. New monitor results therefore retain raw unsigned counts, raw records, source/protocol, limit direction and scaling identifier when provided. Raw comparison is distinguished from an OEM-validated result. No rich-to-lean description or repair recommendation is inferred from TID05 alone. Duplicate identical results retain occurrence counts; conflicting records stay visible.

Intermittent NO DATA affects fields that previously returned data. Bounded retries apply only after a completed prompt-delimited NO DATA reply on read-only commands; transport timeouts still close the session. Reports distinguish latest attempt failure from last-successful samples and age.

Final 2.5.1 local checks:36 JVM tests passed (including10 protocol field regressions and2 screenshot-code parser fixtures),27 Playwright tests passed. Phone/tablet Mode06 render inspected, Kotlin/debug APK build passed; final APK asset checked against source and signature verified. One additional reviewed UI timeout adjustment gives DTC scans75seconds to cover readiness and read-only retry work. CI performs full lint and normal build validation. Physical calibration retrieval/Mode06 output and the Torque DTC discrepancy remain field acceptance items.
