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
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL admits three owned Macs, rejects a concurrent fourth, and isolates selection", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `multi_device_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  let accountRepository: PostgresAccountRepository | undefined;
  try {
    const setup = new Pool({
      connectionString: databaseUrl,
      max: 1,
      options: `-c search_path=${schema}`,
    });
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await setup.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    await setup.end();

    const pool = new Pool({
      connectionString: databaseUrl,
      max: 12,
      options: `-c search_path=${schema}`,
    });
    const codec = new TokenCodec("multi-device-test-key-with-at-least-thirty-two-bytes");
    accountRepository = new PostgresAccountRepository(pool, codec);
    let identity: VerifiedExternalIdentity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `multi-owner-${randomUUID()}`,
      email: "multi-owner@example.invalid",
    };
    const accounts = new AccountService({ verify: async () => identity }, accountRepository, codec);
    const repository = new PostgresAccountControlRepository(pool, 10_000, 3);
    const control = new AccountControlService(repository, codec, () => new Date(), 3);

    const desktops = await Promise.all(
      ["Office Mac", "Home Mac", "Lab Mac", "Spare Mac"].map((name) => signIn(accounts, "macos", name)),
    );
    const principals = await Promise.all(desktops.map((session) => (
      accounts.authenticate(`Bearer ${session.session.accessToken}`)
    )));
    const attempts = await Promise.allSettled(principals.map((principal, index) => (
      control.createPendingBinding(principal, {
        desktopInstallationId: principal.installation.id,
        displayName: principal.installation.displayName,
        connectorPublicKey: Buffer.alloc(32, index + 1).toString("base64url"),
        keyAlgorithm: "Ed25519",
        idempotencyKey: randomUUID(),
      })
    )));
    const admitted = attempts.flatMap((result, index) => (
      result.status === "fulfilled" ? [{ index, binding: result.value }] : []
    ));
    const rejected = attempts.filter((result) => result.status === "rejected");
    assert.equal(admitted.length, 3);
    assert.equal(rejected.length, 1);
    assert(rejected[0].status === "rejected");
    assert.equal(errorCode(rejected[0].reason), "HR-BIND-010");

    for (const { index, binding } of admitted) {
      await pool.query(
        `UPDATE connector_bindings
            SET key_proved_at = now(), health_checked_at = now(), hermes_reachable = true,
                end_to_end_healthy = true, gateway_latency_ms = 1
          WHERE id = $1`,
        [binding.id],
      );
      await control.confirmPendingBinding(principals[index], {
        bindingId: binding.id,
        generation: binding.generation,
        idempotencyKey: randomUUID(),
      });
    }

    const phone = await signIn(accounts, "android", "Owner phone");
    const phonePrincipal = await accounts.authenticate(`Bearer ${phone.session.accessToken}`);
    const devices = await control.listDevices(phonePrincipal);
    assert.equal(devices.length, 3);
    assert.equal(devices.filter(({ isDefault }) => isDefault).length, 1);
    await assert.rejects(
      control.getBinding(phonePrincipal),
      (error: unknown) => errorCode(error) === "HR-BIND-009",
    );

    const selected = devices.find(({ isDefault }) => !isDefault) ?? devices[1];
    const selectKey = randomUUID();
    assert.equal((await control.selectDefaultDevice(phonePrincipal, selected.deviceId, selectKey)).isDefault, true);
    assert.equal((await control.selectDefaultDevice(phonePrincipal, selected.deviceId, selectKey)).id, selected.id);
    assert.equal((await control.getDevice(phonePrincipal, selected.deviceId)).isDefault, true);

    const unbindGrant = await accounts.reauthenticateGoogle(phonePrincipal, {
      idToken: "fresh-owner-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.unbind",
      idempotencyKey: randomUUID(),
    });
    const unbindKey = randomUUID();
    await control.unbindDevice(phonePrincipal, {
      deviceId: selected.deviceId,
      grant: unbindGrant.grant,
      idempotencyKey: unbindKey,
    });
    await control.unbindDevice(phonePrincipal, {
      deviceId: selected.deviceId,
      grant: unbindGrant.grant,
      idempotencyKey: unbindKey,
    });
    const remaining = await control.listDevices(phonePrincipal);
    assert.equal(remaining.length, 2);
    assert.equal(remaining.filter(({ isDefault }) => isDefault).length, 1);
    await assert.rejects(
      control.getDevice(phonePrincipal, selected.deviceId),
      (error: unknown) => errorCode(error) === "HR-BIND-011",
    );

    identity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `multi-other-${randomUUID()}`,
      email: "multi-other@example.invalid",
    };
    const otherPhone = await signIn(accounts, "android", "Other phone");
    const otherPrincipal = await accounts.authenticate(`Bearer ${otherPhone.session.accessToken}`);
    await assert.rejects(
      control.getDevice(otherPrincipal, selected.deviceId),
      (error: unknown) => errorCode(error) === "HR-BIND-011",
    );
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
    idToken: "multi-device-provider-proof-not-persisted",
    nonce: randomBytes(16).toString("hex"),
    clientInstallationId: randomUUID(),
    displayName,
    appVersion: "0.0.0-test",
    idempotencyKey: randomUUID(),
  });
}

function errorCode(error: unknown): unknown {
  return typeof error === "object" && error !== null && "code" in error
    ? (error as { code: unknown }).code
    : undefined;
}
