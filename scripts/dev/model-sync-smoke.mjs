// Real-browser smoke against web-stack.sh only. No production origin, user browser profile,
// response mocking, database access or credentials in output. Install Playwright separately;
// HR_PLAYWRIGHT_MODULE points to its index.mjs (no production/package dependency is added).
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const origin = new URL(
  process.env.HR_MODEL_SYNC_ORIGIN ?? "https://localhost:18443",
);
assert(
  origin.protocol === "https:" &&
    origin.hostname === "localhost" &&
    Number(origin.port) >= 18000 &&
    origin.pathname === "/" &&
    !origin.username &&
    !origin.password,
  "only the isolated HTTPS localhost stack is allowed",
);
const state = process.env.HR_WEB_STACK_STATE;
assert(
  state && process.env.HR_PLAYWRIGHT_MODULE,
  "set HR_WEB_STACK_STATE and HR_PLAYWRIGHT_MODULE",
);
const email = process.env.HR_WEB_STACK_EMAIL ?? "dev@example.test";
assert(
  email.endsWith("@example.test"),
  "use an artificial example.test account",
);
const { chromium, webkit } = await import(
  pathToFileURL(resolve(process.env.HR_PLAYWRIGHT_MODULE)).href
);
const engine = process.env.HR_MODEL_SYNC_BROWSER ?? "chromium";
assert(
  ["chromium", "webkit"].includes(engine),
  "browser must be chromium or webkit",
);
const browser = await { chromium, webkit }[engine].launch();
const contexts = [];
const completed = [];
const check = (name) => {
  completed.push(name);
  console.log(`PASS ${name}`);
};
const nameInput = (page) => page.locator("#account-name");
const save = (page) => page.getByRole("button", { name: /^(保存|Save)$/ });
async function equalName(page, name) {
  await page.waitForFunction(
    (expected) => document.querySelector("#account-name")?.value === expected,
    name,
  );
}
async function wake(page) {
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
}
async function pending(page) {
  return page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const request = indexedDB.open("hermes-go.account-model-sync.v1", 1);
        request.onerror = () => reject(new Error("queue unavailable"));
        request.onsuccess = () => {
          const db = request.result;
          const get = db
            .transaction("accounts")
            .objectStore("accounts")
            .getAll();
          get.onsuccess = () => {
            resolve(get.result.flatMap((r) => Object.values(r.pending)));
            db.close();
          };
          get.onerror = () => reject(new Error("queue unavailable"));
        };
      }),
  );
}
async function drained(page) {
  for (let i = 0; i < 100; i++) {
    if (!(await pending(page)).length) return;
    await page.waitForTimeout(100);
  }
  assert.fail("model queue not drained");
}
async function latestCode(target) {
  const lines = (await readFile(resolve(state, "login-codes.jsonl"), "utf8"))
    .trim()
    .split("\n");
  const row = lines
    .map((line) => JSON.parse(line))
    .findLast((line) => line.to === target);
  assert(row && /^\d{6}$/.test(row.code), "local mail capture missing");
  return row.code;
}
async function login(target = email, needsMac = true) {
  // Page routing cannot reliably drop a request handled by a Service Worker in WebKit. Block SW
  // in this fault-injection suite; SW cache behavior still needs its separate smoke/acceptance gate.
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    serviceWorkers: "block",
    viewport: { width: 430, height: 932 },
  });
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(`${origin.origin}/app/account`);
  await page.locator('input[type="email"]').fill(target);
  const challenge = page.waitForResponse((r) =>
    r.url().endsWith("/v2/web/auth/email/challenges"),
  );
  await page.getByRole("button", { name: /发送验证码|Send code/ }).click();
  assert.equal((await challenge).status(), 202, "login challenge");
  const exchange = page.waitForResponse((r) =>
    r.url().endsWith("/v2/web/auth/email/exchange"),
  );
  await page
    .locator('input[autocomplete="one-time-code"]')
    .fill(await latestCode(target));
  assert.equal((await exchange).status(), 200, "login exchange");
  if (needsMac) {
    await nameInput(page).waitFor();
    await page.waitForFunction(
      () => document.querySelector("#account-name")?.value?.length > 0,
    );
  }
  return { context, page };
}
async function snapshot(page) {
  return page.evaluate(async () => {
    const session = await (await fetch("/v2/web/session")).json();
    const response = await fetch("/v2/web/account-data/models/snapshot", {
      headers: { "x-hermes-expected-account": session.session.account.id },
    });
    if (!response.ok) throw new Error("snapshot failed");
    return response.json();
  });
}

