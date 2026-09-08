import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { EMAIL_DELIVERY_CORRELATION_RETENTION_MS } from "./email-delivery.js";

export const ACCOUNT_RETENTION_BATCH_SIZE = 1_000;
export const ACCOUNT_RETENTION_INITIAL_DELAY_MS = 60_000;
export const ACCOUNT_RETENTION_SWEEP_INTERVAL_MS = 6 * 60 * 60 * 1_000;
export const ACCOUNT_RETENTION_CATCH_UP_DELAY_MS = 1_000;
export const ACCOUNT_CREDENTIAL_TOMBSTONE_RETENTION_MS = 35 * 24 * 60 * 60 * 1_000;
export const ACCOUNT_LIFECYCLE_RETENTION_MS = 30 * 24 * 60 * 60 * 1_000;
export const ACCOUNT_AUDIT_RETENTION_MS = 180 * 24 * 60 * 60 * 1_000;

export interface AccountRetentionCounts {
  idempotencyRecords: number;
  emailWebhookReceipts: number;
  emailOtpChallenges: number;
  deviceShareInvitations: number;
  connectorReplacementRequests: number;
  reauthenticationGrants: number;
  refreshTokens: number;
  accountSessions: number;
  lifecycleEvents: number;
  auditEvents: number;
  accountDeletionRows: number;
  accountsAnonymized: number;
}

export interface AccountRetentionMetrics {
  observedAt: string;
  running: boolean;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  deletedSinceStart: AccountRetentionCounts;
}

export interface AccountRetentionStore {
  sweep(now: Date, batchSize: number): Promise<AccountRetentionCounts>;
}

export class PostgresAccountRetentionStore implements AccountRetentionStore {
  private readonly lifecycleRetentionMilliseconds: number;
  private readonly auditRetentionMilliseconds: number;

  constructor(
    private readonly pool: Pool,
    options: {
      lifecycleRetentionMilliseconds?: number;
      auditRetentionMilliseconds?: number;
    } = {},
  ) {
    this.lifecycleRetentionMilliseconds = retentionWindow(
      options.lifecycleRetentionMilliseconds ?? ACCOUNT_LIFECYCLE_RETENTION_MS,
      "lifecycleRetentionMilliseconds",
    );
    this.auditRetentionMilliseconds = retentionWindow(
      options.auditRetentionMilliseconds ?? ACCOUNT_AUDIT_RETENTION_MS,
      "auditRetentionMilliseconds",
    );
  }

