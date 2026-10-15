import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Readable } from "node:stream";
import type { IncomingMessage, ServerResponse } from "node:http";
import test from "node:test";
import { Pool } from "pg";
import { AccountProfileSync, PostgresProfileSyncStore, profileRevision, type ProfileSyncStore } from "./account/account-profile-sync.js";
import { AccountHttpController } from "./account/account-http-controller.js";
import { AccountService } from "./account/account-service.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresAccountRetentionStore } from "./account/account-retention.js";
import { TokenCodec } from "./account/token-codec.js";
import { accountErrors, type AccountPrincipal } from "./account/model.js";
import { WebSessionSecurity, WEB_COOKIE_NAMES } from "./account/web-session-security.js";

const codec = new TokenCodec("profile-sync-test-only-key-with-thirty-two-bytes");
const principal: AccountPrincipal = { account: { id: randomUUID(), displayName: "Original" },
  installation: { id: randomUUID(), kind: "phone", platform: "android", displayName: "Test" }, sessionId: randomUUID(), refreshFamilyId: randomUUID() };
const state = { revision: "9007199254740993", nameRevision: "9007199254740993", avatarRevision: "0", displayName: "Stored" };
const fake: ProfileSyncStore = { avatar: async () => null, read: async () => state, mutate: async () => ({ status: "accepted", ...state }) };
const hasCode = (code: string) => (e: unknown) => !!e && typeof e === "object" && "code" in e && e.code === code;

test("profile revisions are exact uint64 strings and cursors are scoped, signed and leased", async () => {
  for (const value of [0, 1, "01", "-1", "1e3", "18446744073709551616", null]) assert.throws(() => profileRevision(value), hasCode("HR-ACCOUNT-004"));
  assert.equal(profileRevision("18446744073709551615"), "18446744073709551615");
  let clock = Date.now();
  const sync = new AccountProfileSync(fake, codec, () => clock);
  const snapshot = await sync.snapshot(principal, false);
  assert.equal(snapshot.revision, "9007199254740993");
  assert.equal((await sync.snapshot(principal, false, snapshot.cursor)).profile, null);
  await assert.rejects(sync.snapshot({ ...principal, account: { id: randomUUID() } }, true, snapshot.cursor), hasCode("HR-SYNC-007"));
  await assert.rejects(sync.snapshot(principal, true, snapshot.cursor + "x"), hasCode("HR-SYNC-007"));
  clock += 86_400_001;
  await assert.rejects(sync.snapshot(principal, false, snapshot.cursor), hasCode("HR-SYNC-007"));
});

test("sync rejects malformed, overlong and expired operations without echoing private inputs", async () => {
  const sync = new AccountProfileSync(fake, codec);
  const op = { field: "displayName" as const, opId: randomUUID(), issuedAt: new Date().toISOString(), baseRevision: "1", displayName: "Private\nname" };
  await assert.rejects(sync.mutate(principal, op), (error: unknown) => hasCode("HR-SYNC-008")(error) && !String(error).includes("Private"));
  await assert.rejects(sync.mutate(principal, { ...op, displayName: "x", issuedAt: "2020-01-01T00:00:00Z" }), hasCode("HR-SYNC-007"));
  await assert.rejects(sync.mutate(principal, { ...op, displayName: "x", opId: "no" }), hasCode("HR-ACCOUNT-004"));
});

