import { Pool, type PoolClient, type QueryResultRow } from "pg";
import {
  secureHashEquals,
  type EmailOtpChallengeCreationResult,
  type EmailOtpChallengeRecord,
  type EmailOtpChallengeStore,
  type EmailOtpVerificationResult,
} from "./email-otp-service.js";
import {
  EMAIL_DELIVERY_CORRELATION_RETENTION_MS,
  type EmailProviderSubmission,
} from "./email-delivery.js";

interface ActiveChallengeRow extends QueryResultRow {
  id: string;
  expires_at: Date;
  created_at: Date;
}

interface ChallengeRow extends QueryResultRow {
  code_hash: string;
  expires_at: Date;
  max_attempts: number;
  failed_attempts: number;
  delivery_status: "pending" | "sent" | "failed" | "suppressed";
  consumed_at: Date | null;
  exchange_key_hash: string | null;
  invalidated_at: Date | null;
}

export class PostgresEmailOtpStore implements EmailOtpChallengeStore {
  constructor(private readonly pool: Pool) {}

  async createChallenge(
    challenge: EmailOtpChallengeRecord,
    policy: {
      cooldownMs: number;
      rateWindowMs: number;
      maxRequestsPerEmail: number;
      maxRequestsPerSource: number;
    },
  ): Promise<EmailOtpChallengeCreationResult> {
    return this.transaction(async (client) => {
      await client.query(
        `DELETE FROM email_otp_challenges
          WHERE ctid IN (
            SELECT ctid
              FROM email_otp_challenges
             WHERE created_at < $1
               AND expires_at < $2
             ORDER BY created_at
             LIMIT 1000
          )`,
        [
          new Date(challenge.createdAt.getTime() - EMAIL_DELIVERY_CORRELATION_RETENTION_MS),
          challenge.createdAt,
        ],
      );
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        `email-otp:email:${challenge.emailLookupHash}`,
      ]);
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        `email-otp:source:${challenge.requesterSourceHash}`,
      ]);
      const deletion = await client.query(
        `SELECT 1 FROM account_deletion_email_hashes
          WHERE hash_kind = 'email_otp'
            AND trim(email_lookup_hash) = $1
          LIMIT 1`,
        [challenge.emailLookupHash],
      );
      const suppressed = (deletion.rowCount ?? 0) > 0;

      await client.query(
        `UPDATE email_otp_challenges
            SET invalidated_at = $2
          WHERE email_lookup_hash = $1
            AND consumed_at IS NULL
            AND invalidated_at IS NULL
            AND expires_at <= $2`,
        [challenge.emailLookupHash, challenge.createdAt],
      );
      const active = await client.query<ActiveChallengeRow>(
        `SELECT id, expires_at, created_at
           FROM email_otp_challenges
          WHERE email_lookup_hash = $1
            AND purpose = $2
            AND consumed_at IS NULL
            AND invalidated_at IS NULL
            AND created_at > $3
          ORDER BY created_at DESC
          LIMIT 1`,
        [
          challenge.emailLookupHash,
          challenge.purpose,
          new Date(challenge.createdAt.getTime() - policy.cooldownMs),
        ],
      );
      if ((active.rowCount ?? 0) > 0) {
        return {
          status: "cooldown",
          challengeId: active.rows[0].id,
          expiresAt: active.rows[0].expires_at,
          resendAfter: new Date(active.rows[0].created_at.getTime() + policy.cooldownMs),
        };
      }

      const windowStart = new Date(challenge.createdAt.getTime() - policy.rateWindowMs);
      const counts = await client.query<{ email_count: number; source_count: number }>(
        `SELECT
           count(*) FILTER (WHERE email_lookup_hash = $1)::int AS email_count,
           count(*) FILTER (WHERE requester_source_hash = $2)::int AS source_count
         FROM email_otp_challenges
         WHERE created_at >= $3
           AND (email_lookup_hash = $1 OR requester_source_hash = $2)`,
        [challenge.emailLookupHash, challenge.requesterSourceHash, windowStart],
      );
      if (counts.rows[0].email_count >= policy.maxRequestsPerEmail
          || counts.rows[0].source_count >= policy.maxRequestsPerSource) {
        return { status: "rate_limited" };
      }

      await client.query(
        `UPDATE email_otp_challenges
            SET invalidated_at = $3
          WHERE email_lookup_hash = $1
            AND purpose = $2
            AND consumed_at IS NULL
            AND invalidated_at IS NULL`,
        [challenge.emailLookupHash, challenge.purpose, challenge.createdAt],
      );
      await client.query(
        `INSERT INTO email_otp_challenges
           (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
            requester_platform, client_installation_id, expires_at, max_attempts, created_at,
            delivery_status)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
        [
          challenge.id,
          challenge.purpose,
          challenge.emailLookupHash,
          challenge.codeHash,
          challenge.requesterSourceHash,
          challenge.requesterPlatform,
          challenge.clientInstallationId ?? null,
          challenge.expiresAt,
          challenge.maxAttempts,
          challenge.createdAt,
          suppressed ? "suppressed" : "pending",
        ],
      );
      return { status: suppressed ? "suppressed" : "created" };
    });
  }

  async markDelivery(
    challengeId: string,
    submission: EmailProviderSubmission,
    now: Date,
  ): Promise<void> {
    const accepted = submission.status === "accepted";
    await this.pool.query(
      `UPDATE email_otp_challenges
          SET delivery_status = $2::text,
              provider_message_id = $4,
              delivered_at = CASE
                WHEN $2::text = 'sent' THEN $3::timestamptz
                ELSE NULL::timestamptz
              END,
              invalidated_at = CASE
                WHEN $2::text = 'failed' THEN $3::timestamptz
                ELSE invalidated_at
              END
        WHERE id = $1
          AND delivery_status = 'pending'`,
      [
        challengeId,
        accepted ? "sent" : "failed",
        now,
        accepted ? submission.providerMessageId : null,
      ],
    );
  }

  async verifyChallenge(input: {
    challengeId: string;
    purpose: EmailOtpChallengeRecord["purpose"];
    emailLookupHash: string;
    candidateCodeHash?: string;
    exchangeKeyHash: string;
    requesterPlatform: EmailOtpChallengeRecord["requesterPlatform"];
    clientInstallationId?: string;
    now: Date;
  }): Promise<EmailOtpVerificationResult> {
    return this.transaction(async (client) => {
      const result = await client.query<ChallengeRow>(
        `SELECT code_hash, expires_at, max_attempts, failed_attempts, delivery_status,
                consumed_at, exchange_key_hash, invalidated_at
           FROM email_otp_challenges
          WHERE id = $1
            AND purpose = $2
            AND email_lookup_hash = $3
            AND requester_platform = $4
            AND client_installation_id IS NOT DISTINCT FROM $5::uuid
          FOR UPDATE`,
        [
          input.challengeId,
          input.purpose,
          input.emailLookupHash,
          input.requesterPlatform,
          input.clientInstallationId ?? null,
        ],
      );
      if (result.rowCount !== 1) return { status: "invalid" };
      const challenge = result.rows[0];
      if (challenge.consumed_at) {
        return {
          status: secureHashEquals(challenge.exchange_key_hash ?? "", input.exchangeKeyHash)
            ? "replayed"
            : "consumed",
        };
      }
      if (challenge.delivery_status !== "sent" || challenge.invalidated_at) {
        return { status: "delivery_failed" };
      }
      if (challenge.expires_at.getTime() <= input.now.getTime()) {
        await client.query(
          "UPDATE email_otp_challenges SET invalidated_at = $2 WHERE id = $1",
          [input.challengeId, input.now],
        );
        return { status: "expired" };
      }
      if (challenge.failed_attempts >= challenge.max_attempts) {
        return { status: "attempts_exceeded" };
      }
      if (!secureHashEquals(challenge.code_hash, input.candidateCodeHash)) {
        const attempts = challenge.failed_attempts + 1;
        await client.query(
          `UPDATE email_otp_challenges
              SET failed_attempts = $2,
                  invalidated_at = CASE WHEN $2 >= max_attempts THEN $3 ELSE invalidated_at END
            WHERE id = $1`,
          [input.challengeId, attempts, input.now],
        );
        return { status: attempts >= challenge.max_attempts ? "attempts_exceeded" : "invalid" };
      }
      await client.query(
        `UPDATE email_otp_challenges
            SET consumed_at = $2,
                exchange_key_hash = $3
          WHERE id = $1`,
        [input.challengeId, input.now, input.exchangeKeyHash],
      );
      return { status: "verified" };
    });
  }

  private async transaction<T>(operation: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await operation(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }
}
