import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { PostgresEmailDeliveryMetrics } from "./account/postgres-email-delivery-metrics.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
const NOW = new Date("2026-09-07T12:00:00.000Z");

test("email delivery snapshot exposes only bounded aggregate counters", async () => {
  const queries: string[] = [];
  const pool = {
    query: async (sql: string) => {
      queries.push(sql);
      return sql.includes("email_otp_challenges")
        ? { rows: [{ requested: "9", provider_accepted: "7", provider_failed: "1", pending: "1", verified: "5", final_delivered: "6", final_hard_failed: "1", final_delayed: "1" }] }
        : { rows: [{ requested: "4", provider_accepted: "3", provider_failed: "1", pending: "0", invitation_accepted: "2", final_delivered: "2", final_hard_failed: "1", final_delayed: "0" }] };
    },
  } as unknown as Pick<Pool, "query">;
  const snapshot = await new PostgresEmailDeliveryMetrics(pool, () => NOW).snapshot();
  assert.deepEqual(snapshot, {
    observedAt: "2026-09-07T12:00:00.000Z",
    windowStartedAt: "2026-09-07T11:00:00.000Z",
    windowSeconds: 3600,
    emailOtp: { requested: 9, providerAccepted: 7, providerFailed: 1, pending: 1, finalDelivered: 6, finalHardFailed: 1, finalDelayed: 1, verified: 5 },
    deviceShare: { requested: 4, providerAccepted: 3, providerFailed: 1, pending: 0, finalDelivered: 2, finalHardFailed: 1, finalDelayed: 0, invitationAccepted: 2 },
  });
  assert.equal(queries.length, 2);
  assert.equal(JSON.stringify(snapshot).includes("email"), true);
  assert.equal(JSON.stringify(snapshot).includes("recipient"), false);
  assert.equal(JSON.stringify(snapshot).includes("challengeId"), false);
});

test("PostgreSQL delivery metrics apply a strict one-hour window", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `email_metrics_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 2,
    options: `-c search_path=${schema}`,
  });
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    const recent = [
      { status: "sent", consumed: true, minutesAgo: 10 },
      { status: "failed", consumed: false, minutesAgo: 20 },
      { status: "pending", consumed: false, minutesAgo: 30 },
      { status: "sent", consumed: false, minutesAgo: 120 },
    ];
    for (const [index, row] of recent.entries()) {
      const createdAt = new Date(NOW.getTime() - row.minutesAgo * 60_000);
      const deliveredAt = row.status === "sent" ? createdAt : null;
      const consumedAt = row.consumed ? new Date(createdAt.getTime() + 1_000) : null;
      await pool.query(
        `INSERT INTO email_otp_challenges
           (id, purpose, email_lookup_hash, code_hash, requester_source_hash,
            requester_platform, expires_at, delivery_status, delivered_at, consumed_at,
            exchange_key_hash, invalidated_at, created_at, provider_message_id)
         VALUES ($1, 'sign_in', $2, $3, $4, 'web', $5, $6, $7, $8, $9, $10, $11, $12)`,
        [
          randomUUID(),
          index.toString(16).padStart(64, "0"),
          (index + 10).toString(16).padStart(64, "0"),
          (index + 20).toString(16).padStart(64, "0"),
          new Date(createdAt.getTime() + 10 * 60_000),
          row.status,
          deliveredAt,
          consumedAt,
          row.consumed ? (index + 30).toString(16).padStart(64, "0") : null,
          row.status === "failed" ? createdAt : null,
          createdAt,
          row.status === "sent" ? `provider-${index}` : null,
        ],
      );
    }
    const snapshot = await new PostgresEmailDeliveryMetrics(pool, () => NOW).snapshot();
    assert.deepEqual(snapshot.emailOtp, {
      requested: 3,
      providerAccepted: 1,
      providerFailed: 1,
      pending: 1,
      finalDelivered: 0,
      finalHardFailed: 0,
      finalDelayed: 0,
      verified: 1,
    });
    assert.deepEqual(snapshot.deviceShare, {
      requested: 0,
      providerAccepted: 0,
      providerFailed: 0,
      pending: 0,
      finalDelivered: 0,
      finalHardFailed: 0,
      finalDelayed: 0,
      invitationAccepted: 0,
    });
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA "${schema}" CASCADE`);
    await admin.end();
  }
});