  async sweep(now: Date, batchSize: number): Promise<AccountRetentionCounts> {
    if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > ACCOUNT_RETENTION_BATCH_SIZE) {
      throw new Error(`account retention batchSize must be between 1 and ${ACCOUNT_RETENTION_BATCH_SIZE}`);
    }
    const correlationCutoff = new Date(
      now.getTime() - EMAIL_DELIVERY_CORRELATION_RETENTION_MS,
    );
    const credentialCutoff = new Date(
      now.getTime() - ACCOUNT_CREDENTIAL_TOMBSTONE_RETENTION_MS,
    );
    const lifecycleCutoff = new Date(now.getTime() - this.lifecycleRetentionMilliseconds);
    const auditCutoff = new Date(now.getTime() - this.auditRetentionMilliseconds);
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const idempotencyRecords = await deleteBatch(
        client,
        `SELECT id FROM account_idempotency_records
          WHERE expires_at <= $1
          ORDER BY expires_at, id
          LIMIT $2
          FOR UPDATE SKIP LOCKED`,
        "account_idempotency_records",
        [now, batchSize],
      );
      const emailWebhookReceipts = await deleteBatch(
        client,
        `SELECT webhook_id FROM email_delivery_webhook_receipts
          WHERE received_at < $1
          ORDER BY received_at, webhook_id
          LIMIT $2
          FOR UPDATE SKIP LOCKED`,
        "email_delivery_webhook_receipts",
        [correlationCutoff, batchSize],
        "webhook_id",
      );
      const emailOtpChallenges = await deleteBatch(
        client,
        `SELECT id FROM email_otp_challenges
          WHERE created_at < $1 AND expires_at < $2
          ORDER BY created_at, id
          LIMIT $3
          FOR UPDATE SKIP LOCKED`,
        "email_otp_challenges",
        [correlationCutoff, now, batchSize],
      );
      const deviceShareInvitations = await deleteBatch(
        client,
        `SELECT i.id
           FROM device_share_invitations i
          WHERE i.created_at < $1
            AND i.expires_at < $2
            AND NOT EXISTS (
              SELECT 1 FROM account_idempotency_records d
               WHERE d.device_share_invitation_id = i.id
            )
          ORDER BY i.created_at, i.id
          LIMIT $3
          FOR UPDATE OF i SKIP LOCKED`,
        "device_share_invitations",
        [correlationCutoff, now, batchSize],
      );
      const connectorReplacementRequests = await deleteExpiredReplacementRequests(
        client,
        credentialCutoff,
        batchSize,
      );
      const reauthenticationGrants = await deleteBatch(
        client,
        `SELECT g.id
           FROM reauthentication_grants g
          WHERE g.expires_at < $1
            AND NOT EXISTS (
              SELECT 1 FROM account_idempotency_records d
               WHERE d.reauthentication_grant_id = g.id
            )
            AND NOT EXISTS (
              SELECT 1 FROM connector_replacement_requests r
               WHERE r.reauthentication_grant_id = g.id
            )
          ORDER BY g.expires_at, g.id
          LIMIT $2
          FOR UPDATE OF g SKIP LOCKED`,
        "reauthentication_grants",
        [credentialCutoff, batchSize],
      );
      const refreshTokens = await deleteExpiredRefreshTokens(
        client,
        credentialCutoff,
        batchSize,
      );
      const accountSessions = await deleteBatch(
        client,
        `SELECT s.id
           FROM account_sessions s
          WHERE s.access_expires_at < $1
            AND NOT EXISTS (SELECT 1 FROM refresh_tokens r WHERE r.session_id = s.id)
            AND NOT EXISTS (SELECT 1 FROM reauthentication_grants g WHERE g.session_id = s.id)
            AND NOT EXISTS (SELECT 1 FROM account_idempotency_records d WHERE d.session_id = s.id)
          ORDER BY s.access_expires_at, s.id
          LIMIT $2
          FOR UPDATE OF s SKIP LOCKED`,
        "account_sessions",
        [credentialCutoff, batchSize],
      );
      const lifecycleEvents = await deleteBatch(
        client,
        `SELECT e.sequence
           FROM account_lifecycle_events e
          WHERE e.occurred_at < $1
          ORDER BY e.occurred_at, e.sequence
          LIMIT $2
          FOR UPDATE OF e SKIP LOCKED`,
        "account_lifecycle_events",
        [lifecycleCutoff, batchSize],
        "sequence",
      );
      const auditEvents = await deleteBatch(
        client,
        `SELECT e.id
           FROM account_audit_events e
          WHERE e.occurred_at < $1
          ORDER BY e.occurred_at, e.id
          LIMIT $2
          FOR UPDATE OF e SKIP LOCKED`,
        "account_audit_events",
        [auditCutoff, batchSize],
      );
      const accountDeletion = await processDueAccountDeletion(client, now, {
        idempotencyRecords: batchSize - idempotencyRecords,
        emailOtpChallenges: batchSize - emailOtpChallenges,
        deviceShareInvitations: batchSize - deviceShareInvitations,
        connectorReplacementRequests: batchSize - connectorReplacementRequests,
        reauthenticationGrants: batchSize - reauthenticationGrants,
        refreshTokens: batchSize - refreshTokens,
        accountSessions: batchSize - accountSessions,
        lifecycleEvents: batchSize - lifecycleEvents,
        auditEvents: batchSize - auditEvents,
        otherTables: batchSize,
      });
      await client.query("COMMIT");
      return {
        idempotencyRecords,
        emailWebhookReceipts,
        emailOtpChallenges,
        deviceShareInvitations,
        connectorReplacementRequests,
        reauthenticationGrants,
        refreshTokens,
        accountSessions,
        lifecycleEvents,
        auditEvents,
        accountDeletionRows: accountDeletion.deletedRows,
        accountsAnonymized: accountDeletion.accountAnonymized ? 1 : 0,
      };
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }
}

