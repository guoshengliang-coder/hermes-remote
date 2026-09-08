import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";
import { Pool } from "pg";
import {
  PostgresAccountRetentionStore,
  type AccountRetentionCounts,
} from "./account/account-retention.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
const now = new Date("2026-09-08T03:00:00.000Z");
const day = 24 * 60 * 60 * 1_000;

test("PostgreSQL account retention is bounded, dependency-safe, and concurrency-safe", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_retention_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 8,
    options: `-c search_path=${schema}`,
  });
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    for (const indexName of [
      "device_share_invitations_retention_idx",
      "connector_replacement_requests_retention_idx",
      "reauthentication_grants_retention_idx",
      "refresh_tokens_retention_idx",
      "account_sessions_retention_idx",
      "account_lifecycle_events_retention_idx",
      "account_audit_events_retention_idx",
    ]) {
      assert.equal((await pool.query<{ name: string | null }>(
        "SELECT to_regclass($1)::text AS name",
        [indexName],
      )).rows[0].name, indexName);
    }

    const fixture = await seedRetentionFixture(pool);
    const store = new PostgresAccountRetentionStore(pool);
    assert.deepEqual(await store.sweep(now, 2), {
      idempotencyRecords: 2,
      emailWebhookReceipts: 2,
      emailOtpChallenges: 2,
      deviceShareInvitations: 2,
      connectorReplacementRequests: 1,
      reauthenticationGrants: 2,
      refreshTokens: 2,
      accountSessions: 0,
      lifecycleEvents: 2,
      auditEvents: 2,
      accountDeletionRows: 0,
      accountsAnonymized: 0,
    });
    assert.deepEqual(await store.sweep(now, 2), {
      idempotencyRecords: 1,
      emailWebhookReceipts: 1,
      emailOtpChallenges: 1,
      deviceShareInvitations: 1,
      connectorReplacementRequests: 0,
      reauthenticationGrants: 1,
      refreshTokens: 2,
      accountSessions: 1,
      lifecycleEvents: 1,
      auditEvents: 1,
      accountDeletionRows: 0,
      accountsAnonymized: 0,
    });
    assert.deepEqual(await store.sweep(now, 2), emptyCounts());

    for (const [table, expected] of Object.entries({
      account_idempotency_records: 1,
      email_delivery_webhook_receipts: 1,
      email_otp_challenges: 1,
      device_share_invitations: 1,
      connector_replacement_requests: 1,
      reauthentication_grants: 1,
      refresh_tokens: 2,
      account_sessions: 2,
      account_lifecycle_events: 1,
      account_audit_events: 1,
    })) {
      assert.equal((await pool.query<{ count: number }>(
        `SELECT count(*)::integer AS count FROM ${table}`,
      )).rows[0].count, expected, `${table} keeps its exact retention boundary row`);
    }
    assert.equal((await pool.query<{ status: string; connector_online: boolean }>(
      "SELECT status, connector_online FROM connector_bindings WHERE id = $1",
      [fixture.expiredCandidateBindingId],
    )).rows[0].status, "revoked");
    assert.equal((await pool.query<{ parent_id: string | null }>(
      "SELECT parent_id FROM refresh_tokens WHERE id = $1",
      [fixture.activeRefreshTokenId],
    )).rows[0].parent_id, null);
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM account_lifecycle_receipts",
    )).rows[0].count, 0, "deleting lifecycle events cascades their phone receipts");

    await seedExpiredIdempotency(pool, 4, "concurrent");
    const [left, right] = await Promise.all([
      new PostgresAccountRetentionStore(pool).sweep(now, 2),
      new PostgresAccountRetentionStore(pool).sweep(now, 2),
    ]);
    assert.equal(left.idempotencyRecords + right.idempotencyRecords, 4);
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM account_idempotency_records WHERE operation LIKE 'concurrent.%'",
    )).rows[0].count, 0);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