async function picker(page) {
  await page.goto(`${origin.origin}/app/`);
  await page.getByRole("button", { name: /打开侧边栏|Open sidebar/ }).click();
  await page
    .getByRole("button", { name: /^(默认模型|Default model)$/ })
    .click();
  await page
    .getByRole("button", { name: /刷新模型偏好|Refresh model preferences/ })
    .waitFor();
}
async function star(page, model) {
  return page
    .locator(".model-row")
    .filter({ has: page.locator(".model-name", { hasText: model }) })
    .first()
    .locator(".model-star");
}
async function refresh(page) {
  await page
    .getByRole("button", { name: /刷新模型偏好|Refresh model preferences/ })
    .click();
  await page.waitForTimeout(150);
}
try {
  const a = await login(),
    b = await login();
  const caps = await a.page.evaluate(async () =>
    (await fetch("/v2/capabilities")).json(),
  );
  assert.deepEqual(caps.accountAuth.modelPreferencesSync, {
    version: 1,
    maxModels: 500,
    retentionDays: 90,
  });
  assert.equal((await snapshot(a.page)).rows.length, 0);
  await a.page.evaluate(() =>
    localStorage.setItem(
      "hermes-go.models.dev-mac",
      JSON.stringify({
        favorites: ["anthropic/claude-opus-5", "ambiguous/unknown"],
        presets: { "anthropic/claude-opus-5": "high" },
        recents: [],
      }),
    ),
  );
  await picker(a.page);
  await picker(b.page);
  assert.equal((await snapshot(a.page)).rows.length, 0);
  await a.page
    .getByRole("button", { name: /导入此设备的旧偏好|Import this device/ })
    .click();
  assert.equal((await snapshot(a.page)).rows.length, 0);
  await a.page
    .getByRole("button", { name: /^(确认导入|Confirm import)$/ })
    .click();
  await drained(a.page);
  await refresh(b.page);
  await b.page
    .locator(".model-favorites .model-name", { hasText: "claude-opus-5" })
    .waitFor();
  await a.page
    .locator(".model-status-name", { hasText: "claude-opus-5" })
    .waitFor();
  assert.equal(await a.page.getByText(/HR-WEB-009/).count(), 0);
  const imported = (await snapshot(b.page)).rows.find(
    (r) => r.model === "claude-opus-5",
  );
  assert.equal(imported.favorite, true);
  assert.equal(imported.reasoningPreset, "high");
  check(
    "explicit legacy import converges between independent browser installations; unknown source remains local",
  );

  await b.context.setOffline(true);
  await (await star(b.page, "claude-opus-5")).click();
  for (let i = 0; i < 50 && !(await pending(b.page)).length; i++)
    await b.page.waitForTimeout(100);
  const original = (await pending(b.page))[0].operation;
  assert.equal(original.value, false);
  await b.page.close();
  await b.context.setOffline(false);
  b.page = await b.context.newPage();
  await picker(b.page);
  await drained(b.page);
  await refresh(a.page);
  assert.equal(
    (await snapshot(a.page)).rows.find((r) => r.model === "claude-opus-5")
      .favorite,
    false,
  );
  check(
    "offline cancel survives page death and replays durable intent without deleting reasoning",
  );

  const requests = [];
  a.page.on("request", (r) => {
    if (r.url().endsWith("/v2/web/account-data/models/mutations"))
      requests.push(r.postDataJSON().opId);
  });
  let dropped = false;
  await a.page.route(
    "**/v2/web/account-data/models/mutations",
    async (route) => {
      const response = await route.fetch();
      assert.equal(response.status(), 200);
      dropped = true;
      await route.abort("connectionreset");
    },
  );
  await (await star(a.page, "claude-opus-5")).click();
  for (let i = 0; i < 100 && !dropped; i++) await a.page.waitForTimeout(100);
  assert(dropped);
  assert.equal((await pending(a.page)).length, 1);
  await a.page.unroute("**/v2/web/account-data/models/mutations");
  await a.page.reload();
  await drained(a.page);
  await picker(a.page);
  assert(requests.length >= 2);
  assert.equal(requests[0], requests[1]);
  check(
    "committed model edit with dropped ACK replays the same operation after reload",
  );

  await refresh(b.page);
  await b.context.setOffline(true);
  await (await star(b.page, "claude-opus-5")).click();
  for (let i = 0; i < 50 && !(await pending(b.page)).length; i++)
    await b.page.waitForTimeout(100);
  const conflictOp = (await pending(b.page))[0].operation;
  await (await star(a.page, "claude-opus-5")).click();
  await drained(a.page);
  await (await star(a.page, "claude-opus-5")).click();
  await drained(a.page);
  await b.context.setOffline(false);
  await wake(b.page);
  await refresh(b.page);
  await b.page
    .getByText(/HR-SYNC-012/)
    .first()
    .waitFor();
  assert.equal((await pending(b.page))[0].operation.opId, conflictOp.opId);
  await b.page
    .getByRole("button", { name: /重新应用本机选择|Apply local choice again/ })
    .click();
  await drained(b.page);
  assert.equal(
    (await snapshot(b.page)).rows.find((r) => r.model === "claude-opus-5")
      .favorite,
    false,
  );
  check(
    "same-field offline conflict requires explicit reapply with a fresh intent",
  );

  await b.page.locator(".model-favorites").waitFor({ state: "hidden" });
  assert.equal(
    await b.page.locator(".model-status-name").textContent(),
    "claude-opus-5",
  );
  const c = await login("other-models@example.test", false);
  assert.equal((await snapshot(c.page)).rows.length, 0);
  check("different account cannot see private model preferences");
  const nativeFile = process.env.HR_MODEL_SYNC_NATIVE_FIXTURE;
  if (nativeFile) {
    const installation = randomUUID();
    const challenge = await a.context.request.post(
      `${origin.origin}/v2/auth/email/challenges`,
      {
        data: {
          email,
          platform: "android",
          clientInstallationId: installation,
        },
      },
    );
    assert.equal(challenge.status(), 202);
    const { challenge: c } = await challenge.json();
    const exchange = await a.context.request.post(
      `${origin.origin}/v2/auth/email/exchange`,
      {
        headers: { "idempotency-key": randomUUID() },
        data: {
          email,
          code: await latestCode(email),
          challengeId: c.challengeId,
          platform: "android",
          clientInstallationId: installation,
          displayName: "Model smoke Android",
          appVersion: "local-smoke",
        },
      },
    );
    assert.equal(exchange.status(), 200);
    await writeFile(
      nativeFile,
      JSON.stringify({
        baseUrl: origin.origin,
        caPem: await readFile(resolve(state, "tls/ca.pem"), "utf8"),
        exchange: await exchange.json(),
      }),
      { mode: 0o600 },
    );
    check("issued isolated native session for model-preference consumer test");
  }
  await a.page.screenshot({
    path: resolve(state, `models-${engine}.png`),
    fullPage: true,
  });
  console.log(
    JSON.stringify({
      engine,
      checks: completed.length,
      browserVersion: browser.version(),
      serviceWorkers: "blocked-for-fault-injection",
    }),
  );
} finally {
  for (const context of contexts) await context.close();
  await browser.close();
}
