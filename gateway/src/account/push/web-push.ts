import { createHash } from "node:crypto";
import type { Pool } from "pg";
import webpush from "web-push";
import type { AccountPrincipal } from "../model.js";
import { accountErrors } from "../model.js";
import { shouldPush, wakeHint } from "./push-fanout.js";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";

export interface WebSubscription {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}
export interface WebPushTarget {
  installationId: string;
  accountId: string;
  channelId: string;
  language: "zh" | "en";
  subscription: WebSubscription;
}
export interface WebPushStore {
  get(principal: AccountPrincipal): Promise<{ channelId: string } | null>;
  put(
    principal: AccountPrincipal,
    subscription: WebSubscription,
    channelId: string,
    language: "zh" | "en",
  ): Promise<void>;
  remove(principal: AccountPrincipal): Promise<void>;
  targets(deviceId: string): Promise<WebPushTarget[]>;
  drop(target: WebPushTarget): Promise<void>;
}

// These are delivery services, not user-controlled fetch targets. Exact TLS hosts/port, no
// credentials or redirects. Unknown providers require a reviewed allowlist addition.
export function parseWebSubscription(input: unknown): WebSubscription {
  const value = input as Partial<WebSubscription> | null;
  try {
    if (
      !value ||
      typeof value.endpoint !== "string" ||
      value.endpoint.length > 2048
    )
      throw new Error();
    const url = new URL(value.endpoint);
    const allowed =
      url.hostname === "web.push.apple.com" ||
      url.hostname.endsWith(".push.apple.com") ||
      url.hostname === "fcm.googleapis.com" ||
      url.hostname === "updates.push.services.mozilla.com";
    if (
      !allowed ||
      url.protocol !== "https:" ||
      url.port ||
      url.username ||
      url.password ||
      url.hash
    )
      throw new Error();
    const keys = value.keys;
    if (
      !keys ||
      !/^[A-Za-z0-9_-]{87}$/.test(keys.p256dh) ||
      !/^[A-Za-z0-9_-]{22}$/.test(keys.auth)
    )
      throw new Error();
    const point = Buffer.from(keys.p256dh, "base64url");
    if (
      point.length !== 65 ||
      point[0] !== 4 ||
      Buffer.from(keys.auth, "base64url").length !== 16
    )
      throw new Error();
    return {
      endpoint: url.href,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
    };
  } catch {
    throw accountErrors.invalidRequest("The web push subscription is invalid.");
  }
}

export class PostgresWebPushStore implements WebPushStore {
  constructor(private readonly pool: Pool) {}
  async get(p: AccountPrincipal) {
    const r = await this.pool.query<{ channel_id: string }>(
      "SELECT channel_id FROM account_web_push_subscriptions WHERE account_id=$1 AND installation_id=$2 AND session_id=$3",
      [p.account.id, p.installation.id, p.sessionId],
    );
    return r.rows[0] ? { channelId: r.rows[0].channel_id } : null;
  }
  async put(
    p: AccountPrincipal,
    subscription: WebSubscription,
    channelId: string,
    language: "zh" | "en",
  ) {
    const db = await this.pool.connect();
    try {
      await db.query("BEGIN");
      // Serialize registration with logout/revocation. A revoked request must not steal an
      // endpoint from another valid account, nor recreate a row after its cleanup trigger ran.
      const live = await db.query(
        `SELECT i.id FROM installations i
        JOIN account_sessions s ON s.installation_id=i.id AND s.account_id=i.account_id
        WHERE i.id=$2 AND i.account_id=$1 AND s.id=$3 AND i.revoked_at IS NULL
          AND s.revoked_at IS NULL AND i.kind='browser' AND i.platform='web'
        FOR UPDATE OF i,s`,
        [p.account.id, p.installation.id, p.sessionId],
      );
      if (!live.rowCount) throw accountErrors.sessionRevoked();
      await db.query(
        `DELETE FROM account_web_push_subscriptions WHERE endpoint=$1 AND (account_id<>$2 OR installation_id<>$3)`,
        [subscription.endpoint, p.account.id, p.installation.id],
      );
      await db.query(
        `INSERT INTO account_web_push_subscriptions
        (installation_id,account_id,session_id,channel_id,endpoint,subscription,language)
        VALUES ($2,$1,$3,$4,$5,$6::jsonb,$7)
        ON CONFLICT (installation_id) DO UPDATE SET session_id=EXCLUDED.session_id,
          channel_id=EXCLUDED.channel_id,endpoint=EXCLUDED.endpoint,subscription=EXCLUDED.subscription,language=EXCLUDED.language`,
        [
          p.account.id,
          p.installation.id,
          p.sessionId,
          channelId,
          subscription.endpoint,
          JSON.stringify(subscription),
          language,
        ],
      );
      await db.query("COMMIT");
    } catch (error) {
      await db.query("ROLLBACK");
      throw error;
    } finally {
      db.release();
    }
  }

