import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountService } from "./account/account-service.js";
import { EmailOtpSecurity } from "./account/email-otp.js";
import { EmailOtpService } from "./account/email-otp-service.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresEmailOtpStore } from "./account/postgres-email-otp-store.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL email OTP challenges are race-safe, bounded, and plaintext-free", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `email_otp_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 4,
    options: `-c search_path=${schema}`,
  });
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    assert.equal((await pool.query<{ name: string | null }>(
      "SELECT to_regclass('email_otp_challenges_retention_idx')::text AS name",
    )).rows[0].name, "email_otp_challenges_retention_idx");
    const security = new EmailOtpSecurity(
      "database-email-otp-secret-with-at-least-thirty-two-bytes",
      "https://mrlgs.net",
      () => 246810,
    );
    const store = new PostgresEmailOtpStore(pool);
    const deliveries: Array<{ recipient: string; code: string }> = [];
    const clientInstallationId = randomUUID();
    let now = new Date("2026-09-07T00:00:00.000Z");
    const boundaryChallengeId = randomUUID();
    const retainedChallengeId = randomUUID();
    await pool.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, invalidated_at, created_at)
       SELECT ('00000000-0000-4000-8000-' || lpad(value::text, 12, '0'))::uuid,
              'sign_in', $1, $2, $3, 'macos', $4, $4, $5
         FROM generate_series(1, 1001) AS value`,
      [
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        new Date(now.getTime() - 36 * 24 * 60 * 60 * 1_000 + 10 * 60 * 1_000),
        new Date(now.getTime() - 36 * 24 * 60 * 60 * 1_000),
      ],
    );
    await pool.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, invalidated_at, created_at)
       VALUES ($1, 'sign_in', $2, $3, $4, 'macos', $5, $5, $6),
              ($7, 'sign_in', $8, $9, $10, 'macos', $11, $11, $12)`,
      [
        boundaryChallengeId,
        "4".repeat(64),
        "5".repeat(64),
        "6".repeat(64),
        new Date(now.getTime() - 35 * 24 * 60 * 60 * 1_000 + 10 * 60 * 1_000),
        new Date(now.getTime() - 35 * 24 * 60 * 60 * 1_000),
        retainedChallengeId,
        "d".repeat(64),
        "e".repeat(64),
        "f".repeat(64),
        new Date(now.getTime() - 34 * 24 * 60 * 60 * 1_000 + 10 * 60 * 1_000),
        new Date(now.getTime() - 34 * 24 * 60 * 60 * 1_000),
      ],
    );
    await store.createChallenge({
      id: randomUUID(),
      purpose: "sign_in",
      emailLookupHash: "1".repeat(64),
      codeHash: "2".repeat(64),
      requesterSourceHash: "3".repeat(64),
      requesterPlatform: "macos",
      createdAt: now,
      expiresAt: new Date(now.getTime() + 10 * 60 * 1_000),
      maxAttempts: 5,
    }, {
      cooldownMs: 60_000,
      rateWindowMs: 15 * 60_000,
      maxRequestsPerEmail: 5,
      maxRequestsPerSource: 20,
    });
    assert.equal((await pool.query<{ count: string }>(
      "SELECT count(*)::text AS count FROM email_otp_challenges WHERE created_at < $1",
      [new Date(now.getTime() - 35 * 24 * 60 * 60 * 1_000)],
    )).rows[0].count, "1");
    const service = new EmailOtpService(
      store,
      { sendLoginCode: async ({ messageId, recipient, code }) => {
        deliveries.push({ recipient, code });
        return { providerMessageId: messageId };
      } },
      security,
      () => now,
    );

    const requests = await Promise.all([
      service.requestChallenge({
        email: "Person+db@Example.com",
        purpose: "sign_in",
        platform: "macos",
        source: "203.0.113.20",
        clientInstallationId,
      }),
      service.requestChallenge({
        email: "person+db@example.com",
        purpose: "sign_in",
        platform: "macos",
        source: "203.0.113.20",
        clientInstallationId,
      }),
    ]);
    assert.equal(requests[0].challengeId, requests[1].challengeId);
    assert.equal(deliveries.length, 1);
    assert.equal((await pool.query<{ count: string }>(
      "SELECT count(*)::text AS count FROM email_otp_challenges WHERE created_at < $1",
      [new Date(now.getTime() - 35 * 24 * 60 * 60 * 1_000)],
    )).rows[0].count, "0");
    assert.deepEqual(new Set((await pool.query<{ id: string }>(
      "SELECT id FROM email_otp_challenges WHERE id = ANY($1::uuid[])",
      [[boundaryChallengeId, retainedChallengeId]],
    )).rows.map(({ id }) => id)), new Set([boundaryChallengeId, retainedChallengeId]));

    for (let attempt = 0; attempt < 5; attempt += 1) {
      await assert.rejects(
        service.verifyChallenge({
          challengeId: requests[0].challengeId,
          email: "person+db@example.com",
          code: "000000",
          purpose: "sign_in",
          platform: "macos",
          clientInstallationId,
          exchangeIdempotencyKey: "failed-exchange-request",
        }),
        hasCode("HR-AUTH-009"),
      );
    }
    await assert.rejects(
      service.verifyChallenge({
        challengeId: requests[0].challengeId,
        email: "person+db@example.com",
        code: "246810",
        purpose: "sign_in",
        platform: "macos",
        clientInstallationId,
        exchangeIdempotencyKey: "failed-exchange-request",
      }),
      hasCode("HR-AUTH-009"),
    );

    now = new Date("2026-09-07T00:01:01.000Z");
    const next = await service.requestChallenge({
      email: "person+db@example.com",
      purpose: "sign_in",
      platform: "macos",
      source: "203.0.113.20",
      clientInstallationId,
    });
    const identity = await service.verifyChallenge({
      challengeId: next.challengeId,
      email: "person+db@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId,
      exchangeIdempotencyKey: "successful-exchange-request",
    });
    assert.equal(identity.provider, "email_otp");
    assert.deepEqual(await service.verifyChallenge({
      challengeId: next.challengeId,
      email: "person+db@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId,
      exchangeIdempotencyKey: "successful-exchange-request",
    }), identity);
    await assert.rejects(service.verifyChallenge({
      challengeId: next.challengeId,
      email: "person+db@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId,
      exchangeIdempotencyKey: "different-exchange-request",
    }), hasCode("HR-AUTH-009"));

    const persisted = (await pool.query<{ row: string }>(
      "SELECT row_to_json(t)::text AS row FROM email_otp_challenges t",
    )).rows.map(({ row }) => row).join("\n");
    for (const plaintext of ["Person+db@Example.com", "person+db@example.com", "246810", "203.0.113.20"]) {
      assert.equal(persisted.includes(plaintext), false);
    }

    const accountTokens = new TokenCodec(
      "database-account-token-secret-with-at-least-thirty-two-bytes",
    );
    const accountRepository = new PostgresAccountRepository(pool, accountTokens);
    const accountService = new AccountService(
      { verify: async () => { throw new Error("Google verifier must not handle email OTP"); } },
      accountRepository,
      accountTokens,
      () => now,
    );
    const sessionInput = {
      platform: "macos" as const,
      clientInstallationId,
      displayName: "Database Mac",
      appVersion: "0.4.0-test",
      idempotencyKey: "5e39096f-c43d-47ce-8edc-a0ccfd67ec8c",
    };
    const emailSession = await accountService.exchangeEmailIdentity(identity, sessionInput);
    const replayedIdentity = await service.verifyChallenge({
      challengeId: next.challengeId,
      email: "person+db@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId,
      exchangeIdempotencyKey: "successful-exchange-request",
    });
    assert.deepEqual(
      await accountService.exchangeEmailIdentity(replayedIdentity, sessionInput),
      emailSession,
    );

    const googleSession = await new AccountService(
      { verify: async () => ({
        provider: "google",
        issuer: "https://accounts.google.com",
        subject: "google-subject-with-matching-display-email",
        email: "person+db@example.com",
      }) },
      accountRepository,
      accountTokens,
      () => now,
    ).exchangeGoogleProof({
      platform: "macos",
      idToken: "not-persisted-google-proof",
      nonce: "0123456789abcdef",
      clientInstallationId: randomUUID(),
      displayName: "Other Mac",
      appVersion: "0.4.0-test",
      idempotencyKey: randomUUID(),
    });
    assert.notEqual(googleSession.account.id, emailSession.account.id);
    assert.equal((await pool.query<{ version: number }>(
      "SELECT version FROM gateway_schema_state WHERE singleton = true",
    )).rows[0].version, 15);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

function hasCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null && "code" in error
    && (error as { code: unknown }).code === code;
}
