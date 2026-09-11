import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import type { EmailDeliveryEvent } from "./account/email-delivery.js";
import { PostgresEmailDeliveryEventStore } from "./account/postgres-email-delivery-event-store.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("Resend webhook receipts are idempotent, ordered, PII-minimal, and invalidate hard failures", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `email_delivery_${randomUUID().replaceAll("-", "")}`;
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

    const otpId = randomUUID();
    await pool.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, created_at, delivery_status, delivered_at,
          provider_message_id)
       VALUES ($1, 'sign_in', $2, $3, $4, 'web', $5, $6, 'sent', $6, $7)`,
      [
        otpId,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        new Date("2026-09-08T09:00:00.000Z"),
        new Date("2026-09-08T08:00:00.000Z"),
        "provider_otp_01",
      ],
    );
    const store = new PostgresEmailDeliveryEventStore(pool);
    await pool.query(
      `INSERT INTO email_delivery_webhook_receipts
         (webhook_id, provider_message_id, message_kind, message_id, event_type,
          event_created_at, received_at)
       VALUES ('msg_expired_01', 'provider_expired_01', 'email_otp', $1,
         'email.delivered', $2, $2)`,
      [randomUUID(), new Date("2026-07-01T00:00:00.000Z")],
    );
    const delivered = event({
      webhookId: "msg_delivered_01",
      messageId: otpId,
      providerMessageId: "provider_otp_01",
      eventType: "email.delivered",
      eventCreatedAt: new Date("2026-09-08T08:01:00.000Z"),
    });
    assert.equal(await store.record(delivered), "applied");
    assert.equal(await store.record(delivered), "duplicate");
    assert.equal(await store.record(event({
      webhookId: "msg_bounced_01",
      messageId: otpId,
      providerMessageId: "provider_otp_01",
      eventType: "email.bounced",
      eventCreatedAt: new Date("2026-09-08T08:02:00.000Z"),
    })), "applied");
    assert.equal(await store.record(event({
      webhookId: "msg_stale_01",
      messageId: otpId,
      providerMessageId: "provider_otp_01",
      eventType: "email.delivered",
      eventCreatedAt: new Date("2026-09-08T08:01:30.000Z"),
    })), "stale");

    const otp = await pool.query<{
      delivery_status: string;
      delivered_at: Date | null;
      invalidated_at: Date | null;
      final_delivery_status: string;
    }>(
      `SELECT delivery_status, delivered_at, invalidated_at, final_delivery_status
         FROM email_otp_challenges WHERE id = $1`,
      [otpId],
    );
    assert.deepEqual(otp.rows[0], {
      delivery_status: "failed",
      delivered_at: null,
      invalidated_at: new Date("2026-09-08T08:02:00.000Z"),
      final_delivery_status: "bounced",
    });

    const ownerAccountId = randomUUID();
    const installationId = randomUUID();
    const bindingId = randomUUID();
    const invitationId = randomUUID();
    await pool.query("INSERT INTO accounts (id) VALUES ($1)", [ownerAccountId]);
    await pool.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES ($1, $2, $3, 'desktop', 'macos', 'Webhook Mac', 'test')`,
      [installationId, ownerAccountId, randomUUID()],
    );
    await pool.query(
      `INSERT INTO connector_bindings
         (id, account_id, desktop_installation_id, display_name, device_id, public_key,
          key_algorithm, public_key_fingerprint, generation, status, activated_at)
       VALUES ($1, $2, $3, 'Webhook Mac', $4, $5, 'Ed25519', $6, 1, 'active', now())`,
      [
        bindingId,
        ownerAccountId,
        installationId,
        `webhook-${randomUUID()}`,
        Buffer.alloc(32, 7),
        "7".repeat(64),
      ],
    );
    // created_at is supplied rather than left to now(), for the same reason the OTP inserts above
    // supply it: the schema checks `expires_at > created_at`, so a row that hardcodes only
    // expires_at stops being insertable the moment the wall clock passes it. This one carried
    // 2026-09-11T08:00 and started failing every build at exactly that instant.
    await pool.query(
      `INSERT INTO device_share_invitations
         (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
          token_hash, expires_at, created_at, delivery_status, delivered_at, provider_message_id)
       VALUES ($1, $2, $3, $4, 'g***@example.com', $5, $6, $7, 'sent', $8, $9)`,
      [
        invitationId,
        bindingId,
        ownerAccountId,
        "d".repeat(64),
        "e".repeat(64),
        new Date("2026-09-11T08:00:00.000Z"),
        new Date("2026-09-08T07:00:00.000Z"),
        new Date("2026-09-08T08:00:00.000Z"),
        "provider_share_01",
      ],
    );
    assert.equal(await store.record(event({
      webhookId: "msg_suppressed_01",
      messageKind: "device_share",
      messageId: invitationId,
      providerMessageId: "provider_share_01",
      eventType: "email.suppressed",
      eventCreatedAt: new Date("2026-09-08T08:03:00.000Z"),
    })), "applied");
    const invitation = await pool.query<{
      status: string;
      delivery_status: string;
      cancelled_at: Date;
      final_delivery_status: string;
    }>(
      `SELECT status, delivery_status, cancelled_at, final_delivery_status
         FROM device_share_invitations WHERE id = $1`,
      [invitationId],
    );
    assert.deepEqual(invitation.rows[0], {
      status: "delivery_failed",
      delivery_status: "failed",
      cancelled_at: new Date("2026-09-08T08:03:00.000Z"),
      final_delivery_status: "suppressed",
    });

    const racedOtpId = randomUUID();
    const racedEvent = event({
      webhookId: "msg_raced_01",
      messageId: racedOtpId,
      providerMessageId: "provider_raced_01",
      eventType: "email.delivered",
      eventCreatedAt: new Date("2026-09-08T08:04:00.000Z"),
    });
    assert.equal(await store.record(racedEvent), "unmatched");
    await pool.query(
      `INSERT INTO email_otp_challenges
         (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
          requester_platform, expires_at, created_at, delivery_status, delivered_at,
          provider_message_id)
       VALUES ($1, 'sign_in', $2, $3, $4, 'web', $5, $6, 'sent', $6, $7)`,
      [
        racedOtpId,
        "1".repeat(64),
        "2".repeat(64),
        "3".repeat(64),
        new Date("2026-09-08T09:00:00.000Z"),
        new Date("2026-09-08T08:00:00.000Z"),
        "provider_raced_01",
      ],
    );
    assert.equal(await store.record(racedEvent), "applied");

    const receipts = await pool.query("SELECT * FROM email_delivery_webhook_receipts ORDER BY webhook_id");
    assert.equal(receipts.rowCount, 5);
    assert.equal(receipts.rows.some((row) => row.webhook_id === "msg_expired_01"), false);
    const serialized = JSON.stringify(receipts.rows);
    assert.equal(serialized.includes("@"), false);
    assert.equal(serialized.includes("subject"), false);
    assert.equal(receipts.rows.filter((row) => row.applied).length, 4);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA "${schema}" CASCADE`);
    await admin.end();
  }
});

function event(overrides: Partial<EmailDeliveryEvent>): EmailDeliveryEvent {
  return {
    webhookId: "msg_default_01",
    providerMessageId: "provider_otp_01",
    messageKind: "email_otp",
    messageId: randomUUID(),
    eventType: "email.delivered",
    eventCreatedAt: new Date("2026-09-08T08:01:00.000Z"),
    receivedAt: new Date("2026-09-08T08:01:01.000Z"),
    ...overrides,
  };
}
