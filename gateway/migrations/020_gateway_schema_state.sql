BEGIN;

-- Per-account commit order and a stable, backup-covered HMAC key for minimal cancellation markers.
CREATE TABLE account_model_sync_state (
  account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  key_secret bytea NOT NULL CHECK (octet_length(key_secret) = 32),
  revision numeric(20,0) NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 18446744073709551615),
  log_floor numeric(20,0) NOT NULL DEFAULT 0 CHECK (log_floor BETWEEN 0 AND revision)
);
CREATE TABLE account_model_preferences (
  account_id uuid NOT NULL REFERENCES account_model_sync_state(account_id) ON DELETE CASCADE,
  key_hash text NOT NULL CHECK (length(key_hash) = 64),
  provider text,
  model text,
  favorite boolean NOT NULL DEFAULT false,
  reasoning text CHECK (reasoning IN ('none','minimal','low','medium','high','xhigh','max','ultra')),
  revision numeric(20,0) NOT NULL CHECK (revision BETWEEN 1 AND 18446744073709551615),
  favorite_revision numeric(20,0) NOT NULL DEFAULT 0 CHECK (favorite_revision BETWEEN 0 AND revision),
  reasoning_revision numeric(20,0) NOT NULL DEFAULT 0 CHECK (reasoning_revision BETWEEN 0 AND revision),
  PRIMARY KEY (account_id,key_hash),
  CHECK ((provider IS NOT NULL AND model IS NOT NULL AND (favorite OR reasoning IS NOT NULL))
      OR (provider IS NULL AND model IS NULL AND NOT favorite AND reasoning IS NULL))
);
CREATE TABLE account_model_sync_changes (
  account_id uuid NOT NULL REFERENCES account_model_sync_state(account_id) ON DELETE CASCADE,
  revision numeric(20,0) NOT NULL,
  payload jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (account_id,revision)
);
CREATE INDEX account_model_sync_changes_expiry ON account_model_sync_changes(expires_at);
CREATE TABLE account_model_sync_operations (
  account_id uuid NOT NULL REFERENCES account_model_sync_state(account_id) ON DELETE CASCADE,
  op_id uuid NOT NULL,
  request_hash text NOT NULL,
  result jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (account_id,op_id)
);
CREATE INDEX account_model_sync_operations_expiry ON account_model_sync_operations(expires_at);
UPDATE gateway_schema_state SET version=20, updated_at=now() WHERE singleton=true;
COMMIT;