test("due account cleanup serializes with an in-flight OTP correlation write", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_retention_deletion_race_${randomUUID().replaceAll("-", "")}`;
  const applicationName = `hermes-retention-race-${randomUUID()}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 3,
    options: `-c search_path=${schema}`,
  });
  const sweepPool = new Pool({
    connectionString: databaseUrl,
    max: 1,
    application_name: applicationName,
    options: `-c search_path=${schema}`,
  });
  const writer = await pool.connect();
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    const accountId = randomUUID();
    const emailLookupHash = "e".repeat(64);
    await pool.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
    await pool.query(
      `UPDATE accounts
          SET status = 'pending_deletion', deletion_requested_at = $2, deletion_due_at = $3
        WHERE id = $1`,
      [accountId, new Date(now.getTime() - 30 * day), now],
    );
    await pool.query(
      `INSERT INTO account_deletion_email_hashes
         (account_id, hash_kind, email_lookup_hash)
       VALUES ($1, 'email_otp', $2)`,
      [accountId, emailLookupHash],
    );

    await writer.query("BEGIN");
    await writer.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
      `email-otp:email:${emailLookupHash}`,
    ]);
    const sweep = new PostgresAccountRetentionStore(sweepPool).sweep(now, 1_000);
    await waitForAdvisoryWait(admin, applicationName);
    await writer.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, delivery_status, created_at)
       VALUES ($1, 'sign_in', $2, $3, $4, 'web', $5, 'suppressed', $6)`,
      [randomUUID(), emailLookupHash, "c".repeat(64), "d".repeat(64),
        new Date(now.getTime() + 10 * 60_000), now],
    );
    await writer.query("COMMIT");

    const result = await sweep;
    assert.equal(result.accountsAnonymized, 1);
    assert.equal((await pool.query<{ status: string }>(
      "SELECT status FROM accounts WHERE id = $1",
      [accountId],
    )).rows[0].status, "deleted");
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM email_otp_challenges",
    )).rows[0].count, 0);
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM account_deletion_email_hashes",
    )).rows[0].count, 0);
  } finally {
    await writer.query("ROLLBACK").catch(() => {});
    writer.release();
    await sweepPool.end();
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

test("due account cleanup serializes with an in-flight addressed invitation write", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_retention_invite_race_${randomUUID().replaceAll("-", "")}`;
  const applicationName = `hermes-retention-race-${randomUUID()}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 3,
    options: `-c search_path=${schema}`,
  });
  const sweepPool = new Pool({
    connectionString: databaseUrl,
    max: 1,
    application_name: applicationName,
    options: `-c search_path=${schema}`,
  });
  const writer = await pool.connect();
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    const deletingAccountId = randomUUID();
    const ownerAccountId = randomUUID();
    const ownerInstallationId = randomUUID();
    const ownerBindingId = randomUUID();
    const targetEmailLookupHash = "a".repeat(64);
    await pool.query(
      "INSERT INTO accounts (id) VALUES ($1), ($2)",
      [deletingAccountId, ownerAccountId],
    );
    await pool.query(
      `UPDATE accounts
          SET status = 'pending_deletion', deletion_requested_at = $2, deletion_due_at = $3
        WHERE id = $1`,
      [deletingAccountId, new Date(now.getTime() - 30 * day), now],
    );
    await pool.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES ($1, $2, $3, 'desktop', 'macos', 'Invitation owner Mac', 'test')`,
      [ownerInstallationId, ownerAccountId, randomUUID()],
    );
    await seedBinding(pool, {
      id: ownerBindingId,
      accountId: ownerAccountId,
      installationId: ownerInstallationId,
      deviceId: "retention-invitation-owner",
      generation: 1,
      status: "active",
    });
    await pool.query(
      `INSERT INTO account_deletion_email_hashes
         (account_id, hash_kind, email_lookup_hash)
       VALUES ($1, 'device_share', $2)`,
      [deletingAccountId, targetEmailLookupHash],
    );

    await writer.query("BEGIN");
    await writer.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
      `device-share-email:${targetEmailLookupHash}`,
    ]);
    const sweep = new PostgresAccountRetentionStore(sweepPool).sweep(now, 1_000);
    await waitForAdvisoryWait(admin, applicationName);
    await writer.query(
      `INSERT INTO device_share_invitations
         (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
          token_hash, status, delivery_status, expires_at, created_at)
       VALUES ($1, $2, $3, $4, 'd***@example.invalid', $5,
               'pending', 'pending', $6, $7)`,
      [randomUUID(), ownerBindingId, ownerAccountId, targetEmailLookupHash, "b".repeat(64),
        new Date(now.getTime() + day), now],
    );
    await writer.query("COMMIT");

    const result = await sweep;
    assert.equal(result.accountsAnonymized, 1);
    assert.equal((await pool.query<{ status: string }>(
      "SELECT status FROM accounts WHERE id = $1",
      [deletingAccountId],
    )).rows[0].status, "deleted");
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM device_share_invitations",
    )).rows[0].count, 0);
    assert.equal((await pool.query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM account_deletion_email_hashes",
    )).rows[0].count, 0);
    assert.equal((await pool.query<{ status: string }>(
      "SELECT status FROM connector_bindings WHERE id = $1",
      [ownerBindingId],
    )).rows[0].status, "active");
  } finally {
    await writer.query("ROLLBACK").catch(() => {});
    writer.release();
    await sweepPool.end();
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

async function waitForAdvisoryWait(pool: Pool, applicationName: string): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const activity = await pool.query<{ wait_event_type: string | null; wait_event: string | null }>(
      `SELECT wait_event_type, wait_event
         FROM pg_stat_activity
        WHERE application_name = $1 AND state = 'active'`,
      [applicationName],
    );
    if (activity.rows.some(({ wait_event_type, wait_event }) => (
      wait_event_type === "Lock" && wait_event === "advisory"
    ))) return;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 10));
  }
  throw new Error("retention sweep did not wait for the OTP correlation lock");
}

