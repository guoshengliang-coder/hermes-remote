import type { Pool } from "pg";
import type { PushProviderName } from "./push-provider.js";

export interface PushTarget {
  installationId: string;
  provider: PushProviderName;
  token: string;
}

export interface PushRegistrationStore {
  upsert(accountId: string, installationId: string, provider: PushProviderName, token: string): Promise<void>;
  remove(accountId: string, installationId: string): Promise<void>;
  listTargets(accountId: string): Promise<PushTarget[]>;
  /** Drops a registration only if it still holds the token the provider rejected. */
  removeToken(installationId: string, token: string): Promise<void>;
}

export class PostgresPushRegistrationStore implements PushRegistrationStore {
  constructor(private readonly pool: Pool) {}

  async upsert(accountId: string, installationId: string, provider: PushProviderName, token: string) {
    // The installation must still be a live Android phone of this account; a revoked one must not
    // be able to re-register between its revocation and its session expiring.
    await this.pool.query(
      `INSERT INTO account_push_registrations (installation_id, account_id, provider, token)
       SELECT i.id, i.account_id, $3, $4
         FROM installations i
        WHERE i.id = $2 AND i.account_id = $1 AND i.revoked_at IS NULL
          AND i.kind = 'phone' AND i.platform = 'android'
       ON CONFLICT (installation_id) DO UPDATE
         SET provider = EXCLUDED.provider, token = EXCLUDED.token, updated_at = now()
         WHERE account_push_registrations.provider IS DISTINCT FROM EXCLUDED.provider
            OR account_push_registrations.token IS DISTINCT FROM EXCLUDED.token`,
      [accountId, installationId, provider, token],
    );
  }

  async remove(accountId: string, installationId: string) {
    await this.pool.query(
      "DELETE FROM account_push_registrations WHERE account_id = $1 AND installation_id = $2",
      [accountId, installationId],
    );
  }

  async listTargets(accountId: string): Promise<PushTarget[]> {
    const result = await this.pool.query<{ installation_id: string; provider: PushProviderName; token: string }>(
      `SELECT r.installation_id, r.provider, r.token
         FROM account_push_registrations r
         JOIN installations i ON i.id = r.installation_id AND i.account_id = r.account_id
        WHERE r.account_id = $1 AND i.revoked_at IS NULL`,
      [accountId],
    );
    return result.rows.map((row) => ({
      installationId: row.installation_id,
      provider: row.provider,
      token: row.token,
    }));
  }

  async removeToken(installationId: string, token: string) {
    await this.pool.query(
      "DELETE FROM account_push_registrations WHERE installation_id = $1 AND token = $2",
      [installationId, token],
    );
  }
}
