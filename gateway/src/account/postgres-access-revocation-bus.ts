import { type Notification, type Pool, type PoolClient } from "pg";

export const ACCOUNT_ACCESS_REVOCATION_CHANNEL = "hermes_account_access_revoked";

export type AccountAccessRevocation =
  | { kind: "account"; accountId: string }
  | { kind: "session"; accountId: string; sessionId: string }
  | { kind: "installation"; accountId: string; installationId: string }
  | { kind: "binding"; accountId: string; bindingId: string };

export interface AccountAccessRevocationListener {
  (event: AccountAccessRevocation): void;
}

export async function publishAccountAccessRevocation(
  client: PoolClient,
  event: AccountAccessRevocation,
): Promise<void> {
  await client.query(
    "SELECT pg_notify($1, $2)",
    [ACCOUNT_ACCESS_REVOCATION_CHANNEL, encodeAccountAccessRevocation(event)],
  );
}

export function encodeAccountAccessRevocation(event: AccountAccessRevocation): string {
  if (!validAccountAccessRevocation(event)) throw new Error("invalid account access revocation");
  return JSON.stringify(event);
}

export function parseAccountAccessRevocation(payload: string | undefined): AccountAccessRevocation | undefined {
  if (!payload || Buffer.byteLength(payload) > 1_024) return undefined;
  try {
    const value: unknown = JSON.parse(payload);
    return validAccountAccessRevocation(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

export class PostgresAccountAccessRevocationSubscriber {
  private readonly listeners = new Set<AccountAccessRevocationListener>();
  private client?: PoolClient;
  private retryTimer?: NodeJS.Timeout;
  private starting?: Promise<void>;
  private stopped = false;

  constructor(
    private readonly pool: Pool,
    private readonly retryMilliseconds = 1_000,
  ) {
    if (!Number.isSafeInteger(retryMilliseconds) || retryMilliseconds < 10) {
      throw new Error("revocation retryMilliseconds must be at least 10");
    }
  }

  subscribe(listener: AccountAccessRevocationListener): () => void {
    this.listeners.add(listener);
    void this.start();
    return () => this.listeners.delete(listener);
  }

  async start(): Promise<void> {
    if (this.stopped || this.client) return;
    if (this.starting) return this.starting;
    this.starting = this.connect().finally(() => {
      this.starting = undefined;
    });
    return this.starting;
  }

  async close(): Promise<void> {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    await this.starting?.catch(() => {});
    const client = this.client;
    this.client = undefined;
    if (!client) return;
    client.removeAllListeners("notification");
    client.removeAllListeners("error");
    await client.query(`UNLISTEN ${ACCOUNT_ACCESS_REVOCATION_CHANNEL}`).catch(() => {});
    client.release();
  }

  private async connect(): Promise<void> {
    let client: PoolClient | undefined;
    try {
      client = await this.pool.connect();
      if (this.stopped) {
        client.release();
        return;
      }
      const connected = client;
      this.client = connected;
      connected.on("notification", (notification: Notification) => {
        if (notification.channel !== ACCOUNT_ACCESS_REVOCATION_CHANNEL) return;
        const event = parseAccountAccessRevocation(notification.payload);
        if (!event) return;
        for (const listener of this.listeners) {
          try {
            listener(event);
          } catch {
            // A process-local consumer must not block revocation delivery to the others.
            // The periodic database authorization check remains the fail-closed fallback.
          }
        }
      });
      connected.on("error", () => this.handleConnectionLoss(connected));
      await connected.query(`LISTEN ${ACCOUNT_ACCESS_REVOCATION_CHANNEL}`);
    } catch {
      if (client) {
        if (this.client === client) this.client = undefined;
        client.removeAllListeners("notification");
        client.removeAllListeners("error");
        client.release(true);
      }
      this.scheduleReconnect();
    }
  }

  private handleConnectionLoss(client: PoolClient): void {
    if (this.client !== client) return;
    this.client = undefined;
    client.removeAllListeners("notification");
    client.removeAllListeners("error");
    client.release(true);
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      void this.start();
    }, this.retryMilliseconds);
    this.retryTimer.unref();
  }
}

function validAccountAccessRevocation(value: unknown): value is AccountAccessRevocation {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  if (!isUuid(record.accountId)) return false;
  switch (record.kind) {
    case "account": return exactKeys(record, ["accountId", "kind"]);
    case "session": return isUuid(record.sessionId)
      && exactKeys(record, ["accountId", "kind", "sessionId"]);
    case "installation": return isUuid(record.installationId)
      && exactKeys(record, ["accountId", "installationId", "kind"]);
    case "binding": return isUuid(record.bindingId)
      && exactKeys(record, ["accountId", "bindingId", "kind"]);
    default: return false;
  }
}

function exactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  return Object.keys(value).sort().join("\u0000") === [...expected].sort().join("\u0000");
}

function isUuid(value: unknown): value is string {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