async function seedRetentionFixture(pool: Pool): Promise<{
  expiredCandidateBindingId: string;
  activeRefreshTokenId: string;
}> {
  const accountId = randomUUID();
  const installationId = randomUUID();
  const sessionId = randomUUID();
  await pool.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
  await pool.query(
    `INSERT INTO installations
       (id, account_id, client_installation_id, kind, platform, display_name, app_version)
     VALUES ($1, $2, $3, 'desktop', 'macos', 'Retention Mac', 'test')`,
    [installationId, accountId, randomUUID()],
  );
  await pool.query(
    `INSERT INTO account_sessions
       (id, account_id, installation_id, refresh_family_id, access_token_hash, access_expires_at)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [sessionId, accountId, installationId, randomUUID(), "a".repeat(64), new Date(now.getTime() + day)],
  );
  const activeFamilyId = randomUUID();
  await pool.query(
    "UPDATE account_sessions SET refresh_family_id = $2 WHERE id = $1",
    [sessionId, activeFamilyId],
  );
  const activeBindingId = randomUUID();
  const expiredCandidateBindingId = randomUUID();
  await seedBinding(pool, {
    id: activeBindingId,
    accountId,
    installationId,
    deviceId: "retention-active",
    generation: 1,
    status: "active",
  });
  await seedBinding(pool, {
    id: expiredCandidateBindingId,
    accountId,
    installationId,
    deviceId: "retention-candidate",
    generation: 2,
    status: "pending",
    pendingExpiresAt: new Date(now.getTime() - 36 * day),
  });

  await seedExpiredIdempotency(pool, 3, "expired", accountId);
  await pool.query(
    `INSERT INTO account_idempotency_records
       (id, account_id, operation, idempotency_key, request_hash, response_ciphertext, expires_at)
     VALUES ($1, $2, 'boundary', $3, $4, 'protected', $5)`,
    [randomUUID(), accountId, randomUUID(), "b".repeat(64), new Date(now.getTime() + day)],
  );

  for (let index = 0; index < 3; index += 1) {
    const createdAt = new Date(now.getTime() - (36 * day) - index);
    await pool.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, invalidated_at, created_at)
       VALUES ($1, 'sign_in', $2, $3, $4, 'macos', $5, $5, $6)`,
      [randomUUID(), hash("c", index), hash("d", index), hash("e", index), new Date(createdAt.getTime() + 600_000), createdAt],
    );
    await pool.query(
      `INSERT INTO email_delivery_webhook_receipts
         (webhook_id, provider_message_id, message_kind, message_id, event_type,
          event_created_at, received_at)
       VALUES ($1, $2, 'email_otp', $3, 'email.delivered', $4, $4)`,
      [`old_webhook_${index}`, `old_message_${index}`, randomUUID(), createdAt],
    );
    await pool.query(
      `INSERT INTO device_share_invitations
         (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
          token_hash, status, delivery_status, expires_at, created_at)
       VALUES ($1, $2, $3, $4, 'o***@example.invalid', $5,
               'expired', 'sent', $6, $7)`,
      [randomUUID(), activeBindingId, accountId, hash("f", index), hash("1", index), new Date(createdAt.getTime() + 600_000), createdAt],
    );
  }
  const cutoff = new Date(now.getTime() - 35 * day);
  await pool.query(
    `INSERT INTO email_otp_challenges
       (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
        requester_platform, expires_at, invalidated_at, created_at)
     VALUES ($1, 'sign_in', $2, $3, $4, 'macos', $5, $5, $6)`,
    [randomUUID(), "2".repeat(64), "3".repeat(64), "4".repeat(64), new Date(cutoff.getTime() + 600_000), cutoff],
  );
  await pool.query(
    `INSERT INTO email_delivery_webhook_receipts
       (webhook_id, provider_message_id, message_kind, message_id, event_type,
        event_created_at, received_at)
     VALUES ('boundary_webhook', 'boundary_message', 'email_otp', $1,
             'email.delivered', $2, $2)`,
    [randomUUID(), cutoff],
  );
  await pool.query(
    `INSERT INTO device_share_invitations
       (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
        token_hash, status, delivery_status, expires_at, created_at)
     VALUES ($1, $2, $3, $4, 'b***@example.invalid', $5,
             'expired', 'sent', $6, $7)`,
    [randomUUID(), activeBindingId, accountId, "5".repeat(64), "6".repeat(64), new Date(cutoff.getTime() + 600_000), cutoff],
  );

  const replacementGrantId = await seedReauthenticationGrant(
    pool, accountId, installationId, sessionId, new Date(now.getTime() - 36 * day), "7",
  );
  for (let index = 0; index < 2; index += 1) {
    await seedReauthenticationGrant(
      pool, accountId, installationId, sessionId,
      new Date(now.getTime() - (36 * day) - index), String(8 + index),
    );
  }
  await pool.query(
    `INSERT INTO connector_replacement_requests
       (id, account_id, requesting_installation_id, previous_binding_id,
        candidate_binding_id, reauthentication_grant_id, status, created_at, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7, $8)`,
    [randomUUID(), accountId, installationId, activeBindingId, expiredCandidateBindingId,
      replacementGrantId, new Date(now.getTime() - 37 * day), new Date(now.getTime() - 36 * day)],
  );

  const boundaryInstallation = randomUUID();
  await pool.query(
    `INSERT INTO installations
       (id, account_id, client_installation_id, kind, platform, display_name, app_version)
     VALUES ($1, $2, $3, 'desktop', 'macos', 'Boundary Mac', 'test')`,
    [boundaryInstallation, accountId, randomUUID()],
  );
  const boundaryActive = randomUUID();
  const boundaryCandidate = randomUUID();
  await seedBinding(pool, { id: boundaryActive, accountId, installationId: boundaryInstallation, deviceId: "boundary-active", generation: 3, status: "active" });
  await seedBinding(pool, { id: boundaryCandidate, accountId, installationId: boundaryInstallation, deviceId: "boundary-candidate", generation: 4, status: "pending", pendingExpiresAt: cutoff });
  const boundaryReplacementGrant = await seedReauthenticationGrant(
    pool, accountId, boundaryInstallation, sessionId, cutoff, "a",
  );
  await pool.query(
    `INSERT INTO connector_replacement_requests
       (id, account_id, requesting_installation_id, previous_binding_id,
        candidate_binding_id, reauthentication_grant_id, status, created_at, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7, $7)`,
    [randomUUID(), accountId, boundaryInstallation, boundaryActive, boundaryCandidate,
      boundaryReplacementGrant, cutoff],
  );

  const oldSessionId = randomUUID();
  const oldFamilyId = randomUUID();
  await seedSession(pool, oldSessionId, accountId, installationId, oldFamilyId,
    new Date(now.getTime() - 36 * day), "b");
  let parentId: string | null = null;
  for (let index = 0; index < 3; index += 1) {
    const tokenId = randomUUID();
    await seedRefreshToken(
      pool,
      tokenId,
      oldSessionId,
      oldFamilyId,
      parentId,
      new Date(now.getTime() - (36 * day) - (2 - index)),
      String(index + 1),
    );
    parentId = tokenId;
  }

  const boundarySessionId = randomUUID();
  const boundaryFamilyId = randomUUID();
  await seedSession(pool, boundarySessionId, accountId, boundaryInstallation, boundaryFamilyId,
    cutoff, "c");
  await seedRefreshToken(
    pool, randomUUID(), boundarySessionId, boundaryFamilyId, null, cutoff, "4",
  );

  const expiredParentId = randomUUID();
  const activeRefreshTokenId = randomUUID();
  await seedRefreshToken(
    pool, expiredParentId, sessionId, activeFamilyId, null,
    new Date(now.getTime() - 37 * day), "5",
  );
  await seedRefreshToken(
    pool, activeRefreshTokenId, sessionId, activeFamilyId, expiredParentId,
    new Date(now.getTime() + day), "6",
  );
  const phoneInstallationId = randomUUID();
  await pool.query(
    `INSERT INTO installations
       (id, account_id, client_installation_id, kind, platform, display_name, app_version)
     VALUES ($1, $2, $3, 'phone', 'android', 'Retention Phone', 'test')`,
    [phoneInstallationId, accountId, randomUUID()],
  );
  for (let index = 0; index < 3; index += 1) {
    const occurredAt = new Date(now.getTime() - (31 * day) - index);
    const inserted = await pool.query<{ sequence: string }>(
      `INSERT INTO account_lifecycle_events
         (account_id, connector_binding_id, event_id, device_id, runtime_session_id,
          stored_session_id, event_kind, lifecycle_state, occurred_at)
       VALUES ($1, $2, $3, 'retention-active', $4, $5, 'run.completed', 'idle', $6)
       RETURNING sequence::text`,
      [accountId, activeBindingId, `old-event-${index}`, `runtime-${index}`, `stored-${index}`, occurredAt],
    );
    if (index === 0) {
      await pool.query(
        `INSERT INTO account_lifecycle_receipts
           (event_sequence, account_id, installation_id)
         VALUES ($1, $2, $3)`,
        [inserted.rows[0].sequence, accountId, phoneInstallationId],
      );
    }
  }
  const lifecycleCutoff = new Date(now.getTime() - 30 * day);
  await pool.query(
    `INSERT INTO account_lifecycle_events
       (account_id, connector_binding_id, event_id, device_id, runtime_session_id,
        stored_session_id, event_kind, lifecycle_state, occurred_at)
     VALUES ($1, $2, 'boundary-event', 'retention-active', 'boundary-runtime',
             'boundary-stored', 'run.completed', 'idle', $3)`,
    [accountId, activeBindingId, lifecycleCutoff],
  );
  for (let index = 0; index < 3; index += 1) {
    await pool.query(
      `INSERT INTO account_audit_events
         (id, account_id, installation_id, event_type, occurred_at)
       VALUES ($1, $2, $3, 'retention.test', $4)`,
      [randomUUID(), accountId, installationId, new Date(now.getTime() - (181 * day) - index)],
    );
  }
  await pool.query(
    `INSERT INTO account_audit_events
       (id, account_id, installation_id, event_type, occurred_at)
     VALUES ($1, $2, $3, 'retention.boundary', $4)`,
    [randomUUID(), accountId, installationId, new Date(now.getTime() - 180 * day)],
  );
  return { expiredCandidateBindingId, activeRefreshTokenId };
}

