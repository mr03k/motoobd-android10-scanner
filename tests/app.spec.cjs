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
        setTimeout(() => window.onObdResult(id, response), 10);
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