export class AccountRetentionScheduler {
  private timer?: NodeJS.Timeout;
  private activeSweep?: Promise<AccountRetentionCounts>;
  private stopped = false;
  private lastAttemptAt?: Date;
  private lastSuccessAt?: Date;
  private lastFailureAt?: Date;
  private readonly deletedSinceStart = emptyCounts();

  constructor(
    private readonly store: AccountRetentionStore,
    private readonly options: {
      now?: () => Date;
      batchSize?: number;
      initialDelayMilliseconds?: number;
      intervalMilliseconds?: number;
      catchUpDelayMilliseconds?: number;
      reportFailure?: () => void;
    } = {},
  ) {
    validateDelay(options.initialDelayMilliseconds, "initialDelayMilliseconds");
    validateDelay(options.intervalMilliseconds, "intervalMilliseconds");
    validateDelay(options.catchUpDelayMilliseconds, "catchUpDelayMilliseconds");
  }

  start(): void {
    if (this.stopped || this.timer || this.activeSweep) return;
    this.schedule(this.options.initialDelayMilliseconds ?? ACCOUNT_RETENTION_INITIAL_DELAY_MS);
  }

  runNow(): Promise<AccountRetentionCounts> {
    if (this.stopped) return Promise.resolve(emptyCounts());
    if (this.activeSweep) return this.activeSweep;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    const now = this.now();
    this.lastAttemptAt = now;
    const batchSize = this.options.batchSize ?? ACCOUNT_RETENTION_BATCH_SIZE;
    const intervalDelay = this.options.intervalMilliseconds ?? ACCOUNT_RETENTION_SWEEP_INTERVAL_MS;
    const catchUpDelay = this.options.catchUpDelayMilliseconds ?? ACCOUNT_RETENTION_CATCH_UP_DELAY_MS;
    let nextDelay = intervalDelay;
    const sweep = this.store.sweep(
      now,
      batchSize,
    );
    this.activeSweep = sweep;
    void sweep.then((counts) => {
      this.lastSuccessAt = this.now();
      addCounts(this.deletedSinceStart, counts);
      if (needsCatchUp(counts, batchSize)) nextDelay = catchUpDelay;
    }).catch(() => {
      this.lastFailureAt = this.now();
      this.options.reportFailure?.();
    }).finally(() => {
      if (this.activeSweep === sweep) this.activeSweep = undefined;
      if (!this.stopped) {
        this.schedule(nextDelay);
      }
    });
    return sweep;
  }

  snapshot(): AccountRetentionMetrics {
    return {
      observedAt: this.now().toISOString(),
      running: Boolean(this.activeSweep),
      lastAttemptAt: this.lastAttemptAt?.toISOString() ?? null,
      lastSuccessAt: this.lastSuccessAt?.toISOString() ?? null,
      lastFailureAt: this.lastFailureAt?.toISOString() ?? null,
      deletedSinceStart: { ...this.deletedSinceStart },
    };
  }

  async close(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    await this.activeSweep?.catch(() => {});
  }

  private schedule(milliseconds: number): void {
    if (this.stopped || this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.runNow().catch(() => {});
    }, milliseconds);
    this.timer.unref();
  }

  private now(): Date {
    return this.options.now?.() ?? new Date();
  }
}

async function deleteExpiredReplacementRequests(
  client: PoolClient,
  cutoff: Date,
  batchSize: number,
): Promise<number> {
  const candidates = await client.query<{ id: string; candidate_binding_id: string }>(
    `SELECT r.id, r.candidate_binding_id
       FROM connector_replacement_requests r
      WHERE r.expires_at < $1
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d
           WHERE d.connector_replacement_request_id = r.id
        )
      ORDER BY r.expires_at, r.id
      LIMIT $2
      FOR UPDATE OF r SKIP LOCKED`,
    [cutoff, batchSize],
  );
  if (candidates.rows.length === 0) return 0;
  const ids = candidates.rows.map(({ id }) => id);
  const candidateBindingIds = candidates.rows.map(({ candidate_binding_id }) => candidate_binding_id);
  await client.query(
    `UPDATE connector_bindings
        SET status = 'revoked', revoked_at = COALESCE(revoked_at, now()), connector_online = false
      WHERE id = ANY($1::uuid[]) AND status = 'pending'`,
    [candidateBindingIds],
  );
  const deleted = await client.query(
    "DELETE FROM connector_replacement_requests WHERE id = ANY($1::uuid[])",
    [ids],
  );
  return deleted.rowCount ?? 0;
}

