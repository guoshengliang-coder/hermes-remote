import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountControlService } from "./account/account-control-service.js";
import { AccountService, type AccountSessionResponse } from "./account/account-service.js";
import type { AccountPlatform, VerifiedExternalIdentity } from "./account/model.js";
import { PostgresAccountControlRepository } from "./account/postgres-account-control-repository.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresPushRegistrationStore } from "./account/push/push-registration-store.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL push registrations are per live Android phone and vanish on revocation", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `push_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  let accountRepository: PostgresAccountRepository | undefined;
  try {
    const pool = new Pool({ connectionString: databaseUrl, max: 4, options: `-c search_path=${schema}` });
    const migrationFiles = (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort();
    for (const migrationFile of migrationFiles) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    assert.equal((await pool.query(
      "SELECT version FROM gateway_schema_state WHERE singleton = true",
    )).rows[0].version, 16);

    const codec = new TokenCodec("push-integration-key-with-at-least-thirty-two-bytes");
    accountRepository = new PostgresAccountRepository(pool, codec);
    let identity: VerifiedExternalIdentity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `push-a-${randomUUID()}`,
      email: "push-a@example.invalid",
      displayName: "Push A",
    };
    const accounts = new AccountService({ verify: async () => identity }, accountRepository, codec);
    const control = new AccountControlService(new PostgresAccountControlRepository(pool), codec);
    const store = new PostgresPushRegistrationStore(pool);

    const phoneA = await signIn(accounts, "android", "Phone A");
    const phoneB = await signIn(accounts, "android", "Phone B");
    const desktop = await signIn(accounts, "macos", "Desktop");
    const phoneAPrincipal = await accounts.authenticate(`Bearer ${phoneA.session.accessToken}`);
    const phoneBPrincipal = await accounts.authenticate(`Bearer ${phoneB.session.accessToken}`);
    const desktopPrincipal = await accounts.authenticate(`Bearer ${desktop.session.accessToken}`);
    const accountId = phoneAPrincipal.account.id;

    identity = { ...identity, subject: `push-b-${randomUUID()}`, email: "push-b@example.invalid" };
    const otherPhone = await signIn(accounts, "android", "Other Phone");
    const otherPrincipal = await accounts.authenticate(`Bearer ${otherPhone.session.accessToken}`);

    await store.upsert(accountId, phoneAPrincipal.installation.id, "fcm", "token-a1");
    await store.upsert(accountId, phoneAPrincipal.installation.id, "fcm", "token-a2");
    await store.upsert(accountId, phoneBPrincipal.installation.id, "fcm", "token-b");
    // A Desktop installation and another account's phone cannot be registered under this account.
    await store.upsert(accountId, desktopPrincipal.installation.id, "fcm", "token-desktop");
    await store.upsert(accountId, otherPrincipal.installation.id, "fcm", "token-other");
    await store.upsert(otherPrincipal.account.id, otherPrincipal.installation.id, "fcm", "token-other");

    const tokens = async (id: string) => (await store.listTargets(id)).map((t) => t.token).sort();
    assert.deepEqual(await tokens(accountId), ["token-a2", "token-b"]);
    assert.deepEqual(await tokens(otherPrincipal.account.id), ["token-other"]);

    // A stale token report must not delete a registration that has since rotated.
    await store.removeToken(phoneAPrincipal.installation.id, "token-a1");
    assert.deepEqual(await tokens(accountId), ["token-a2", "token-b"]);
    await store.removeToken(phoneBPrincipal.installation.id, "token-b");
    assert.deepEqual(await tokens(accountId), ["token-a2"]);

    await control.revokeCurrentPhoneInstallation(`Bearer ${phoneA.session.accessToken}`, randomUUID());
    assert.deepEqual(await tokens(accountId), []);
    assert.equal((await pool.query(
      "SELECT count(*)::int AS n FROM account_push_registrations WHERE installation_id = $1",
      [phoneAPrincipal.installation.id],
    )).rows[0].n, 0);
    // A revoked installation cannot register again.
    await store.upsert(accountId, phoneAPrincipal.installation.id, "fcm", "token-a3");
    assert.deepEqual(await tokens(accountId), []);

    await store.remove(otherPrincipal.account.id, otherPrincipal.installation.id);
    assert.deepEqual(await tokens(otherPrincipal.account.id), []);
    await pool.end();
  } finally {
    await accountRepository?.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

async function signIn(
  service: AccountService,
  platform: AccountPlatform,
  displayName: string,
): Promise<AccountSessionResponse> {
  return service.exchangeGoogleProof({
    platform,
    idToken: "integration-provider-proof-not-persisted",
    nonce: randomBytes(16).toString("hex"),
    clientInstallationId: randomUUID(),
    displayName,
    appVersion: "0.0.0-test",
    idempotencyKey: randomUUID(),
  });
}