async function seedExpiredIdempotency(
  pool: Pool,
  count: number,
  prefix: string,
  knownAccountId?: string,
): Promise<void> {
  let accountId = knownAccountId;
  if (!accountId) {
    accountId = randomUUID();
    await pool.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
  }
  for (let index = 0; index < count; index += 1) {
    await pool.query(
      `INSERT INTO account_idempotency_records
         (id, account_id, operation, idempotency_key, request_hash, response_ciphertext, expires_at)
       VALUES ($1, $2, $3, $4, $5, 'protected', $6)`,
      [randomUUID(), accountId, `${prefix}.${index}`, randomUUID(), hash("c", index), new Date(now.getTime() - day - index)],
    );
  }
}

async function seedBinding(pool: Pool, input: {
  id: string;
  accountId: string;
  installationId: string;
  deviceId: string;
  generation: number;
  status: "active" | "pending";
  pendingExpiresAt?: Date;
}): Promise<void> {
  await pool.query(
    `INSERT INTO connector_bindings
       (id, account_id, desktop_installation_id, display_name, device_id, public_key,
        key_algorithm, public_key_fingerprint, generation, status, pending_expires_at,
        activated_at, connector_online)
     VALUES ($1, $2, $3, 'Retention Mac', $4, $5, 'Ed25519', $6, $7, $8, $9, $10, $11)`,
    [input.id, input.accountId, input.installationId, input.deviceId, Buffer.alloc(32, input.generation),
      hash("d", input.generation), input.generation, input.status, input.pendingExpiresAt ?? null,
      input.status === "active" ? now : null, input.status === "active"],
  );
}