async function deleteExpiredRefreshTokens(
  client: PoolClient,
  cutoff: Date,
  batchSize: number,
): Promise<number> {
  const candidates = await client.query<{ id: string }>(
    `SELECT r.id
       FROM refresh_tokens r
      WHERE r.expires_at < $1
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d WHERE d.refresh_token_id = r.id
        )
      ORDER BY r.expires_at, r.id
      LIMIT $2
      FOR UPDATE OF r SKIP LOCKED`,
    [cutoff, batchSize],
  );
  if (candidates.rows.length === 0) return 0;
  const ids = candidates.rows.map(({ id }) => id);
  await client.query(
    "UPDATE refresh_tokens SET parent_id = NULL WHERE parent_id = ANY($1::uuid[])",
    [ids],
  );
  const deleted = await client.query(
    "DELETE FROM refresh_tokens WHERE id = ANY($1::uuid[])",
    [ids],
  );
  return deleted.rowCount ?? 0;
}

async function processDueAccountDeletion(
  client: PoolClient,
  now: Date,
  budgets: {
    idempotencyRecords: number;
    emailOtpChallenges: number;
    deviceShareInvitations: number;
    connectorReplacementRequests: number;
    reauthenticationGrants: number;
    refreshTokens: number;
    accountSessions: number;
    lifecycleEvents: number;
    auditEvents: number;
    otherTables: number;
  },
): Promise<{ deletedRows: number; accountAnonymized: boolean }> {
  const due = await client.query<{ id: string }>(
    `SELECT id
       FROM accounts
      WHERE status = 'pending_deletion' AND deletion_due_at <= $1
      ORDER BY deletion_due_at, id
      LIMIT 1
      FOR UPDATE SKIP LOCKED`,
    [now],
  );
  const accountId = due.rows[0]?.id;
  if (!accountId) return { deletedRows: 0, accountAnonymized: false };

  const deletionHashes = await client.query<{
    hash_kind: "device_share" | "email_otp";
    email_lookup_hash: string;
  }>(
    `SELECT hash_kind, trim(email_lookup_hash) AS email_lookup_hash
       FROM account_deletion_email_hashes
      WHERE account_id = $1`,
    [accountId],
  );
  const correlationLocks = deletionHashes.rows.map(({ hash_kind, email_lookup_hash }) => (
    hash_kind === "device_share"
      ? `device-share-email:${email_lookup_hash}`
      : `email-otp:email:${email_lookup_hash}`
  )).sort();
  for (const lockKey of correlationLocks) {
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [lockKey]);
  }

  let deletedRows = 0;
  let auditBudget = budgets.auditEvents;
  const externalAuditEvents = await deleteBatch(
    client,
    `SELECT e.id
       FROM account_audit_events e
      WHERE e.account_id <> $1
        AND (
          EXISTS (
            SELECT 1
              FROM device_share_invitations i
              LEFT JOIN account_deletion_email_hashes h
                ON h.account_id = $1
               AND h.hash_kind = 'device_share'
               AND trim(h.email_lookup_hash) = trim(i.target_email_lookup_hash)
             WHERE (i.owner_account_id = $1 OR i.accepted_by_account_id = $1 OR h.account_id IS NOT NULL)
               AND e.metadata->>'invitationId' = i.id::text
          )
          OR EXISTS (
            SELECT 1 FROM device_access_grants g
             WHERE (g.owner_account_id = $1 OR g.grantee_account_id = $1)
               AND e.metadata->>'grantId' = g.id::text
          )
        )
      ORDER BY e.occurred_at, e.id
      LIMIT $2
      FOR UPDATE OF e SKIP LOCKED`,
    "account_audit_events",
    [accountId, auditBudget],
  );
  deletedRows += externalAuditEvents;
  auditBudget -= externalAuditEvents;
  deletedRows += await deleteBatch(
    client,
    `SELECT id FROM account_idempotency_records
      WHERE account_id = $1 ORDER BY created_at, id LIMIT $2 FOR UPDATE SKIP LOCKED`,
    "account_idempotency_records",
    [accountId, budgets.idempotencyRecords],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT c.id
       FROM email_otp_challenges c
       JOIN account_deletion_email_hashes h
         ON h.account_id = $1
        AND h.hash_kind = 'email_otp'
        AND trim(h.email_lookup_hash) = trim(c.email_lookup_hash)
      ORDER BY c.created_at, c.id
      LIMIT $2
      FOR UPDATE OF c SKIP LOCKED`,
    "email_otp_challenges",
    [accountId, budgets.emailOtpChallenges],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT i.id FROM device_share_invitations i
      LEFT JOIN account_deletion_email_hashes h
        ON h.account_id = $1
       AND h.hash_kind = 'device_share'
       AND trim(h.email_lookup_hash) = trim(i.target_email_lookup_hash)
      WHERE (i.owner_account_id = $1 OR i.accepted_by_account_id = $1 OR h.account_id IS NOT NULL)
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d WHERE d.device_share_invitation_id = i.id
        )
        AND NOT EXISTS (
          SELECT 1 FROM account_audit_events e
           WHERE e.account_id <> $1 AND e.metadata->>'invitationId' = i.id::text
        )
      ORDER BY i.created_at, i.id LIMIT $2 FOR UPDATE OF i SKIP LOCKED`,
    "device_share_invitations",
    [accountId, budgets.deviceShareInvitations],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT g.id FROM device_access_grants g
      WHERE (g.owner_account_id = $1 OR g.grantee_account_id = $1)
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d WHERE d.device_access_grant_id = g.id
        )
        AND NOT EXISTS (
          SELECT 1 FROM account_audit_events e
           WHERE e.account_id <> $1 AND e.metadata->>'grantId' = g.id::text
        )
      ORDER BY g.granted_at, g.id LIMIT $2 FOR UPDATE OF g SKIP LOCKED`,
    "device_access_grants",
    [accountId, budgets.otherTables],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT r.id FROM connector_replacement_requests r
      WHERE r.account_id = $1
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d
           WHERE d.connector_replacement_request_id = r.id
        )
      ORDER BY r.created_at, r.id LIMIT $2 FOR UPDATE OF r SKIP LOCKED`,
    "connector_replacement_requests",
    [accountId, budgets.connectorReplacementRequests],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT g.id FROM reauthentication_grants g
      WHERE g.account_id = $1
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d WHERE d.reauthentication_grant_id = g.id
        )
        AND NOT EXISTS (
          SELECT 1 FROM connector_replacement_requests r WHERE r.reauthentication_grant_id = g.id
        )
      ORDER BY g.created_at, g.id LIMIT $2 FOR UPDATE OF g SKIP LOCKED`,
    "reauthentication_grants",
    [accountId, budgets.reauthenticationGrants],
  );

  const refresh = await client.query<{ id: string }>(
    `SELECT r.id
       FROM refresh_tokens r
       JOIN account_sessions s ON s.id = r.session_id
      WHERE s.account_id = $1
        AND NOT EXISTS (
          SELECT 1 FROM account_idempotency_records d WHERE d.refresh_token_id = r.id
        )
      ORDER BY r.issued_at, r.id LIMIT $2 FOR UPDATE OF r SKIP LOCKED`,
    [accountId, budgets.refreshTokens],
  );
  if (refresh.rows.length > 0) {
    const ids = refresh.rows.map(({ id }) => id);
    await client.query("UPDATE refresh_tokens SET parent_id = NULL WHERE parent_id = ANY($1::uuid[])", [ids]);
    deletedRows += (await client.query(
      "DELETE FROM refresh_tokens WHERE id = ANY($1::uuid[])",
      [ids],
    )).rowCount ?? 0;
  }
  deletedRows += await deleteBatch(
    client,
    `SELECT s.id FROM account_sessions s
      WHERE s.account_id = $1
        AND NOT EXISTS (SELECT 1 FROM refresh_tokens r WHERE r.session_id = s.id)
        AND NOT EXISTS (SELECT 1 FROM reauthentication_grants g WHERE g.session_id = s.id)
        AND NOT EXISTS (SELECT 1 FROM account_idempotency_records d WHERE d.session_id = s.id)
      ORDER BY s.created_at, s.id LIMIT $2 FOR UPDATE OF s SKIP LOCKED`,
    "account_sessions",
    [accountId, budgets.accountSessions],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT e.sequence FROM account_lifecycle_events e
      WHERE e.account_id = $1 ORDER BY e.sequence LIMIT $2 FOR UPDATE OF e SKIP LOCKED`,
    "account_lifecycle_events",
    [accountId, budgets.lifecycleEvents],
    "sequence",
  );
  deletedRows += (await client.query(
    "DELETE FROM account_device_preferences WHERE account_id = $1",
    [accountId],
  )).rowCount ?? 0;
  deletedRows += await deleteBatch(
    client,
    `SELECT b.id FROM connector_bindings b
      WHERE b.account_id = $1
        AND NOT EXISTS (SELECT 1 FROM connector_replacement_requests r WHERE r.previous_binding_id = b.id OR r.candidate_binding_id = b.id)
        AND NOT EXISTS (SELECT 1 FROM device_share_invitations i WHERE i.binding_id = b.id)
        AND NOT EXISTS (SELECT 1 FROM device_access_grants g WHERE g.binding_id = b.id)
        AND NOT EXISTS (SELECT 1 FROM account_lifecycle_events e WHERE e.connector_binding_id = b.id)
        AND NOT EXISTS (SELECT 1 FROM account_device_preferences p WHERE p.default_binding_id = b.id)
        AND NOT EXISTS (SELECT 1 FROM account_idempotency_records d WHERE d.connector_binding_id = b.id)
      ORDER BY b.created_at, b.id LIMIT $2 FOR UPDATE OF b SKIP LOCKED`,
    "connector_bindings",
    [accountId, budgets.otherTables],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT e.id FROM account_audit_events e
      WHERE e.account_id = $1 ORDER BY e.occurred_at, e.id LIMIT $2 FOR UPDATE OF e SKIP LOCKED`,
    "account_audit_events",
    [accountId, auditBudget],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT i.id FROM external_identities i
      WHERE i.account_id = $1 ORDER BY i.created_at, i.id LIMIT $2 FOR UPDATE OF i SKIP LOCKED`,
    "external_identities",
    [accountId, budgets.otherTables],
  );
  deletedRows += await deleteBatch(
    client,
    `SELECT i.id FROM installations i
      WHERE i.account_id = $1
        AND NOT EXISTS (SELECT 1 FROM account_sessions s WHERE s.installation_id = i.id)
        AND NOT EXISTS (SELECT 1 FROM reauthentication_grants g WHERE g.installation_id = i.id)
        AND NOT EXISTS (SELECT 1 FROM connector_replacement_requests r WHERE r.requesting_installation_id = i.id)
        AND NOT EXISTS (SELECT 1 FROM connector_bindings b WHERE b.desktop_installation_id = i.id)
        AND NOT EXISTS (SELECT 1 FROM account_audit_events e WHERE e.installation_id = i.id)
        AND NOT EXISTS (SELECT 1 FROM account_lifecycle_receipts r WHERE r.installation_id = i.id)
      ORDER BY i.created_at, i.id LIMIT $2 FOR UPDATE OF i SKIP LOCKED`,
    "installations",
    [accountId, budgets.otherTables],
  );
  const deletionEmailHashes = await client.query(
    `WITH expired AS (
       SELECT h.account_id, h.hash_kind, h.email_lookup_hash
         FROM account_deletion_email_hashes h
        WHERE h.account_id = $1
          AND (
            (h.hash_kind = 'device_share' AND NOT EXISTS (
              SELECT 1 FROM device_share_invitations i
               WHERE trim(i.target_email_lookup_hash) = trim(h.email_lookup_hash)
            ))
            OR
            (h.hash_kind = 'email_otp' AND NOT EXISTS (
              SELECT 1 FROM email_otp_challenges c
               WHERE trim(c.email_lookup_hash) = trim(h.email_lookup_hash)
            ))
          )
        ORDER BY h.hash_kind, h.email_lookup_hash
        LIMIT $2
        FOR UPDATE OF h SKIP LOCKED
     )
     DELETE FROM account_deletion_email_hashes target
      USING expired
      WHERE target.account_id = expired.account_id
        AND target.hash_kind = expired.hash_kind
        AND target.email_lookup_hash = expired.email_lookup_hash`,
    [accountId, budgets.otherTables],
  );
  deletedRows += deletionEmailHashes.rowCount ?? 0;

  const remaining = await client.query<{ present: boolean }>(
    `SELECT EXISTS (
       SELECT 1 FROM external_identities WHERE account_id = $1
       UNION ALL SELECT 1 FROM installations WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_sessions WHERE account_id = $1
       UNION ALL SELECT 1 FROM reauthentication_grants WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_idempotency_records WHERE account_id = $1
       UNION ALL SELECT 1 FROM connector_bindings WHERE account_id = $1
       UNION ALL SELECT 1 FROM connector_replacement_requests WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_device_preferences WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_lifecycle_events WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_audit_events WHERE account_id = $1
       UNION ALL SELECT 1 FROM account_deletion_email_hashes WHERE account_id = $1
       UNION ALL SELECT 1 FROM device_share_invitations WHERE owner_account_id = $1 OR accepted_by_account_id = $1
       UNION ALL SELECT 1 FROM device_access_grants WHERE owner_account_id = $1 OR grantee_account_id = $1
     ) AS present`,
    [accountId],
  );
  if (remaining.rows[0]?.present) return { deletedRows, accountAnonymized: false };

  const completed = await client.query(
    `UPDATE accounts
        SET status = 'deleted', deleted_at = $2, updated_at = $2
      WHERE id = $1 AND status = 'pending_deletion' AND deletion_due_at <= $2`,
    [accountId, now],
  );
  if ((completed.rowCount ?? 0) !== 1) return { deletedRows, accountAnonymized: false };
  await client.query(
    `INSERT INTO account_audit_events (id, account_id, event_type, occurred_at, metadata)
     VALUES ($1, $2, 'account.deleted', $3, '{}'::jsonb)`,
    [randomUUID(), accountId, now],
  );
  return { deletedRows, accountAnonymized: true };
}

