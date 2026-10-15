// Real-browser smoke against web-stack.sh only. No production origin, user browser profile,
// response mocking, database access or credentials in output. Install Playwright separately;
// HR_PLAYWRIGHT_MODULE points to its index.mjs (no production/package dependency is added).
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const origin = new URL(process.env.HR_PROFILE_SYNC_ORIGIN ?? "https://localhost:18443");
assert(origin.protocol === "https:" && origin.hostname === "localhost" && Number(origin.port) >= 18000 &&
  origin.pathname === "/" && !origin.username && !origin.password, "only the isolated HTTPS localhost stack is allowed");
const state = process.env.HR_WEB_STACK_STATE;
assert(state && process.env.HR_PLAYWRIGHT_MODULE, "set HR_WEB_STACK_STATE and HR_PLAYWRIGHT_MODULE");
const email = process.env.HR_WEB_STACK_EMAIL ?? "dev@example.test";
assert(email.endsWith("@example.test"), "use an artificial example.test account");
const { chromium, webkit } = await import(pathToFileURL(resolve(process.env.HR_PLAYWRIGHT_MODULE)).href);
const engine = process.env.HR_PROFILE_SYNC_BROWSER ?? "chromium";
assert(["chromium", "webkit"].includes(engine), "browser must be chromium or webkit");
const browser = await ({ chromium, webkit }[engine]).launch();
const contexts = [];
const completed = [];
const check = (name) => { completed.push(name); console.log(`PASS ${name}`); };
const nameInput = (page) => page.locator("#account-name");
const save = (page) => page.getByRole("button", { name: /^(保存|Save)$/ });
async function equalName(page, name) {
  await page.waitForFunction((expected) => document.querySelector("#account-name")?.value === expected, name);
}
async function wake(page) { await page.evaluate(() => window.dispatchEvent(new Event("online"))); }
async function pending(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open("hermes-go.account-profile-sync.v1");
    open.onerror = () => reject(new Error("cannot read test queue"));
    open.onsuccess = () => {
      const db = open.result;
      const request = db.transaction("pending").objectStore("pending").getAll();
      request.onsuccess = () => { resolve(request.result); db.close(); };
      request.onerror = () => { reject(new Error("cannot read test queue")); db.close(); };
    };
  }));
}
async function drained(page) {
  for (let attempt = 0; attempt < 40; attempt++) {
    if ((await pending(page)).length === 0) return;
    await page.waitForTimeout(100);
  }
  assert.fail("durable queue did not drain");
}
async function saveOnline(page) {
  const response = page.waitForResponse((r) => r.url().endsWith("/v2/web/account-data/mutations"));
  await save(page).click();
  assert.equal((await response).status(), 200);
  await drained(page);
}
async function latestCode(target) {
  const lines = (await readFile(resolve(state, "login-codes.jsonl"), "utf8")).trim().split("\n");
  const row = lines.map((line) => JSON.parse(line)).findLast((line) => line.to === target);
  assert(row && /^\d{6}$/.test(row.code), "local mail capture missing");
  return row.code;
}
async function login(target = email, needsMac = true) {
  // Page routing cannot reliably drop a request handled by a Service Worker in WebKit. Block SW
  // in this fault-injection suite; SW cache behavior still needs its separate smoke/acceptance gate.
  const context = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: "block", viewport: { width: 430, height: 932 } });
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(`${origin.origin}/app/account`);
  await page.locator('input[type="email"]').fill(target);
  const challenge = page.waitForResponse((r) => r.url().endsWith("/v2/web/auth/email/challenges"));
  await page.getByRole("button", { name: /发送验证码|Send code/ }).click();
  assert.equal((await challenge).status(), 202, "login challenge");
  const exchange = page.waitForResponse((r) => r.url().endsWith("/v2/web/auth/email/exchange"));
  await page.locator('input[autocomplete="one-time-code"]').fill(await latestCode(target));
  assert.equal((await exchange).status(), 200, "login exchange");
  if (needsMac) {
    await nameInput(page).waitFor();
    await page.waitForFunction(() => document.querySelector("#account-name")?.value?.length > 0);
  }
  return { context, page };
}
async function snapshot(page) {
  return page.evaluate(async () => {
    const session = await (await fetch("/v2/web/session")).json();
    const response = await fetch("/v2/web/account-data/snapshot", {
      headers: { "x-hermes-expected-account": session.session.account.id },
    });
    if (!response.ok) throw new Error("snapshot failed");
    return response.json();
  });
}

