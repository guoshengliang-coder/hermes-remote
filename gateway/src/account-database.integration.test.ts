import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountService } from "./account/account-service.js";
import type { VerifiedExternalIdentity } from "./account/model.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL sessions survive restart and refresh reuse revokes the family atomically", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  let repository: PostgresAccountRepository | undefined;
  try {
    const codec = new TokenCodec("integration-test-key-with-at-least-thirty-two-bytes");
    const migrationFiles = (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort();
    const createRepository = async (): Promise<PostgresAccountRepository> => {
      const pool = new Pool({
        connectionString: databaseUrl,
        max: 4,
        options: `-c search_path=${schema}`,
      });
      await pool.query("SELECT 1");
      return new PostgresAccountRepository(pool, codec);
    };

    repository = await createRepository();
    const setupPool = new Pool({
      connectionString: databaseUrl,
      max: 1,
      options: `-c search_path=${schema}`,
    });
    for (const migrationFile of migrationFiles) {
      await setupPool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    await setupPool.end();

    let identity: VerifiedExternalIdentity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `test-${randomUUID()}`,
      email: "person@example.invalid",
      displayName: "Integration Test",
    };
    const clientInstallationId = randomUUID();
    let service = new AccountService(
      { verify: async () => identity },
      repository,
      codec,
    );
    const created = await service.exchangeGoogleProof({
      platform: "android",
      idToken: "not-persisted-provider-proof",
      nonce: "1234567890abcdef",
      clientInstallationId,
      displayName: "Test phone",
      appVersion: "0.0.0-test",
      idempotencyKey: randomUUID(),
    });

    await repository.close();
    repository = await createRepository();
    service = new AccountService({ verify: async () => identity }, repository, codec);
    const afterRestart = await service.authenticate(`Bearer ${created.session.accessToken}`);
    assert.equal(afterRestart.account.id, created.account.id);

    identity = {
      ...identity,
      email: "changed-person@example.invalid",
      displayName: "Changed Integration Test",
    };
    const signOutSession = await service.exchangeGoogleProof({
      platform: "android",
      idToken: "sign-out-not-persisted-provider-proof",
      nonce: "0011223344556677",
      clientInstallationId: randomUUID(),
      displayName: "Second test phone",
      appVersion: "0.0.0-test",
      idempotencyKey: randomUUID(),
    });
    assert.equal(signOutSession.account.id, created.account.id);
    assert.equal(signOutSession.account.email, "changed-person@example.invalid");
    const signOutKey = randomUUID();
    await service.signOut(`Bearer ${signOutSession.session.accessToken}`, signOutKey);
    await service.signOut(`Bearer ${signOutSession.session.accessToken}`, signOutKey);
    await assert.rejects(
      service.authenticate(`Bearer ${signOutSession.session.accessToken}`),
      (error: unknown) => typeof error === "object" && error !== null && "code" in error
        && (error as { code: unknown }).code === "HR-AUTH-004",
    );
    assert.equal(
      (await service.authenticate(`Bearer ${created.session.accessToken}`)).account.id,
      created.account.id,
    );

    // Sign-out after the access token expired (an idle browser tab): the access path can no longer
    // identify the session, so the refresh credential ends it, family and all.
    const idleInstallationId = randomUUID();
    const idleSession = await service.exchangeGoogleProof({
      platform: "android",
      idToken: "idle-sign-out-not-persisted-provider-proof",
      nonce: "8899aabbccddeeff",
      clientInstallationId: idleInstallationId,
      displayName: "Idle test phone",
      appVersion: "0.0.0-test",
      idempotencyKey: randomUUID(),
    });
    const expirePool = new Pool({ connectionString: databaseUrl, max: 1, options: `-c search_path=${schema}` });
    await expirePool.query(
      "UPDATE account_sessions SET access_expires_at = now() - interval '1 minute' WHERE access_token_hash = $1",
      [codec.hashAccessToken(idleSession.session.accessToken)],
    );
    await assert.rejects(
      service.signOut(`Bearer ${idleSession.session.accessToken}`, randomUUID()),
      (error: unknown) => (error as { code?: unknown }).code === "HR-AUTH-003",
    );
    await service.signOutWithRefreshToken(idleSession.session.refreshToken);
    await service.signOutWithRefreshToken(idleSession.session.refreshToken);
    await service.signOutWithRefreshToken("hgr_not-a-real-refresh-token-at-all-000000000000");
    const idleState = await expirePool.query<{ revoked: boolean; live_tokens: string }>(
      `SELECT s.revoked_at IS NOT NULL AS revoked,
              (SELECT count(*) FROM refresh_tokens r
                WHERE r.family_id = s.refresh_family_id AND r.revoked_at IS NULL)::text AS live_tokens
         FROM account_sessions s WHERE s.access_token_hash = $1`,
      [codec.hashAccessToken(idleSession.session.accessToken)],
    );
    await expirePool.end();
    assert.deepEqual(idleState.rows[0], { revoked: true, live_tokens: "0" });
    await assert.rejects(service.refresh({
      refreshToken: idleSession.session.refreshToken,
      clientInstallationId: idleInstallationId,
      idempotencyKey: randomUUID(),
    }));

    const retryKey = randomUUID();
    const results = await Promise.allSettled([
      service.refresh({
        refreshToken: created.session.refreshToken,
        clientInstallationId,
        idempotencyKey: retryKey,
      }),
      service.refresh({
        refreshToken: created.session.refreshToken,
        clientInstallationId,
        idempotencyKey: retryKey,
      }),
    ]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 2);
    const successful = results[0];
    const replayed = results[1];
    assert(successful.status === "fulfilled" && replayed.status === "fulfilled");
    assert.deepEqual(replayed.value, successful.value);

    await assert.rejects(
      service.refresh({
        refreshToken: created.session.refreshToken,
        clientInstallationId,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => typeof error === "object" && error !== null && "code" in error
        && (error as { code: unknown }).code === "HR-AUTH-005",
    );
    await assert.rejects(
      service.authenticate(`Bearer ${successful.value.accessToken}`),
      (error: unknown) => typeof error === "object" && error !== null && "code" in error
        && (error as { code: unknown }).code === "HR-AUTH-004",
    );

    const signedInAgain = await service.exchangeGoogleProof({
      platform: "android",
      idToken: "second-not-persisted-provider-proof",
      nonce: "fedcba0987654321",
      clientInstallationId,
      displayName: "Test phone",
      appVersion: "0.0.0-test",
      idempotencyKey: randomUUID(),
    });
    const principal = await service.authenticate(`Bearer ${signedInAgain.session.accessToken}`);
    const reauthentication = await service.reauthenticateGoogle(principal, {
      idToken: "fresh-not-persisted-provider-proof",
      nonce: "abcdef1234567890",
      scope: "account.revoke_all",
      idempotencyKey: randomUUID(),
    });
    const revokeKey = randomUUID();
    await service.revokeAllSessions(
      `Bearer ${signedInAgain.session.accessToken}`,
      reauthentication.grant,
      revokeKey,
    );
    await service.revokeAllSessions(
      `Bearer ${signedInAgain.session.accessToken}`,
      reauthentication.grant,
      revokeKey,
    );
    await assert.rejects(
      service.authenticate(`Bearer ${signedInAgain.session.accessToken}`),
      (error: unknown) => typeof error === "object" && error !== null && "code" in error
        && (error as { code: unknown }).code === "HR-AUTH-004",
    );

    const persistedRows: string[] = [];
    for (const table of [
      "accounts",
      "external_identities",
      "installations",
      "account_sessions",
      "refresh_tokens",
      "reauthentication_grants",
      "account_idempotency_records",
      "account_audit_events",
      "connector_bindings",
      "connector_replacement_requests",
      "email_otp_challenges",
    ]) {
      const rows = await admin.query<{ row: string }>(
        `SELECT row_to_json(t)::text AS row FROM "${schema}"."${table}" t`,
      );
      persistedRows.push(...rows.rows.map(({ row }) => row));
    }
    const persistedSnapshot = persistedRows.join("\n");
    for (const secret of [
      "not-persisted-provider-proof",
      "sign-out-not-persisted-provider-proof",
      "second-not-persisted-provider-proof",
      "fresh-not-persisted-provider-proof",
      created.session.accessToken,
      created.session.refreshToken,
      signOutSession.session.accessToken,
      signOutSession.session.refreshToken,
      successful.value.accessToken,
      successful.value.refreshToken,
      signedInAgain.session.accessToken,
      signedInAgain.session.refreshToken,
      reauthentication.grant,
    ]) {
      assert.equal(persistedSnapshot.includes(secret), false);
    }
  } finally {
    await repository?.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});
