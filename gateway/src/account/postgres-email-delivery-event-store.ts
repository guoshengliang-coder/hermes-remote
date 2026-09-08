import { Pool, type PoolClient } from "pg";
import type {
  EmailDeliveryEvent,
  EmailDeliveryEventStore,
  EmailDeliveryEventType,
} from "./email-delivery.js";
import { EMAIL_DELIVERY_CORRELATION_RETENTION_MS } from "./email-delivery.js";

const TERMINAL_FAILURES: ReadonlySet<EmailDeliveryEventType> = new Set([
  "email.bounced",
  "email.complained",
  "email.failed",
  "email.suppressed",
]);

export class PostgresEmailDeliveryEventStore implements EmailDeliveryEventStore {
  constructor(private readonly pool: Pool) {}

  async record(event: EmailDeliveryEvent): Promise<"applied" | "duplicate" | "unmatched" | "stale"> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      await client.query(
        `DELETE FROM email_delivery_webhook_receipts
          WHERE ctid IN (
            SELECT ctid
              FROM email_delivery_webhook_receipts
             WHERE received_at < $1
             ORDER BY received_at
             LIMIT 1000
          )`,
        [new Date(event.receivedAt.getTime() - EMAIL_DELIVERY_CORRELATION_RETENTION_MS)],
      );
      const inserted = await client.query(
        `INSERT INTO email_delivery_webhook_receipts
           (webhook_id, provider_message_id, message_kind, message_id, event_type,
            event_created_at, received_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         ON CONFLICT (webhook_id) DO NOTHING
         RETURNING webhook_id`,
        [
          event.webhookId,
          event.providerMessageId,
          event.messageKind,
          event.messageId,
          event.eventType,
          event.eventCreatedAt,
          event.receivedAt,
        ],
      );
      if ((inserted.rowCount ?? 0) === 0) {
        const existing = await client.query<{
          provider_message_id: string;
          message_kind: string;
          message_id: string;
          event_type: string;
          event_created_at: Date;
          applied: boolean;
        }>(
          `SELECT provider_message_id, message_kind, message_id, event_type,
                  event_created_at, applied
             FROM email_delivery_webhook_receipts
            WHERE webhook_id = $1
            FOR UPDATE`,
          [event.webhookId],
        );
        const receipt = existing.rows[0];
        if (!receipt
            || receipt.provider_message_id !== event.providerMessageId
            || receipt.message_kind !== event.messageKind
            || receipt.message_id !== event.messageId
            || receipt.event_type !== event.eventType
            || receipt.event_created_at.getTime() !== event.eventCreatedAt.getTime()) {
          throw new Error("email delivery webhook id collision");
        }
        if (receipt.applied) {
          await client.query("COMMIT");
          return "duplicate";
        }
      }

      const applied = event.messageKind === "email_otp"
        ? await this.applyOtpEvent(client, event)
        : await this.applyShareEvent(client, event);
      if (applied) {
        await client.query(
          "UPDATE email_delivery_webhook_receipts SET applied = true WHERE webhook_id = $1",
          [event.webhookId],
        );
        await client.query("COMMIT");
        return "applied";
      }

      const target = event.messageKind === "email_otp" ? "email_otp_challenges" : "device_share_invitations";
      const exists = await client.query(
        `SELECT 1 FROM ${target} WHERE id = $1 AND provider_message_id = $2`,
        [event.messageId, event.providerMessageId],
      );
      await client.query("COMMIT");
      return (exists.rowCount ?? 0) === 1 ? "stale" : "unmatched";
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }

  private async applyOtpEvent(client: PoolClient, event: EmailDeliveryEvent): Promise<boolean> {
    const rank = eventRank(event.eventType);
    const failed = TERMINAL_FAILURES.has(event.eventType);
    const result = await client.query(
      `UPDATE email_otp_challenges
          SET final_delivery_status = $3,
              final_delivery_event_at = $4,
              final_delivery_event_rank = $5,
              delivery_status = CASE
                WHEN $6::boolean AND consumed_at IS NULL THEN 'failed'
                ELSE delivery_status
              END,
              delivered_at = CASE
                WHEN $6::boolean AND consumed_at IS NULL THEN NULL
                ELSE delivered_at
              END,
              invalidated_at = CASE
                WHEN $6::boolean AND consumed_at IS NULL THEN COALESCE(invalidated_at, $4)
                ELSE invalidated_at
              END
        WHERE id = $1
          AND provider_message_id = $2
          AND (
            final_delivery_event_at IS NULL
            OR final_delivery_event_at < $4
            OR (final_delivery_event_at = $4 AND final_delivery_event_rank < $5)
          )
        RETURNING id`,
      [
        event.messageId,
        event.providerMessageId,
        eventTypeValue(event.eventType),
        event.eventCreatedAt,
        rank,
        failed,
      ],
    );
    return (result.rowCount ?? 0) === 1;
  }

  private async applyShareEvent(client: PoolClient, event: EmailDeliveryEvent): Promise<boolean> {
    const rank = eventRank(event.eventType);
    const failed = TERMINAL_FAILURES.has(event.eventType);
    const result = await client.query(
      `UPDATE device_share_invitations
          SET final_delivery_status = $3,
              final_delivery_event_at = $4,
              final_delivery_event_rank = $5,
              status = CASE WHEN $6::boolean AND status = 'pending' THEN 'delivery_failed' ELSE status END,
              delivery_status = CASE
                WHEN $6::boolean AND status = 'pending' THEN 'failed'
                ELSE delivery_status
              END,
              delivered_at = CASE
                WHEN $6::boolean AND status = 'pending' THEN NULL
                ELSE delivered_at
              END,
              cancelled_at = CASE
                WHEN $6::boolean AND status = 'pending' THEN COALESCE(cancelled_at, $4)
                ELSE cancelled_at
              END
        WHERE id = $1
          AND provider_message_id = $2
          AND (
            final_delivery_event_at IS NULL
            OR final_delivery_event_at < $4
            OR (final_delivery_event_at = $4 AND final_delivery_event_rank < $5)
          )
        RETURNING id`,
      [
        event.messageId,
        event.providerMessageId,
        eventTypeValue(event.eventType),
        event.eventCreatedAt,
        rank,
        failed,
      ],
    );
    return (result.rowCount ?? 0) === 1;
  }
}

function eventRank(type: EmailDeliveryEventType): number {
  switch (type) {
    case "email.sent": return 10;
    case "email.delivery_delayed": return 20;
    case "email.delivered": return 30;
    case "email.complained": return 40;
    case "email.bounced":
    case "email.failed":
    case "email.suppressed": return 50;
  }
}

function eventTypeValue(type: EmailDeliveryEventType): string {
  return type.slice("email.".length);
}
