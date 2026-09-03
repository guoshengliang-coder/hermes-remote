BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS installations_id_account_unique_idx
  ON installations (id, account_id);

CREATE TABLE IF NOT EXISTS connector_bindings (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  desktop_installation_id uuid NOT NULL,
  display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 128),
  device_id text NOT NULL UNIQUE CHECK (length(device_id) BETWEEN 1 AND 128),
  public_key bytea NOT NULL CHECK (octet_length(public_key) = 32),
  key_algorithm text NOT NULL CHECK (key_algorithm = 'Ed25519'),
  public_key_fingerprint char(64) NOT NULL,
  generation integer NOT NULL CHECK (generation > 0),
  status text NOT NULL CHECK (status IN ('pending', 'active', 'replaced', 'revoked')),
  pending_expires_at timestamptz,
  key_proved_at timestamptz,
  health_checked_at timestamptz,
  connector_online boolean NOT NULL DEFAULT false,
  hermes_reachable boolean,
  hermes_version text CHECK (hermes_version IS NULL OR length(hermes_version) <= 64),
  gateway_latency_ms integer CHECK (gateway_latency_ms IS NULL OR gateway_latency_ms BETWEEN 0 AND 60000),
  end_to_end_healthy boolean,
  created_at timestamptz NOT NULL DEFAULT now(),
  activated_at timestamptz,
  last_seen_at timestamptz,
  replaced_at timestamptz,
  revoked_at timestamptz,
  FOREIGN KEY (desktop_installation_id, account_id)
    REFERENCES installations(id, account_id),
  UNIQUE (account_id, generation)
);

CREATE UNIQUE INDEX IF NOT EXISTS connector_bindings_one_active_per_account_idx
  ON connector_bindings (account_id) WHERE status = 'active';

CREATE UNIQUE INDEX IF NOT EXISTS connector_bindings_one_pending_per_account_idx
  ON connector_bindings (account_id) WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS connector_bindings_account_status_idx
  ON connector_bindings (account_id, status, generation DESC);

ALTER TABLE account_idempotency_records
  ADD COLUMN IF NOT EXISTS connector_binding_id uuid REFERENCES connector_bindings(id);

COMMIT;
