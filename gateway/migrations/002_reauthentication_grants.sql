BEGIN;

CREATE TABLE IF NOT EXISTS reauthentication_grants (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  installation_id uuid NOT NULL REFERENCES installations(id),
  session_id uuid NOT NULL REFERENCES account_sessions(id),
  scope text NOT NULL CHECK (scope IN ('connector.replace', 'connector.unbind', 'account.revoke_all')),
  token_hash char(64) NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  revoked_at timestamptz
);

CREATE INDEX IF NOT EXISTS reauthentication_grants_active_idx
  ON reauthentication_grants (account_id, installation_id, scope, expires_at)
  WHERE used_at IS NULL AND revoked_at IS NULL;

COMMIT;
