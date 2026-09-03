BEGIN;

CREATE TABLE IF NOT EXISTS accounts (
  id uuid PRIMARY KEY,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_identities (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  provider text NOT NULL CHECK (provider IN ('google')),
  issuer text NOT NULL,
  subject text NOT NULL,
  email text,
  display_name text,
  avatar_url text,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_verified_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, issuer, subject)
);

CREATE TABLE IF NOT EXISTS installations (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  client_installation_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('phone', 'desktop')),
  platform text NOT NULL CHECK (platform IN ('android', 'macos')),
  display_name text NOT NULL,
  app_version text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  UNIQUE (account_id, client_installation_id)
);

CREATE TABLE IF NOT EXISTS account_sessions (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  installation_id uuid NOT NULL REFERENCES installations(id),
  refresh_family_id uuid NOT NULL,
  access_token_hash char(64) NOT NULL UNIQUE,
  access_expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES account_sessions(id),
  family_id uuid NOT NULL,
  parent_id uuid REFERENCES refresh_tokens(id),
  token_hash char(64) NOT NULL UNIQUE,
  issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  revoked_at timestamptz
);

CREATE TABLE IF NOT EXISTS account_audit_events (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  installation_id uuid REFERENCES installations(id),
  event_type text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS installations_account_kind_idx
  ON installations (account_id, kind) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS account_sessions_account_idx
  ON account_sessions (account_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS account_sessions_installation_idx
  ON account_sessions (installation_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS refresh_tokens_family_idx
  ON refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS account_audit_events_account_time_idx
  ON account_audit_events (account_id, occurred_at DESC);

COMMIT;
