const { test, expect } = require("@playwright/test");
const fs = require("fs");
const html = () => fs.readFileSync("app/src/main/assets/index.html", "utf8");
async function load(page, options = {}) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.evaluate((options) => {
    window.__mock = { connected: false, calls: [], ...options };
    if (options.browserOnly) return;
    window.AndroidObdBridge = {
      getConnectionStateJson() {
        return JSON.stringify({
          connected: window.__mock.connected,
          state: window.__mock.connected ? "connected" : "disconnected",
          device: "Test ELM",
          error: window.__mock.linkError || "",
        });
      },
      getLiveTelemetryJson() {
        return JSON.stringify(
          window.__mock.telemetry || {
            rpm: 1450,
            speed: 0,
            coolant: 86,
            voltage: 14.1,
            updatedAt: Date.now(),
          },
        );
      },
      requestOperation(id, operation, argument) {
        const m = window.__mock;
        m.calls.push({ operation, argument });
        let response = { ok: true };
        if (operation === "listDevices")
          response = m.permissionError
            ? {
                ok: false,
                error:
                  "Allow Nearby devices permission in Android Settings, then try again.",
              }
            : {
                ok: true,
                data: [
                  {
                    name: "Test ELM",
                    address: "00:11:22:33:44:55",
                    type: "Classic",
                  },
                ],
              };
        if (operation === "connect") {
          m.connected = true;
          response.data = "CONNECTED:Test ELM";
        }
        if (operation === "disconnect") {
          m.connected = false;
          response.data = "DISCONNECTED";
        }
        if (operation === "scanDtc")
          response = m.scanFailure
            ? { ok: false, error: "Adapter reply timed out" }
            : {
                ok: true,
                data: m.scan || {
                  complete: true,
                  codes: [
                    { code: "P0300", mode: "STORED" },
                    { code: "P0171", mode: "PENDING" },
                    { code: "P0420", mode: "PERMANENT" },
                  ],
                  errors: [],
                },
              };
        if (operation === "clearDtc")
          response.data = m.clearFailure
            ? { success: false, error: "ECU did not confirm clearing codes." }
            : { success: true, response: "44" };
        if (operation === "readDiagnostics")
          response = m.diagnosticsFailure
            ? { ok: false, error: "Adapter disconnected during diagnostics" }
            : { ok: true, data: m.diagnostics || {} };
        if (operation === "readMonitorTests")
          response = m.monitorFailure
            ? { ok: false, error: "Monitor test read interrupted" }
            : { ok: true, data: m.monitorTests || {} };
        setTimeout(
          () => window.onObdResult(id, response),
          operation === "readDiagnostics" ? m.diagnosticDelay || 10 : 10,
        );
      },
    };
  }, options);
  await page.setContent(html(), { waitUntil: "domcontentloaded" });
  return errors;
}
async function connect(page) {
  await page.locator("[data-go=connect]").first().click();
  await page.locator(".device").click();
  await expect(page.locator("#statusPill")).toHaveText("Connected");
}
async function faults(page) {
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#scanButton").click();
}

