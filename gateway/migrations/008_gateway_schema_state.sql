BEGIN;

ALTER TABLE external_identities
  DROP CONSTRAINT IF EXISTS external_identities_provider_check;
ALTER TABLE external_identities
  ADD CONSTRAINT external_identities_provider_check
  CHECK (provider IN ('google', 'email_otp'));

CREATE TABLE IF NOT EXISTS email_otp_challenges (
  id uuid PRIMARY KEY,
  purpose text NOT NULL CHECK (purpose IN ('sign_in', 'link_identity', 'reauthenticate')),
  email_lookup_hash char(64) NOT NULL,
  code_hash char(64) NOT NULL,
  requester_source_hash char(64) NOT NULL,
  requester_platform text NOT NULL CHECK (requester_platform IN ('android', 'macos', 'web')),
  client_installation_id uuid,
  expires_at timestamptz NOT NULL,
  max_attempts smallint NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 10),
  failed_attempts smallint NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND max_attempts),
  delivery_status text NOT NULL DEFAULT 'pending'
    CHECK (delivery_status IN ('pending', 'sent', 'failed')),
  delivered_at timestamptz,
  consumed_at timestamptz,
  exchange_key_hash char(64),
  invalidated_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > created_at),
  CHECK (delivered_at IS NULL OR delivery_status = 'sent'),
  CHECK (consumed_at IS NULL OR (delivery_status = 'sent' AND invalidated_at IS NULL)),
  CHECK ((consumed_at IS NULL) = (exchange_key_hash IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS email_otp_one_active_challenge_idx
  ON email_otp_challenges (email_lookup_hash, purpose)
  WHERE consumed_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX IF NOT EXISTS email_otp_email_window_idx
  ON email_otp_challenges (email_lookup_hash, created_at DESC);
CREATE INDEX IF NOT EXISTS email_otp_source_window_idx
  ON email_otp_challenges (requester_source_hash, created_at DESC);
CREATE INDEX IF NOT EXISTS email_otp_expiry_idx
  ON email_otp_challenges (expires_at)
  WHERE consumed_at IS NULL AND invalidated_at IS NULL;

UPDATE gateway_schema_state
SET version = 8,
    updated_at = now()
WHERE singleton = true;

COMMIT;