try {
  const a = await login();
  const caps = await a.page.evaluate(async () => (await fetch("/v2/capabilities")).json());
  assert.deepEqual(caps.accountAuth.accountDataSync, { version: 1, entities: ["accountProfile"] });
  const b = await login(); // independent browser installation/cookies/IndexedDB
  assert.equal((await snapshot(a.page)).profile.account.id, (await snapshot(b.page)).profile.account.id);
  check("two independent browser installations sign in to the same real account");

  await nameInput(a.page).fill("Sync A"); await saveOnline(a.page);
  await wake(b.page); await equalName(b.page, "Sync A");
  check("name edited in A converges in the already-open B account page");

  await nameInput(b.page).fill("B typing");
  await nameInput(a.page).fill("A newer"); await saveOnline(a.page);
  await wake(b.page);
  await b.page.getByText(/Current cloud name:|云端当前名字：/).waitFor({ state: "hidden" });
  assert.equal(await nameInput(b.page).inputValue(), "B typing");
  await save(b.page).click(); await b.page.getByText(/HR-SYNC-006/).first().waitFor();
  assert.equal((await snapshot(a.page)).profile.account.displayName, "A newer");
  await saveOnline(b.page); await wake(a.page); await equalName(a.page, "B typing");
  check("foreground pull preserves typing and stale save conflicts until explicit resave");

  await b.context.setOffline(true);
  await nameInput(b.page).fill("Offline B"); await save(b.page).click();
  await b.page.getByText(/Waiting to sync|正在等待同步/).waitFor();
  const queued = await pending(b.page); assert.equal(queued.length, 1);
  await b.page.close(); await b.context.setOffline(false);
  b.page = await b.context.newPage();
  const replay = b.page.waitForRequest((r) => r.url().endsWith("/v2/web/account-data/mutations"));
  await b.page.goto(`${origin.origin}/app/account`);
  assert.equal((await replay).postDataJSON().opId, queued[0].operation.opId);
  await equalName(b.page, "Offline B"); await drained(b.page);
  await wake(a.page); await equalName(a.page, "Offline B");
  check("offline IndexedDB edit survives page death and replays the original operation");

  const operations = [];
  a.page.on("request", (r) => { if (r.url().endsWith("/v2/web/account-data/mutations")) operations.push(r.postDataJSON().opId); });
  let completeDrop;
  const dropped = new Promise((resolve) => { completeDrop = resolve; });
  await a.page.route("**/v2/web/account-data/mutations", async (route) => {
    const response = await route.fetch(); assert.equal(response.status(), 200); // commit, then drop ACK
    await route.abort("connectionreset");
    completeDrop();
  });
  await nameInput(a.page).fill("Lost ACK"); await save(a.page).click();
  try { await a.page.getByText(/Waiting to sync|正在等待同步/).waitFor(); }
  catch (error) {
    console.error("lost-ACK diagnostic", JSON.stringify({ operations: operations.length,
      queue: (await pending(a.page)).map((p) => ({ terminal: p.terminal?.code, baseRevision: p.operation.baseRevision })),
      notices: await a.page.locator(".error-notice").allTextContents() }));
    throw error;
  }
  await dropped;
  await a.page.unroute("**/v2/web/account-data/mutations");
  await a.page.reload(); await equalName(a.page, "Lost ACK"); await drained(a.page);
  assert(operations.length >= 2); assert.equal(operations[0], operations[1]);
  await wake(b.page); await equalName(b.page, "Lost ACK");
  check("committed edit with dropped ACK replays idempotently after browser reload");

  await a.context.setOffline(true);
  await a.page.locator('input[type="file"]').setInputFiles(resolve("android/app/src/main/ic_launcher-playstore.png"));
  await a.page.getByRole("button", { name: /^(完成|Done)$/ }).click();
  await a.page.getByText(/Waiting to sync|正在等待同步/).waitFor();
  const avatarQueue = await pending(a.page);
  assert.equal(avatarQueue.length, 1);
  assert(["image/png", "image/jpeg", "image/webp"].includes(avatarQueue[0].avatarType));
  await a.page.close(); await a.context.setOffline(false);
  a.page = await a.context.newPage();
  const avatarReplay = a.page.waitForRequest((r) => r.method() === "PUT" && r.url().endsWith("/v2/web/account-data/avatar"));
  await a.page.goto(`${origin.origin}/app/account`);
  assert.equal((await avatarReplay).headers()["idempotency-key"], avatarQueue[0].operation.opId);
  try { await a.page.locator("img.account-avatar").waitFor(); }
  catch (error) {
    console.error("avatar diagnostic", JSON.stringify({
      queue: (await pending(a.page)).map((p) => ({ terminal: p.terminal?.code,
        size: p.avatarBytes?.byteLength ?? p.avatar?.size, type: p.avatarType ?? p.avatar?.type })),
      notices: await a.page.locator(".error-notice").allTextContents() }));
    throw error;
  }
  await drained(a.page);
  await wake(b.page);
  await b.page.locator("img.account-avatar").waitFor();
  await b.page.waitForFunction(() => { const image = document.querySelector("img.account-avatar"); return image?.complete && image.naturalWidth > 0; });
  const cloud = await snapshot(a.page);
  const avatar = await a.context.request.get(`${origin.origin}${cloud.profile.account.avatarUrl}`);
  assert.equal(avatar.status(), 200); assert.match(avatar.headers()["cache-control"], /no-store/);
  const avatarSha256 = createHash("sha256").update(await avatar.body()).digest("hex");
  check("offline avatar survives page death, replays on B and private bytes are not HTTP cached");

  // Optional credential fixture for the opt-in Android instrumentation test. Local tokens are
  // written mode 0600; never printed, committed, traced or included in screenshots.
  const nativeFile = process.env.HR_PROFILE_SYNC_NATIVE_FIXTURE;
  if (nativeFile) {
    const installation = randomUUID();
    const challenge = await a.context.request.post(`${origin.origin}/v2/auth/email/challenges`, {
      data: { email, platform: "android", clientInstallationId: installation },
    });
    assert.equal(challenge.status(), 202);
    const { challenge: c } = await challenge.json();
    const exchange = await a.context.request.post(`${origin.origin}/v2/auth/email/exchange`, {
      headers: { "idempotency-key": randomUUID() },
      data: { email, code: await latestCode(email), challengeId: c.challengeId, platform: "android",
        clientInstallationId: installation, displayName: "Sync smoke Android", appVersion: "local-smoke" },
    });
    assert.equal(exchange.status(), 200);
    const native = await exchange.json();
    assert.equal(native.account.id, cloud.profile.account.id);
    await writeFile(nativeFile, JSON.stringify({ baseUrl: origin.origin, caPem: await readFile(resolve(state, "tls/ca.pem"), "utf8"),
      exchange: native, expected: { displayName: cloud.profile.account.displayName, revision: cloud.revision,
        avatarRevision: cloud.profile.avatarRevision, avatarSha256 } }), { mode: 0o600 });
    check("issued isolated native session for opt-in Android live consumer test");
  }

  const foreign = await login(`other-${randomUUID().slice(0, 8)}@example.test`, false);
  assert.notEqual((await snapshot(foreign.page)).profile.account.id, cloud.profile.account.id);
  const denied = await foreign.page.evaluate(async (owner) => {
    const response = await fetch("/v2/web/account-data/snapshot", { headers: { "x-hermes-expected-account": owner } });
    return response.status;
  }, cloud.profile.account.id);
  assert.equal(denied, 409);
  check("another real account cannot read the expected owner's profile");

  const tab = await a.context.newPage(); await tab.goto(`${origin.origin}/app/account`); await nameInput(tab).waitFor();
  // This tab cannot learn about sign-out through a later HTTP 401; the storage signal must hide it.
  await tab.route("**/v2/**", (route) => route.abort("internetdisconnected"));
  await a.page.getByRole("button", { name: /^(退出登录|Sign out)$/ }).click();
  await a.page.locator('input[type="email"]').waitFor();
  await tab.locator('input[type="email"]').waitFor();
  assert.deepEqual(await pending(tab), []);
  await tab.unroute("**/v2/**");
  assert.equal((await tab.evaluate(async () => (await (await fetch("/v2/web/session")).json()).session.authenticated)), false);
  check("sign-out clears shared durable queue and invalidates the other tab's server session");
  console.log(JSON.stringify({ browser: engine, version: browser.version(), passed: completed.length,
    scope: "local real Gateway/PostgreSQL + browser UI; no production or physical-device proof" }));
} finally {
  for (const context of contexts) await context.close();
  await browser.close();
}