test("bundled and preview files remain identical with no runtime CDN dependencies", () => {
  expect(html()).toBe(fs.readFileSync("web/obd2_diagnostic_app.html", "utf8"));
  expect(html()).not.toMatch(/<(?:script|link)[^>]+(?:src|href)=["']https?:/i);
  expect(html()).not.toMatch(/user-scalable=no|maximum-scale=1/);
});

test("offline disconnected and labeled demo flow, units and keyboard dialog", async ({
  page,
}, testInfo) => {
  const errors = await load(page, { browserOnly: true });
  await expect(page.locator("#value-rpm")).toHaveText("—");
  await expect(page.locator("#scanButton")).toBeDisabled();
  await page.locator("[data-go=connect]").first().click();
  await expect(page.locator("#deviceList")).toContainText(
    "Android app required",
  );
  await page.locator("#demoButton").click();
  await expect(page.locator("#demoNotice")).toBeVisible();
  await expect(page.locator("#value-rpm")).not.toHaveText("—");
  await page.locator("#unitsButton").click();
  await expect(page.locator("#unit-speed")).toHaveText("mph");
  await faults(page);
  await expect(page.locator(".dtc-row")).toHaveCount(2);
  await expect(page.locator("#clearButton")).toBeDisabled();
  await page.locator(".dtc-row").first().click();
  await expect(page.getByRole("dialog")).toContainText(
    "No freeze-frame data was read",
  );
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(page.locator(".dtc-row").first()).toBeFocused();
  await page.screenshot({
    path: testInfo.outputPath("demo-faults-phone.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("permission denial is actionable and retry remains available", async ({
  page,
}) => {
  const errors = await load(page, { permissionError: true });
  await page.locator("[data-go=connect]").first().click();
  await expect(page.locator("#connectionMessage")).toContainText(
    "Nearby devices",
  );
  await expect(page.locator("#refreshButton")).toBeEnabled();
  expect(errors).toEqual([]);
});

test("native measured values, stale expiry, disconnect and reconnect", async ({
  page,
}) => {
  const errors = await load(page);
  await connect(page);
  await expect(page.locator("#value-rpm")).toHaveText("1,450");
  await expect(page.locator("#value-map")).toHaveText("—");
  await page.evaluate(
    () =>
      (window.__mock.telemetry = {
        rpm: 2000,
        speed: 0,
        updatedAt: Date.now() - 20000,
      }),
  );
  await expect(page.locator("#value-rpm")).toHaveText("—");
  await page.evaluate(() => {
    window.__mock.connected = false;
    window.__mock.linkError = "Adapter disconnected";
  });
  await expect(page.locator("#statusPill")).toHaveText("Disconnected");
  await expect(page.locator("#connectionNotice")).toBeVisible();
  await connect(page);
  await expect(page.locator("#value-rpm")).toHaveText("—");
  expect(errors).toEqual([]);
});

test("DTC modes, filter, confirmation cancellation and ECU clear refusal", async ({
  page,
}) => {
  const errors = await load(page, { clearFailure: true });
  await connect(page);
  await faults(page);
  await expect(page.locator(".dtc-row")).toHaveCount(3);
  await page.locator("[data-filter=PERMANENT]").click();
  await expect(page.locator(".dtc-row")).toHaveCount(1);
  await expect(page.locator(".dtc-row")).toContainText("P0420");
  await page.locator("#clearButton").click();
  await page.getByRole("button", { name: "Keep codes", exact: true }).click();
  expect(
    await page.evaluate(
      () =>
        window.__mock.calls.filter((c) => c.operation === "clearDtc").length,
    ),
  ).toBe(0);
  await page.locator("#clearButton").click();
  await page.getByRole("button", { name: "Clear codes", exact: true }).click();
  await expect(page.locator("#scanMessage")).toContainText("did not confirm");
  await expect(page.locator(".dtc-row")).toContainText("P0420");
  expect(errors).toEqual([]);
});

test("partial empty scan never claims a complete fault-free result", async ({
  page,
}) => {
  const errors = await load(page, {
    scan: { complete: false, codes: [], errors: ["Mode 0A: NO DATA"] },
  });
  await connect(page);
  await faults(page);
  await expect(page.locator("#scanMessage")).toContainText(
    /incomplete|partial|not complete/i,
  );
  await expect(page.locator("#dtcList")).not.toContainText("No codes reported");
  expect(errors).toEqual([]);
});

test("scan failure retains and labels previous results", async ({ page }) => {
  const errors = await load(page);
  await connect(page);
  await faults(page);
  await expect(page.locator(".dtc-row")).toHaveCount(3);
  await page.evaluate(() => (window.__mock.scanFailure = true));
  await page.locator("#scanButton").click();
  await expect(page.locator("#scanHeading")).toContainText(
    /could not|incomplete|failed/i,
  );
  await expect(page.locator(".dtc-row")).toHaveCount(3);
  await page.locator("#faultSearch").fill("P0300");
  await expect(page.locator("#scanHeading")).toContainText(
    /could not|incomplete|failed/i,
  );
  expect(errors).toEqual([]);
});

for (const width of [320, 412, 768, 1280])
  test(`layout remains usable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 915 });
    const errors = await load(page, { browserOnly: true });
    await page.locator("[data-go=connect]").first().click();
    await page.locator("#demoButton").click();
    for (const tab of ["live", "faults", "connect"]) {
      await page.locator(`nav [data-go=${tab}]`).click();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      if (width === 412 || width === 768)
        await page.screenshot({
          path: testInfo.outputPath(`${tab}-${width}.png`),
          fullPage: true,
        });
    }
    expect(errors).toEqual([]);
  });

test("positive clear acknowledgement requires a follow-up scan and preserves permanent-code warning", async ({
  page,
}) => {
  await load(page);
  await connect(page);
  await faults(page);
  await page.locator("#clearButton").click();
  await page.getByRole("button", { name: "Clear codes", exact: true }).click();
  await expect(page.locator("#scanMessage")).toContainText("Read codes again");
  await expect(page.locator("#scanMessage")).toContainText("permanent");
  await expect(page.locator(".dtc-row")).toHaveCount(0);
  expect(
    await page.evaluate(
      () =>
        window.__mock.calls.filter((c) => c.operation === "clearDtc").length,
    ),
  ).toBe(1);
});

test("report preserves previous adapter and scan context after physical disconnection", async ({
  page,
}) => {
  await load(page);
  await connect(page);
  await faults(page);
  await expect(page.locator(".dtc-row")).toHaveCount(3);
  await page.evaluate(() => (window.__mock.connected = false));
  await expect(page.locator("#statusPill")).toHaveText("Disconnected");
  await page
    .locator("#page-faults")
    .getByRole("button", { name: "Copy report", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/Adapter: Test ELM/);
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/P0420 PERMANENT/);
});

const expandedTelemetry = () => ({
  rpm: 832,
  speed: 0,
  voltage: 14,
  coolant: 74,
  updatedAt: Date.now(),
  capabilities: { complete: true, supportedCount: 4, errors: [] },
  parameters: [
    {
      id: "01-0C",
      pid: "0C",
      name: "Engine speed",
      value: 832,
      unit: "rpm",
      raw: "0D 00",
      status: "fresh",
      updatedAt: Date.now(),
      supported: true,
      decoded: true,
    },
    {
      id: "01-10",
      pid: "10",
      name: "Mass air flow",
      value: null,
      unit: "g/s",
      raw: "",
      status: "unsupported",
      updatedAt: null,
      supported: false,
      decoded: true,
    },
    {
      id: "01-99",
      pid: "99",
      name: "PID 99",
      value: null,
      unit: "",
      raw: "A1 B2 C3",
      status: "fresh",
      updatedAt: Date.now(),
      supported: true,
      decoded: false,
    },
    {
      id: "01-05",
      pid: "05",
      name: "Coolant",
      value: 74,
      unit: "°C",
      raw: "72",
      status: "stale",
      updatedAt: Date.now() - 60000,
      supported: true,
      decoded: true,
    },
    {
      id: "01-04",
      pid: "04",
      name: "Calculated load",
      value: null,
      unit: "%",
      raw: "NO DATA",
      status: "read_failed",
      updatedAt: null,
      supported: true,
      decoded: true,
      error: "No valid response",
    },
  ],
});

test("parameter browser includes unknown raw values and distinguishes failure, stale and unsupported", async ({
  page,
}) => {
  const errors = await load(page, { telemetry: expandedTelemetry() });
  await connect(page);
  await expect(page.locator("#parameterList")).toContainText("A1 B2 C3");
  await expect(page.locator(".parameter-row[data-status=stale]")).toContainText(
    "Coolant",
  );
  await expect(
    page.locator(".parameter-row[data-status=read_failed]"),
  ).toContainText("Calculated load");
  await page.locator("#parameterSearch").fill("99");
  await expect(page.locator(".parameter-row")).toHaveCount(1);
  await page.locator("#parameterSearch").fill("");
  await page.locator("#parameterFilter").selectOption("unsupported");
  await expect(page.locator(".parameter-row")).toHaveCount(1);
  await expect(page.locator("#parameterList")).toContainText("Mass air flow");
  expect(errors).toEqual([]);
});

test("incomplete capability discovery is visible and does not invent support", async ({
  page,
}) => {
  const telemetry = expandedTelemetry();
  telemetry.capabilities = {
    complete: false,
    supportedCount: 4,
    errors: ["Mode 01 PID 20: NO DATA"],
  };
  const errors = await load(page, { telemetry });
  await connect(page);
  await expect(page.locator("#capabilitySummary")).toContainText(
    /incomplete|partial|not complete/i,
  );
  expect(errors).toEqual([]);
});

test("diagnostic operation failure restores retry and displays actionable error", async ({
  page,
}) => {
  const errors = await load(page, { diagnosticsFailure: true });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  await expect(page.locator("#diagnosticsMessage")).toContainText(
    "Adapter disconnected during diagnostics",
  );
  await expect(page.locator("#diagnosticsButton")).toBeEnabled();
  expect(errors).toEqual([]);
});

const snapshotFixture = () => ({
  complete: true,
  updatedAt: Date.now(),
  errors: [],
  readiness: [
    {
      ecu: "7E8",
      mil: true,
      dtcCount: 1,
      ignitionType: "Spark ignition",
      status: "fresh",
      raw: "81 07 E5 00",
      monitors: [
        { name: "Misfire monitoring", supported: true, complete: true },
        { name: "Catalyst monitoring", supported: true, complete: false },
        { name: "Evaporative system", supported: false, complete: null },
      ],
    },
  ],
  vehicleInfo: [
    {
      ecu: "7E8",
      pid: "02",
      name: "Vehicle identification number",
      value: "TEST123",
      raw: "54 45 53 54",
      status: "fresh",
    },
  ],
  freezeFrames: [
    { frame: 0, ecu: "7E8", dtc: null, status: "no_frame", parameters: [] },
  ],
});

test("snapshot distinguishes readiness completion, unsupported monitors, vehicle info and absent freeze frame", async ({
  page,
}, testInfo) => {
  const errors = await load(page, { diagnostics: snapshotFixture() });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  await expect(page.locator("#diagnosticsResults")).toContainText("TEST123");
  await expect(page.locator("#diagnosticsResults")).toContainText("Incomplete");
  await expect(page.locator("#diagnosticsResults")).toContainText(
    "Unsupported",
  );
  await expect(page.locator("#diagnosticsResults")).toContainText(
    "No freeze frame stored",
  );
  await page.locator("#diagnosticsResults").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: testInfo.outputPath("diagnostic-snapshot.png"),
  });
  expect(errors).toEqual([]);
});

test("clearing invalidates previously read readiness and freeze-frame snapshot", async ({
  page,
}) => {
  await load(page, { diagnostics: snapshotFixture() });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  await expect(page.locator("#diagnosticsResults")).toContainText("TEST123");
  await page.locator("#clearButton").click();
  await page.getByRole("button", { name: "Clear codes", exact: true }).click();
  await expect(page.locator("#diagnosticsResults")).not.toContainText(
    "TEST123",
  );
});

test("freeze-frame retains stored measurement and exports raw bytes without using current RPM", async ({
  page,
}) => {
  const diagnostics = snapshotFixture();
  diagnostics.freezeFrames = [
    {
      frame: 0,
      ecu: "7E8",
      dtc: "P0300",
      status: "fresh",
      parameters: [
        {
          id: "7E8:0C",
          ecu: "7E8",
          pid: "0C",
          name: "Engine speed",
          value: 2345,
          unit: "rpm",
          raw: "24 A4",
          status: "fresh",
          updatedAt: Date.now(),
          supported: true,
          decoded: true,
        },
      ],
    },
  ];
  await load(page, { diagnostics });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  await expect(page.locator("#diagnosticsResults")).toContainText("P0300");
  await expect(page.locator("#diagnosticsResults")).toContainText(/2,?345/);
  await page
    .locator("#page-faults")
    .getByRole("button", { name: "Copy report", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/24 A4/);
});

test("late diagnostic response cannot restore snapshot after user disconnects", async ({
  page,
}) => {
  await load(page, { diagnostics: snapshotFixture(), diagnosticDelay: 1500 });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  await page.locator("nav [data-go=connect]").click();
  await page.locator("#disconnectButton").click();
  await expect(page.locator("#statusPill")).toHaveText("Disconnected");
  await page.waitForTimeout(1700);
  await page.locator("nav [data-go=faults]").click();
  await expect(page.locator("#diagnosticsResults")).not.toContainText(
    "TEST123",
  );
});

test("slow polling respects native per-parameter freshness window", async ({
  page,
}) => {
  const telemetry = expandedTelemetry();
  telemetry.parameters = [
    {
      id: "7E8:5C",
      ecu: "7E8",
      pid: "5C",
      name: "Oil temperature",
      value: 92,
      unit: "°C",
      raw: "84",
      supported: true,
      decoded: true,
      status: "fresh",
      updatedAt: Date.now() - 20000,
      staleAfterMs: 60000,
    },
  ];
  await load(page, { telemetry });
  await connect(page);
  await expect(page.locator(".parameter-row[data-status=fresh]")).toContainText(
    "Oil temperature",
  );
});

test("snapshot raw disclosures stay open during live refresh", async ({
  page,
}) => {
  await load(page, { diagnostics: snapshotFixture() });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#diagnosticsButton").click();
  const disclosure = page.locator("#diagnosticsResults details").first();
  await disclosure.locator("summary").click();
  await page.waitForTimeout(1200);
  await expect(disclosure).toHaveAttribute("open", "");
});

const torqueMonitorFixture = () => ({
  complete: true,
  updatedAt: Date.now(),
  errors: [],
  tests: [
    {
      ecu: "unidentified",
      protocol: "legacy",
      tid: "05",
      cid: "09",
      mid: null,
      scalingId: null,
      valueRaw: 32768,
      minimumRaw: null,
      maximumRaw: 20768,
      raw: "05 09 80 00 51 20",
      comparison: "unverified",
      unit: "raw counts",
      interpretation: "OEM conversion unverified.",
    },
    {
      ecu: "unidentified",
      protocol: "legacy",
      tid: "05",
      cid: "09",
      mid: null,
      scalingId: null,
      valueRaw: 32768,
      minimumRaw: null,
      maximumRaw: 20768,
      raw: "05 09 80 00 51 20",
      comparison: "unverified",
      unit: "raw counts",
      interpretation: "OEM conversion unverified.",
    },
    {
      ecu: "unidentified",
      protocol: "legacy",
      tid: "05",
      cid: "0A",
      mid: null,
      scalingId: null,
      valueRaw: 32768,
      minimumRaw: 31168,
      maximumRaw: null,
      raw: "05 8A 80 00 79 C0",
      comparison: "unverified",
      unit: "raw counts",
      interpretation: "OEM conversion unverified.",
    },
  ],
  rawResponses: [
    { request: "0605", response: "46 05 09 80 00 51 20\r46 05 8A 80 00 79 C0" },
  ],
});

test("Mode06 retains Torque-style raw values and duplicate occurrences without claiming an OEM diagnosis", async ({
  page,
}) => {
  const errors = await load(page, { monitorTests: torqueMonitorFixture() });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#monitorTestsButton").click();
  await expect(page.locator(".monitor-test-row")).toHaveCount(2);
  await expect(page.locator("#monitorTestsResults")).toContainText(/32,?768/);
  await expect(page.locator("#monitorTestsResults")).toContainText(/20,?768/);
  await expect(page.locator("#monitorTestsResults")).toContainText(
    /unverified/i,
  );
  await expect(page.locator("#monitorTestsResults")).not.toContainText(
    "Rich to Lean",
  );
  await page
    .locator("#page-faults")
    .getByRole("button", { name: "Copy report", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/0605/);
  expect(errors).toEqual([]);
});

test("Mode06 conflicting same-ID same-limit records remain visible", async ({
  page,
}) => {
  const monitorTests = torqueMonitorFixture();
  monitorTests.tests[1] = {
    ...monitorTests.tests[1],
    valueRaw: 123,
    raw: "05 09 00 7B 51 20",
  };
  await load(page, { monitorTests });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#monitorTestsButton").click();
  await expect(page.locator(".monitor-test-row")).toHaveCount(3);
  await expect(
    page.locator(".monitor-test-row[data-conflicting=true]"),
  ).toHaveCount(2);
});

test("Mode06 failure is actionable and leaves retry enabled", async ({
  page,
}) => {
  await load(page, { monitorFailure: true });
  await connect(page);
  await page.locator("nav [data-go=faults]").click();
  await page.locator("#monitorTestsButton").click();
  await expect(page.locator("#monitorTestsMessage")).toContainText(
    "Monitor test read interrupted",
  );
  await expect(page.locator("#monitorTestsButton")).toBeEnabled();
});

test("DTC raw evidence retains empty stored, pending errors and unsupported permanent replies", async ({
  page,
}) => {
  await load(page, {
    scan: {
      complete: false,
      codes: [],
      errors: ["Mode 0A unsupported"],
      mode03: "43 00 00 00 00 00 00",
      mode07: "NO DATA",
      mode0A: "7F 0A 11",
      protocol: "ISO 9141-2",
      readiness: { raw: "41 01 00 37 65 65" },
    },
  });
  await connect(page);
  await faults(page);
  await expect(page.locator("#dtcEvidence")).toContainText(
    "43 00 00 00 00 00 00",
  );
  await expect(page.locator("#dtcEvidence")).toContainText("7F 0A 11");
  await page
    .locator("#page-faults")
    .getByRole("button", { name: "Copy report", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/43 00 00 00 00 00 00/);
});

test("failed latest PID read preserves last-known value with separate failure evidence and times", async ({
  page,
}) => {
  const telemetry = expandedTelemetry();
  const success = Date.now() - 25000,
    attempt = Date.now();
  telemetry.parameters = [
    {
      id: "unidentified:0F",
      ecu: "unidentified",
      pid: "0F",
      name: "Intake air temperature",
      value: 30,
      unit: "°C",
      raw: "46",
      status: "read_failed",
      supported: true,
      decoded: true,
      updatedAt: success,
      lastSuccessAt: success,
      lastAttemptAt: attempt,
      failureRaw: "NO DATA",
      error: "NO DATA",
    },
  ];
  await load(page, { telemetry });
  await connect(page);
  await expect(page.locator("#parameterList")).toContainText(
    /Last known|last known/,
  );
  await expect(page.locator("#parameterList")).toContainText("NO DATA");
  await page.locator("#reportButton").click();
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/lastSuccessfulRead=/);
  await expect(
    page.getByRole("textbox", { name: "Diagnostic report text" }),
  ).toHaveValue(/failureRaw=NO DATA/);
});
