import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";
import { Pool } from "pg";
import { AccountService } from "./account/account-service.js";
import { PostgresAccountRetentionStore } from "./account/account-retention.js";
import { EmailOtpService } from "./account/email-otp-service.js";
import { EmailOtpSecurity } from "./account/email-otp.js";
import type { VerifiedExternalIdentity } from "./account/model.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresEmailOtpStore } from "./account/postgres-email-otp-store.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
const day = 24 * 60 * 60 * 1_000;

test("PostgreSQL account deletion revokes access immediately and removes Cloud PII once after 30 days", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_deletion_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 8,
    options: `-c search_path=${schema}`,
  });
  const codec = new TokenCodec("account-deletion-integration-key-at-least-32-bytes");
  const repository = new PostgresAccountRepository(pool, codec);
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    assert.equal((await pool.query<{ name: string | null }>(
      "SELECT to_regclass('accounts_deletion_due_idx')::text AS name",
    )).rows[0].name, "accounts_deletion_due_idx");

    const deletionRequestedAt = new Date();
    let identity = googleIdentity("delete-subject", "delete-person@example.invalid");
    const deletedIdentity = identity;
    const service = new AccountService(
      { verify: async () => identity },
      repository,
      codec,
      () => deletionRequestedAt,
    );

    const desktop = await exchange(service, "macos", randomUUID(), "Delete person's Mac");
    const phoneClientInstallationId = randomUUID();
    const phone = await exchange(service, "android", phoneClientInstallationId, "Delete person's phone");
    const deletedAccountId = desktop.account.id;
    assert.equal(phone.account.id, deletedAccountId);

    identity = googleIdentity("keeper-subject", "keeper@example.invalid");
    const keeper = await exchange(service, "macos", randomUUID(), "Keeper Mac");
    const keeperAccountId = keeper.account.id;
    const deletedBindingId = await seedBinding(
      pool,
      deletedAccountId,
      desktop.installation.id,
      "delete-person-device",
      1,
    );
    const keeperBindingId = await seedBinding(
      pool,
      keeperAccountId,
      keeper.installation.id,
      "keeper-device",
      1,
    );
    const deletedEmailLookupHash = codec.hashContext(
      "device-share-email-v1\u0000delete-person@example.invalid",
    );
    const crossAccountRelations = await seedCrossAccountRelations(pool, {
      deletedAccountId,
      keeperAccountId,
      deletedBindingId,
      keeperBindingId,
      deletedPhoneInstallationId: phone.installation.id,
      deletedEmailLookupHash,
    });

    identity = deletedIdentity;
    const principal = await service.authenticate(`Bearer ${desktop.session.accessToken}`);
    const proof = await service.reauthenticateGoogle(principal, {
      idToken: "fresh-proof-never-persisted",
      nonce: "1234567890abcdef",
      scope: "account.delete",
      idempotencyKey: randomUUID(),
    });
    const deletionKey = randomUUID();
    await service.requestAccountDeletion(
      `Bearer ${desktop.session.accessToken}`,
      proof.grant,
      deletionKey,
      true,
    );
    await service.requestAccountDeletion(
      `Bearer ${desktop.session.accessToken}`,
      proof.grant,
      deletionKey,
      true,
    );
    await assert.rejects(
      service.requestAccountDeletion(
        `Bearer ${desktop.session.accessToken}`,
        codec.issueReauthenticationGrant(),
        deletionKey,
        true,
      ),
      (error: unknown) => hasCode(error, "HR-ACCOUNT-005"),
    );

    const pending = (await pool.query<{
      status: string;
      deletion_requested_at: Date;
      deletion_due_at: Date;
    }>(
      `SELECT status, deletion_requested_at, deletion_due_at
         FROM accounts WHERE id = $1`,
      [deletedAccountId],
    )).rows[0];
    assert.equal(pending.status, "pending_deletion");
    assert.equal(pending.deletion_requested_at.toISOString(), deletionRequestedAt.toISOString());
    assert.equal(
      pending.deletion_due_at.toISOString(),
      new Date(deletionRequestedAt.getTime() + 30 * day).toISOString(),
    );
    assert.equal(await count(pool, "account_sessions", "account_id = $1 AND revoked_at IS NULL", deletedAccountId), 0);
    assert.equal(await count(pool, "installations", "account_id = $1 AND revoked_at IS NULL", deletedAccountId), 0);
    assert.equal(await count(pool, "connector_bindings", "account_id = $1 AND status <> 'revoked'", deletedAccountId), 0);
    assert.equal(await count(pool, "account_device_preferences", "account_id = $1", deletedAccountId), 0);
    assert.deepEqual((await pool.query<{ owner_account_id: string; status: string }>(
      `SELECT owner_account_id, status FROM device_access_grants
        WHERE owner_account_id = $1 OR grantee_account_id = $1
        ORDER BY owner_account_id`,
      [deletedAccountId],
    )).rows.map(({ owner_account_id, status }) => ({ owner_account_id, status })), [
      { owner_account_id: deletedAccountId, status: "revoked" },
      { owner_account_id: keeperAccountId, status: "left" },
    ].sort((left, right) => left.owner_account_id.localeCompare(right.owner_account_id)));
    assert.equal(await count(
      pool,
      "device_share_invitations",
      "owner_account_id = $1 AND status = 'cancelled'",
      deletedAccountId,
    ), 1);
    assert.equal(await count(
      pool,
      "device_share_invitations",
      "trim(target_email_lookup_hash) = $1 AND status = 'cancelled'",
      deletedEmailLookupHash,
    ), 1);
    assert.equal(await count(
      pool,
      "account_deletion_email_hashes",
      "account_id = $1",
      deletedAccountId,
    ), 1);
    await assert.rejects(
      service.authenticate(`Bearer ${phone.session.accessToken}`),
      (error: unknown) => hasCode(error, "HR-ACCOUNT-012"),
    );
    await assert.rejects(
      service.refresh({
        refreshToken: phone.session.refreshToken,
        clientInstallationId: phoneClientInstallationId,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => hasCode(error, "HR-ACCOUNT-012"),
    );
    identity = deletedIdentity;
    await assert.rejects(
      exchange(service, "android", randomUUID(), "Cannot sign in while deleting"),
      (error: unknown) => hasCode(error, "HR-ACCOUNT-012"),
    );
    identity = googleIdentity("keeper-subject", "keeper@example.invalid");
    assert.equal(
      (await service.authenticate(`Bearer ${keeper.session.accessToken}`)).account.id,
      keeperAccountId,
    );

    const retention = new PostgresAccountRetentionStore(pool);
    const dueAt = new Date(deletionRequestedAt.getTime() + 30 * day);
    const beforeDue = await retention.sweep(new Date(dueAt.getTime() - 1), 1_000);
    assert.equal(beforeDue.accountDeletionRows, 0);
    assert.equal(beforeDue.accountsAnonymized, 0);
    assert.equal(await accountStatus(pool, deletedAccountId), "pending_deletion");
    assert.equal(await count(pool, "external_identities", "account_id = $1", deletedAccountId), 1);

    const idempotencyBefore = await totalCount(pool, "account_idempotency_records");
    const staged = await retention.sweep(dueAt, 1);
    assert.equal(staged.accountsAnonymized, 0);
    assert(staged.accountDeletionRows > 0);
    assert.equal(
      await totalCount(pool, "account_idempotency_records"),
      idempotencyBefore - 1,
      "routine and deletion cleanup share one per-table batch budget",
    );
    assert.equal((await accountStatus(pool, deletedAccountId)), "pending_deletion");

    const [left, right] = await Promise.all([
      new PostgresAccountRetentionStore(pool).sweep(dueAt, 1_000),
      new PostgresAccountRetentionStore(pool).sweep(dueAt, 1_000),
    ]);
    assert.equal(left.accountsAnonymized + right.accountsAnonymized, 1);
    assert.equal(await accountStatus(pool, deletedAccountId), "deleted");
    for (const [table, predicate] of [
      ["external_identities", "account_id = $1"],
      ["installations", "account_id = $1"],
      ["account_sessions", "account_id = $1"],
      ["reauthentication_grants", "account_id = $1"],
      ["account_idempotency_records", "account_id = $1"],
      ["connector_bindings", "account_id = $1"],
      ["account_device_preferences", "account_id = $1"],
      ["account_lifecycle_events", "account_id = $1"],
      ["account_deletion_email_hashes", "account_id = $1"],
      ["device_share_invitations", "owner_account_id = $1 OR accepted_by_account_id = $1"],
      ["device_access_grants", "owner_account_id = $1 OR grantee_account_id = $1"],
    ] as const) {
      assert.equal(await count(pool, table, predicate, deletedAccountId), 0, table);
    }
    const deletionAudit = await pool.query<{
      event_type: string;
      installation_id: string | null;
      metadata: Record<string, unknown>;
    }>(
      "SELECT event_type, installation_id, metadata FROM account_audit_events WHERE account_id = $1",
      [deletedAccountId],
    );
    assert.deepEqual(deletionAudit.rows, [{
      event_type: "account.deleted",
      installation_id: null,
      metadata: {},
    }]);
    assert.equal(await accountStatus(pool, keeperAccountId), "active");
    assert.equal(await count(pool, "external_identities", "account_id = $1", keeperAccountId), 1);
    assert.equal(await count(pool, "connector_bindings", "id = $1 AND status = 'active'", keeperBindingId), 1);
    assert.equal(await count(
      pool,
      "device_share_invitations",
      "trim(target_email_lookup_hash) = $1",
      deletedEmailLookupHash,
    ), 0);
    assert.equal(await count(
      pool,
      "account_audit_events",
      "id = $1",
      crossAccountRelations.targetedInvitationAuditId,
    ), 0);
    assert.equal(await count(
      pool,
      "account_audit_events",
      "id = $1",
      crossAccountRelations.safeKeeperAuditId,
    ), 1);
    await service.requestAccountDeletion(
      `Bearer ${desktop.session.accessToken}`,
      proof.grant,
      deletionKey,
      true,
    );
    const durableReceipt = (await pool.query<{
      idempotency_key_hash: string;
      request_hash: string;
    }>("SELECT idempotency_key_hash, request_hash FROM account_deletion_receipts")).rows;
    assert.equal(durableReceipt.length, 1);
    assert.match(durableReceipt[0].idempotency_key_hash.trim(), /^[a-f0-9]{64}$/);
    assert.match(durableReceipt[0].request_hash.trim(), /^[a-f0-9]{64}$/);
    assert.equal(JSON.stringify(durableReceipt).includes(deletionKey), false);
    assert.equal(JSON.stringify(durableReceipt).includes(desktop.session.accessToken), false);
    assert.equal(JSON.stringify(durableReceipt).includes(proof.grant), false);

    identity = deletedIdentity;
    const registeredAgain = await exchange(service, "android", randomUUID(), "New account after deletion");
    assert.notEqual(registeredAgain.account.id, deletedAccountId);
  } finally {
    await repository.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

test("PostgreSQL suppresses OTP delivery to a deleting email and removes its correlation rows", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `account_deletion_otp_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 6,
    options: `-c search_path=${schema}`,
  });
  const tokens = new TokenCodec("account-deletion-otp-test-key-at-least-32-bytes");
  const repository = new PostgresAccountRepository(pool, tokens);
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }

    const requestedAt = new Date();
    const emailSecurity = new EmailOtpSecurity(
      "account-deletion-email-identity-key-at-least-32-bytes",
      "https://accounts.example.invalid",
      () => 246810,
    );
    let deliveryAttempts = 0;
    const emailOtp = new EmailOtpService(
      new PostgresEmailOtpStore(pool),
      { sendLoginCode: async ({ messageId }) => {
        deliveryAttempts += 1;
        return { providerMessageId: `provider-${messageId}` };
      } },
      emailSecurity,
      () => requestedAt,
    );
    const accounts = new AccountService(undefined, repository, tokens, () => requestedAt);
    const clientInstallationId = randomUUID();
    const initialChallenge = await emailOtp.requestChallenge({
      email: "Deleting.Email@Example.com",
      purpose: "sign_in",
      platform: "macos",
      source: "192.0.2.43",
      clientInstallationId,
    });
    const identity = await emailOtp.verifyChallenge({
      challengeId: initialChallenge.challengeId,
      email: "deleting.email@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId,
      exchangeIdempotencyKey: randomUUID(),
    });
    const session = await accounts.exchangeEmailIdentity(identity, {
      platform: "macos",
      clientInstallationId,
      displayName: "Deleting email Mac",
      appVersion: "0.0.0-test",
      idempotencyKey: randomUUID(),
    });
    const issuedBeforeDeletion = await emailOtp.requestChallenge({
      email: "deleting.email@example.com",
      purpose: "sign_in",
      platform: "web",
      source: "192.0.2.44",
    });
    const principal = await accounts.authenticate(`Bearer ${session.session.accessToken}`);
    const proof = await accounts.reauthenticateEmailIdentity(principal, identity, {
      scope: "account.delete",
      idempotencyKey: randomUUID(),
    });
    await accounts.requestAccountDeletion(
      `Bearer ${session.session.accessToken}`,
      proof.grant,
      randomUUID(),
      true,
    );
    assert((await pool.query<{ invalidated_at: Date | null }>(
      "SELECT invalidated_at FROM email_otp_challenges WHERE id = $1",
      [issuedBeforeDeletion.challengeId],
    )).rows[0].invalidated_at, "an unused code issued before deletion must be invalidated immediately");

    const first = await emailOtp.requestChallenge({
      email: "deleting.email@example.com",
      purpose: "sign_in",
      platform: "web",
      source: "192.0.2.44",
    });
    const cooldownReplay = await emailOtp.requestChallenge({
      email: "deleting.email@example.com",
      purpose: "sign_in",
      platform: "web",
      source: "192.0.2.44",
    });
    assert.deepEqual(cooldownReplay, first, "suppression must preserve the neutral cooldown contract");
    assert.equal(deliveryAttempts, 2, "deletion must suppress every later provider submission");
    assert.deepEqual((await pool.query<{
      delivery_status: string;
      invalidated_at: Date | null;
    }>(
      "SELECT delivery_status, invalidated_at FROM email_otp_challenges WHERE id = $1",
      [first.challengeId],
    )).rows, [{ delivery_status: "suppressed", invalidated_at: null }]);
    await assert.rejects(emailOtp.verifyChallenge({
      challengeId: first.challengeId,
      email: "deleting.email@example.com",
      code: "246810",
      purpose: "sign_in",
      platform: "web",
      exchangeIdempotencyKey: randomUUID(),
    }), (error: unknown) => hasCode(error, "HR-AUTH-009"));

    const hashKinds = (await pool.query<{ hash_kind: string }>(
      `SELECT hash_kind FROM account_deletion_email_hashes
        WHERE account_id = $1 ORDER BY hash_kind`,
      [session.account.id],
    )).rows.map(({ hash_kind }) => hash_kind);
    assert.deepEqual(hashKinds, ["device_share", "email_otp"]);

    const dueAt = new Date(requestedAt.getTime() + 30 * day);
    const completion = await new PostgresAccountRetentionStore(pool).sweep(dueAt, 1_000);
    assert.equal(completion.accountsAnonymized, 1);
    assert.equal(await accountStatus(pool, session.account.id), "deleted");
    assert.equal(await totalCount(pool, "email_otp_challenges"), 0);
    assert.equal(await count(
      pool,
      "account_deletion_email_hashes",
      "account_id = $1",
      session.account.id,
    ), 0);
  } finally {
    await repository.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

function googleIdentity(subject: string, email: string): VerifiedExternalIdentity {
  return {
    provider: "google",
    issuer: "https://accounts.google.com",
    subject,
    email,
    displayName: email.split("@", 1)[0],
    avatarUrl: `https://images.example.invalid/${subject}`,
  };
}

async function exchange(
  service: AccountService,
  platform: "android" | "macos",
  clientInstallationId: string,
  displayName: string,
) {
  return service.exchangeGoogleProof({
    platform,
    idToken: "provider-proof-never-persisted",
    nonce: randomUUID().replaceAll("-", ""),
    clientInstallationId,
    displayName,
    appVersion: "0.0.0-test",
    idempotencyKey: randomUUID(),
  });
}

async function seedBinding(
  pool: Pool,
  accountId: string,
  installationId: string,
  deviceId: string,
  generation: number,
): Promise<string> {
  const id = randomUUID();
  await pool.query(
    `INSERT INTO connector_bindings
       (id, account_id, desktop_installation_id, display_name, device_id, public_key,
        key_algorithm, public_key_fingerprint, generation, status, activated_at,
        connector_online, hermes_reachable, end_to_end_healthy)
     VALUES ($1, $2, $3, $4, $5, $6, 'Ed25519', $7, $8, 'active', now(), true, true, true)`,
    [id, accountId, installationId, `${deviceId} Mac`, deviceId,
      Buffer.alloc(32, generation), hash(deviceId), generation],
  );
  await pool.query(
    "INSERT INTO account_device_preferences (account_id, default_binding_id) VALUES ($1, $2)",
    [accountId, id],
  );
  return id;
}

async function seedCrossAccountRelations(pool: Pool, input: {
  deletedAccountId: string;
  keeperAccountId: string;
  deletedBindingId: string;
  keeperBindingId: string;
  deletedPhoneInstallationId: string;
  deletedEmailLookupHash: string;
}): Promise<{ targetedInvitationAuditId: string; safeKeeperAuditId: string }> {
  await pool.query(
    `INSERT INTO device_access_grants
       (id, binding_id, owner_account_id, grantee_account_id, grantee_email_hint)
     VALUES ($1, $2, $3, $4, 'k***@example.invalid'),
            ($5, $6, $4, $3, 'd***@example.invalid')`,
    [randomUUID(), input.deletedBindingId, input.deletedAccountId, input.keeperAccountId,
      randomUUID(), input.keeperBindingId],
  );
  await pool.query(
    `INSERT INTO device_share_invitations
       (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
        token_hash, status, delivery_status, expires_at, delivered_at)
     VALUES ($1, $2, $3, $4, 'k***@example.invalid', $5, 'pending', 'sent',
             now() + interval '7 days', now())`,
    [randomUUID(), input.deletedBindingId, input.deletedAccountId, hash("pending-target"), hash("pending-token")],
  );
  await pool.query(
    `INSERT INTO device_share_invitations
       (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
        token_hash, status, delivery_status, expires_at, delivered_at,
        accepted_at, accepted_by_account_id)
     VALUES ($1, $2, $3, $4, 'd***@example.invalid', $5, 'accepted', 'sent',
             now() + interval '7 days', now(), now(), $6)`,
    [randomUUID(), input.keeperBindingId, input.keeperAccountId, hash("accepted-target"),
      hash("accepted-token"), input.deletedAccountId],
  );
  const targetedInvitationId = randomUUID();
  await pool.query(
    `INSERT INTO device_share_invitations
       (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
        token_hash, status, delivery_status, expires_at, delivered_at)
     VALUES ($1, $2, $3, $4, 'd***@example.invalid', $5, 'pending', 'sent',
             now() + interval '7 days', now())`,
    [targetedInvitationId, input.keeperBindingId, input.keeperAccountId,
      input.deletedEmailLookupHash, hash("deleted-target-token")],
  );
  const targetedInvitationAuditId = randomUUID();
  const safeKeeperAuditId = randomUUID();
  await pool.query(
    `INSERT INTO account_audit_events (id, account_id, event_type, metadata)
     VALUES ($1, $2, 'device.share.invitation.created', $3::jsonb),
            ($4, $2, 'keeper.safe.event', '{"safe":true}'::jsonb)`,
    [
      targetedInvitationAuditId,
      input.keeperAccountId,
      JSON.stringify({ invitationId: targetedInvitationId, targetEmailHint: "d***@example.invalid" }),
      safeKeeperAuditId,
    ],
  );
  const event = await pool.query<{ sequence: string }>(
    `INSERT INTO account_lifecycle_events
       (account_id, connector_binding_id, event_id, device_id, runtime_session_id,
        stored_session_id, event_kind, lifecycle_state, occurred_at, title)
     VALUES ($1, $2, $3, 'delete-person-device', 'runtime-delete', 'stored-delete',
             'run.completed', 'idle', now(), 'Private lifecycle title')
     RETURNING sequence::text`,
    [input.deletedAccountId, input.deletedBindingId, `delete-event-${randomUUID()}`],
  );
  await pool.query(
    `INSERT INTO account_lifecycle_receipts (event_sequence, account_id, installation_id)
     VALUES ($1, $2, $3)`,
    [event.rows[0].sequence, input.deletedAccountId, input.deletedPhoneInstallationId],
  );
  return { targetedInvitationAuditId, safeKeeperAuditId };
}

async function count(pool: Pool, table: string, predicate: string, value: string): Promise<number> {
  return (await pool.query<{ count: number }>(
    `SELECT count(*)::integer AS count FROM ${table} WHERE ${predicate}`,
    [value],
  )).rows[0].count;
}

async function totalCount(pool: Pool, table: string): Promise<number> {
  return (await pool.query<{ count: number }>(
    `SELECT count(*)::integer AS count FROM ${table}`,
  )).rows[0].count;
}

async function accountStatus(pool: Pool, accountId: string): Promise<string> {
  return (await pool.query<{ status: string }>(
    "SELECT status FROM accounts WHERE id = $1",
    [accountId],
  )).rows[0].status;
}

function hash(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function hasCode(error: unknown, code: string): boolean {
  return typeof error === "object" && error !== null && "code" in error
    && (error as { code: unknown }).code === code;
}