async function deleteBatch(
  client: PoolClient,
  selection: string,
  table: string,
  parameters: unknown[],
  primaryKey = "id",
): Promise<number> {
  const deleted = await client.query(
    `WITH expired AS (${selection})
     DELETE FROM ${table} target
      USING expired
      WHERE target.${primaryKey} = expired.${primaryKey}`,
    parameters,
  );
  return deleted.rowCount ?? 0;
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

function needsCatchUp(counts: AccountRetentionCounts, batchSize: number): boolean {
  return counts.accountDeletionRows > 0
    || counts.accountsAnonymized > 0
    || counts.idempotencyRecords >= batchSize
    || counts.emailWebhookReceipts >= batchSize
    || counts.emailOtpChallenges >= batchSize
    || counts.deviceShareInvitations >= batchSize
    || counts.connectorReplacementRequests >= batchSize
    || counts.reauthenticationGrants >= batchSize
    || counts.refreshTokens >= batchSize
    || counts.accountSessions >= batchSize
    || counts.lifecycleEvents >= batchSize
    || counts.auditEvents >= batchSize;
}

function addCounts(target: AccountRetentionCounts, addition: AccountRetentionCounts): void {
  for (const key of Object.keys(target) as (keyof AccountRetentionCounts)[]) {
    target[key] += addition[key];
  }
}

function validateDelay(value: number | undefined, name: string): void {
  if (value === undefined) return;
  if (!Number.isSafeInteger(value) || value < 10) {
    throw new Error(`account retention ${name} must be at least 10`);
  }
}

function retentionWindow(value: number, name: string): number {
  const maximum = 10 * 365 * 24 * 60 * 60 * 1_000;
  if (!Number.isSafeInteger(value) || value < 24 * 60 * 60 * 1_000 || value > maximum) {
    throw new Error(`account retention ${name} must be between 1 and 3650 days`);
  }
  return value;
}