  async remove(p: AccountPrincipal) {
    await this.pool.query(
      "DELETE FROM account_web_push_subscriptions WHERE account_id=$1 AND installation_id=$2",
      [p.account.id, p.installation.id],
    );
  }
  async targets(deviceId: string) {
    const r = await this.pool.query<{
      installation_id: string;
      account_id: string;
      channel_id: string;
      language: "zh" | "en";
      subscription: WebSubscription;
    }>(
      `
      SELECT w.* FROM account_web_push_subscriptions w
      JOIN installations i ON i.id=w.installation_id AND i.account_id=w.account_id
      JOIN accounts a ON a.id=w.account_id
      JOIN account_sessions s ON s.id=w.session_id AND s.account_id=w.account_id AND s.installation_id=w.installation_id
      WHERE i.revoked_at IS NULL AND s.revoked_at IS NULL AND a.status='active'
        AND EXISTS (SELECT 1 FROM refresh_tokens t WHERE t.session_id=s.id AND t.revoked_at IS NULL AND t.used_at IS NULL AND t.expires_at>now())
        AND EXISTS (SELECT 1 FROM connector_bindings b WHERE b.device_id=$1 AND b.status='active'
          AND (b.account_id=w.account_id OR EXISTS (SELECT 1 FROM device_access_grants g
            WHERE g.binding_id=b.id AND g.grantee_account_id=w.account_id AND g.status='active')))`,
      [deviceId],
    );
    return r.rows.map((x) => ({
      installationId: x.installation_id,
      accountId: x.account_id,
      channelId: x.channel_id,
      language: x.language,
      subscription: x.subscription,
    }));
  }
  async drop(t: WebPushTarget) {
    await this.pool.query(
      "DELETE FROM account_web_push_subscriptions WHERE installation_id=$1 AND channel_id=$2",
      [t.installationId, t.channelId],
    );
  }
}

export type WebPushSender = (
  subscription: WebSubscription,
  payload: string,
  options: webpush.RequestOptions,
) => Promise<unknown>;
export class WebPushFanout {
  constructor(
    private readonly store: WebPushStore,
    readonly publicKey: string,
    private readonly privateKey: string,
    private readonly subject: string,
    private readonly send: WebPushSender = webpush.sendNotification,
  ) {
    // Validate configured VAPID material at startup without making a network call.
    webpush.getVapidHeaders(
      "https://web.push.apple.com",
      subject,
      publicKey,
      privateKey,
      "aes128gcm",
    );
  }
  async notify(event: SessionLifecycleEvent): Promise<void> {
    if (!shouldPush(event.event)) return;
    try {
      const targets = await this.store.targets(event.deviceId);
      await Promise.all(
        targets.map(async (target) => {
          try {
            await this.send(
              target.subscription,
              JSON.stringify({
                ...wakeHint(event),
                channelId: target.channelId,
                accountId: target.accountId,
                language: target.language,
              }),
              {
                vapidDetails: {
                  subject: this.subject,
                  publicKey: this.publicKey,
                  privateKey: this.privateKey,
                },
                TTL: 600,
                timeout: 10_000,
                urgency: "high",
                topic: createHash("sha256")
                  .update(
                    `${event.deviceId}:${event.profile ?? "default"}:${event.storedSessionId}`,
                  )
                  .digest("base64url")
                  .slice(0, 32),
              },
            );
          } catch (error) {
            const status = (error as { statusCode?: number }).statusCode;
            if (status === 404 || status === 410) await this.store.drop(target);
            // Never log the error: provider errors carry endpoint addresses and keys.
          }
        }),
      );
    } catch {
      /* Push must never block or fail durable lifecycle ingestion. */
    }
  }
}