async function seedReauthenticationGrant(
  pool: Pool,
  accountId: string,
  installationId: string,
  sessionId: string,
  expiresAt: Date,
  hashCharacter: string,
): Promise<string> {
  const id = randomUUID();
  await pool.query(
    `INSERT INTO reauthentication_grants
       (id, account_id, installation_id, session_id, scope, token_hash, created_at, expires_at)
     VALUES ($1, $2, $3, $4, 'connector.replace', $5, $6, $7)`,
    [id, accountId, installationId, sessionId, hashCharacter.repeat(64), new Date(expiresAt.getTime() - 600_000), expiresAt],
  );
  return id;
}

async function seedSession(
  pool: Pool,
  id: string,
  accountId: string,
  installationId: string,
  familyId: string,
  accessExpiresAt: Date,
  hashCharacter: string,
): Promise<void> {
  await pool.query(
    `INSERT INTO account_sessions
       (id, account_id, installation_id, refresh_family_id, access_token_hash, access_expires_at)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [id, accountId, installationId, familyId, hashCharacter.repeat(64), accessExpiresAt],
  );
}

async function seedRefreshToken(
  pool: Pool,
  id: string,
  sessionId: string,
  familyId: string,
  parentId: string | null,
  expiresAt: Date,
  hashCharacter: string,
): Promise<void> {
  await pool.query(
    `INSERT INTO refresh_tokens
       (id, session_id, family_id, parent_id, token_hash, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [id, sessionId, familyId, parentId, hashCharacter.repeat(64), expiresAt],
  );
}

function hash(character: string, index: number): string {
  const suffix = index.toString(16);
  return `${character.repeat(64 - suffix.length)}${suffix}`;
}

function emptyCounts(): AccountRetentionCounts {
  return {
    idempotencyRecords: 0,
    emailWebhookReceipts: 0,
    emailOtpChallenges: 0,
    deviceShareInvitations: 0,
    connectorReplacementRequests: 0,
    reauthenticationGrants: 0,
    refreshTokens: 0,
    accountSessions: 0,
    lifecycleEvents: 0,
    auditEvents: 0,
    accountDeletionRows: 0,
    accountsAnonymized: 0,
  };
}