test("sync HTTP gates capabilities, keeps cookie and native auth separate, and enforces CSRF", async () => {
  const cookieAccess = `hga_${"a".repeat(43)}`, csrf = `hgc_${"b".repeat(43)}`;
  const seen: unknown[] = [];
  const service = { authenticate: async (auth: string) => { seen.push(auth); if (!auth) throw accountErrors.sessionExpired(); return principal; } } as unknown as AccountService;
  const sync = new AccountProfileSync(fake, codec);
  const controller = new AccountHttpController(true, service, { profileSync: sync, webSessionEnabled: true, webSessionSecurity: new WebSessionSecurity("https://sync.invalid") });
  const call = async (path: string, method = "GET", headers: Record<string, string> = {}, body?: unknown, target = controller) => {
    const request = Readable.from(body === undefined ? [] : [JSON.stringify(body)]) as IncomingMessage;
    request.method = method; request.headers = headers;
    let status = 0; let responseHeaders: unknown; let data = "";
    const response = { writableEnded: false, writeHead: (s: number, h: unknown) => { status = s; responseHeaders = h; }, end: (b: string) => { data = b; } } as unknown as ServerResponse;
    await target.handle(request, response, new URL(path, "https://sync.invalid"));
    return { status, headers: responseHeaders, body: JSON.parse(data) };
  };
  const caps = await call("/v2/capabilities");
  assert.deepEqual(caps.body.accountAuth.accountDataSync, { version: 1, entities: ["accountProfile"] });
  const disabled = new AccountHttpController(true, service);
  assert.equal((await call("/v2/capabilities", "GET", {}, undefined, disabled)).body.accountAuth.accountDataSync, undefined);
  assert.equal((await call("/v2/account-data/snapshot", "GET", {}, undefined, disabled)).status, 503);
  const cookie = `${WEB_COOKIE_NAMES.access}=${cookieAccess}; ${WEB_COOKIE_NAMES.csrf}=${csrf}`;
  await call("/v2/account-data/snapshot", "GET", { authorization: "Bearer native", cookie });
  assert.equal(seen.at(-1), "Bearer native");
  await call("/v2/web/account-data/snapshot", "GET", { authorization: "Bearer native", cookie, "x-hermes-expected-account": principal.account.id });
  assert.equal(seen.at(-1), `Bearer ${cookieAccess}`);
  assert.equal((await call("/v2/web/account-data/snapshot", "GET", { authorization: "Bearer native" })).status, 401);
  const wrongScope = await call("/v2/web/account-data/snapshot", "GET", { cookie, "x-hermes-expected-account": "other-account" });
  assert.equal(wrongScope.status, 409); assert.equal(wrongScope.body.error.code, "HR-SYNC-007");
  const op = { opId: randomUUID(), issuedAt: new Date().toISOString(), baseRevision: "1", displayName: "New" };
  assert.equal((await call("/v2/web/account-data/mutations", "POST", { cookie, "content-type": "application/json" }, op)).status, 403);
  const safe = { "x-hermes-expected-account": principal.account.id, cookie, origin: "https://sync.invalid", "x-hermes-csrf": csrf, "content-type": "application/json" };
  assert.equal((await call("/v2/web/account-data/mutations", "POST", safe, op)).status, 200);
  assert.equal((await call("/v2/web/account-data/mutations", "POST", safe, { ...op, accountId: "victim" })).status, 400);
  const reset = await call("/v2/account-data/changes?cursor=secret-private-text", "GET", { authorization: "Bearer native" });
  assert.equal(reset.status, 409); assert.equal(reset.body.error.code, "HR-SYNC-007"); assert.equal(reset.body.error.retryable, false);
  assert(!JSON.stringify(reset.body).includes("secret-private-text"));
});

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
test("PostgreSQL profile sync persists exact revisions, serializes CAS, includes legacy writers and blocks revoked replay", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL 18",
}, async () => {
  const schema = `profile_sync_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  const pool = new Pool({ connectionString: databaseUrl, max: 6, options: `-c search_path=${schema}` });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  try {
    for (const file of (await readdir(resolve("migrations"))).filter((n) => /^\d{3}_.*\.sql$/.test(n)).sort()) await pool.query(await readFile(resolve("migrations", file), "utf8"));
    const repository = new PostgresAccountRepository(pool, codec);
    const auth = new AccountService({ verify: async () => ({ provider: "google", issuer: "https://accounts.google.com", subject: randomUUID(), email: "profile@example.invalid", displayName: "Original" }) }, repository, codec);
    const exchange = await auth.exchangeGoogleProof({ platform: "android", idToken: "test-proof", nonce: "1234567890abcdef", clientInstallationId: randomUUID(), displayName: "Test", appVersion: "0.0.0-test", idempotencyKey: randomUUID() });
    const actor = await auth.authenticate(`Bearer ${exchange.session.accessToken}`);
    let sync = new AccountProfileSync(new PostgresProfileSyncStore(pool), codec);
    const first = await sync.snapshot(actor, false);
    assert.equal(first.profile?.account.displayName, "Original");
    const operation = (name: string, baseRevision = "1") => ({ field: "displayName" as const, opId: randomUUID(), issuedAt: new Date().toISOString(), baseRevision, displayName: name });
    const opA = operation("Phone"), opB = operation("Web");
    const results = await Promise.allSettled([sync.mutate(actor, opA), sync.mutate(actor, opB)]);
    assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
    assert.equal(results.filter((r) => r.status === "rejected" && hasCode("HR-SYNC-006")(r.reason)).length, 1);
    const accepted = results[0].status === "fulfilled" ? opA : opB;
    const ack = await sync.mutate(actor, accepted);
    sync = new AccountProfileSync(new PostgresProfileSyncStore(pool), codec); // New service instance.
    assert.deepEqual(await sync.mutate(actor, accepted), ack);
    await assert.rejects(sync.mutate(actor, { ...accepted, displayName: "Changed payload" }), hasCode("HR-ACCOUNT-005"));
    await repository.setAccountDisplayName(actor.account.id, "Legacy web");
    const afterLegacy = await sync.snapshot(actor, true, first.cursor);
    assert.equal(afterLegacy.profile?.account.displayName, "Legacy web");
    assert.equal(afterLegacy.revision, "3");
    const avatar = { contentType: "image/png" as const, data: Buffer.from([137,80,78,71,13,10,26,10]) };
    await sync.mutate(actor, { field: "avatar", avatar, opId: randomUUID(), issuedAt: new Date().toISOString(), baseRevision: "0" });
    const afterAvatar = await sync.snapshot(actor, false);
    assert.deepEqual(await sync.avatar(actor, "4"), avatar);
    await assert.rejects(sync.avatar(actor, "3"), hasCode("HR-SYNC-007"));
    assert.equal(afterAvatar.profile?.nameRevision, "3"); assert.equal(afterAvatar.profile?.avatarRevision, "4");
    assert.match(afterAvatar.profile!.account.avatarUrl!, /^\/v2\/account-data\/avatar\?rev=4$/);
    await assert.rejects(sync.mutate(actor, operation("Stale", "2")), hasCode("HR-SYNC-006"));
    await pool.query("UPDATE account_sessions SET revoked_at=now() WHERE id=$1", [actor.sessionId]);
    await assert.rejects(sync.mutate(actor, accepted), hasCode("HR-AUTH-004"));
    await assert.rejects(sync.snapshot(actor, false), hasCode("HR-AUTH-004"));
    // An account's deletion deadline must remove the profile bytea and replay rows, not only hide them.
    await pool.query("UPDATE accounts SET status='pending_deletion', deletion_requested_at=now()-interval '31 days', deletion_due_at=now()-interval '1 day' WHERE id=$1", [actor.account.id]);
    const retention = new PostgresAccountRetentionStore(pool);
    for (let n = 0; n < 5; n++) await retention.sweep(new Date(Date.now() + 365 * 86_400_000), 1000);
    assert.equal((await pool.query("SELECT count(*) FROM account_profiles WHERE account_id=$1", [actor.account.id])).rows[0].count, "0");
    assert.equal((await pool.query("SELECT count(*) FROM account_profile_sync_operations WHERE account_id=$1", [actor.account.id])).rows[0].count, "0");
  } finally {
    await pool.end(); await admin.query(`DROP SCHEMA "${schema}" CASCADE`); await admin.end();
  }
});
