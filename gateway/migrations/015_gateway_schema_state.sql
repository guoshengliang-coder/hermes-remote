BEGIN;

ALTER TABLE accounts
  DROP CONSTRAINT IF EXISTS accounts_status_check;
ALTER TABLE accounts
  ADD COLUMN IF NOT EXISTS deletion_requested_at timestamptz,
  ADD COLUMN IF NOT EXISTS deletion_due_at timestamptz,
  ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
ALTER TABLE accounts
  DROP CONSTRAINT IF EXISTS accounts_deletion_state_check;
ALTER TABLE accounts
  ADD CONSTRAINT accounts_status_check
    CHECK (status IN ('active', 'disabled', 'pending_deletion', 'deleted')),
  ADD CONSTRAINT accounts_deletion_state_check CHECK (
    (status IN ('active', 'disabled')
      AND deletion_requested_at IS NULL AND deletion_due_at IS NULL AND deleted_at IS NULL)
    OR
    (status = 'pending_deletion'
      AND deletion_requested_at IS NOT NULL
      AND deletion_due_at = deletion_requested_at + interval '30 days'
      AND deleted_at IS NULL)
    OR
    (status = 'deleted'
      AND deletion_requested_at IS NOT NULL
      AND deletion_due_at IS NOT NULL
      AND deleted_at IS NOT NULL)
  );
CREATE INDEX IF NOT EXISTS accounts_deletion_due_idx
  ON accounts (deletion_due_at)
  WHERE status = 'pending_deletion';

-- Keep only purpose-separated, non-displayable email fingerprints while deletion is pending. They
-- let sharing reject/remove addressed invitations and let OTP issuance suppress provider delivery
-- without retaining the address itself outside external_identities.
CREATE TABLE IF NOT EXISTS account_deletion_email_hashes (
  account_id uuid NOT NULL REFERENCES accounts(id),
  hash_kind text NOT NULL CHECK (hash_kind IN ('device_share', 'email_otp')),
  email_lookup_hash char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, hash_kind, email_lookup_hash)
);
CREATE INDEX IF NOT EXISTS account_deletion_email_hashes_lookup_idx
  ON account_deletion_email_hashes (hash_kind, email_lookup_hash);

-- A metadata-free replay receipt survives account-linked row cleanup so a Desktop that was offline for
-- the full deletion window can still resolve a previously ambiguous request. Both values are keyed
-- hashes; neither identifies the account or exposes the caller's tokens/idempotency key.
CREATE TABLE IF NOT EXISTS account_deletion_receipts (
  idempotency_key_hash char(64) PRIMARY KEY,
  request_hash char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE reauthentication_grants
  DROP CONSTRAINT IF EXISTS reauthentication_grants_scope_check;
ALTER TABLE reauthentication_grants
  ADD CONSTRAINT reauthentication_grants_scope_check
  CHECK (scope IN (
    'connector.replace',
    'connector.unbind',
    'account.revoke_all',
    'account.identity.link',
    'account.identity.unlink',
    'account.installation.revoke',
    'account.delete',
    'device.share'
  ));

ALTER TABLE email_otp_challenges
  ADD COLUMN IF NOT EXISTS provider_message_id varchar(128),
  ADD COLUMN IF NOT EXISTS final_delivery_status text,
  ADD COLUMN IF NOT EXISTS final_delivery_event_at timestamptz,
  ADD COLUMN IF NOT EXISTS final_delivery_event_rank smallint;

ALTER TABLE email_otp_challenges
  DROP CONSTRAINT IF EXISTS email_otp_challenges_delivery_status_check,
  DROP CONSTRAINT IF EXISTS email_otp_challenges_provider_message_id_check,
  DROP CONSTRAINT IF EXISTS email_otp_challenges_final_delivery_status_check,
  DROP CONSTRAINT IF EXISTS email_otp_challenges_final_delivery_state_check;
ALTER TABLE email_otp_challenges
  ADD CONSTRAINT email_otp_challenges_delivery_status_check
    CHECK (delivery_status IN ('pending', 'sent', 'failed', 'suppressed')),
  ADD CONSTRAINT email_otp_challenges_provider_message_id_check
    CHECK (provider_message_id IS NULL OR provider_message_id ~ '^[A-Za-z0-9_-]{1,128}$'),
  ADD CONSTRAINT email_otp_challenges_final_delivery_status_check
    CHECK (final_delivery_status IS NULL OR final_delivery_status IN (
      'sent', 'delivery_delayed', 'delivered', 'complained', 'bounced', 'failed', 'suppressed'
    )),
  ADD CONSTRAINT email_otp_challenges_final_delivery_state_check
    CHECK (
      (final_delivery_status IS NULL AND final_delivery_event_at IS NULL
        AND final_delivery_event_rank IS NULL)
      OR
      (final_delivery_status IS NOT NULL AND final_delivery_event_at IS NOT NULL
        AND final_delivery_event_rank IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS email_otp_provider_message_id_idx
  ON email_otp_challenges (provider_message_id)
  WHERE provider_message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS email_otp_challenges_retention_idx
  ON email_otp_challenges (created_at);

ALTER TABLE device_share_invitations
  ADD COLUMN IF NOT EXISTS provider_message_id varchar(128),
  ADD COLUMN IF NOT EXISTS final_delivery_status text,
  ADD COLUMN IF NOT EXISTS final_delivery_event_at timestamptz,
  ADD COLUMN IF NOT EXISTS final_delivery_event_rank smallint;

ALTER TABLE device_share_invitations
  DROP CONSTRAINT IF EXISTS device_share_invitations_provider_message_id_check,
  DROP CONSTRAINT IF EXISTS device_share_invitations_final_delivery_status_check,
  DROP CONSTRAINT IF EXISTS device_share_invitations_final_delivery_state_check;
ALTER TABLE device_share_invitations
  ADD CONSTRAINT device_share_invitations_provider_message_id_check
    CHECK (provider_message_id IS NULL OR provider_message_id ~ '^[A-Za-z0-9_-]{1,128}$'),
  ADD CONSTRAINT device_share_invitations_final_delivery_status_check
    CHECK (final_delivery_status IS NULL OR final_delivery_status IN (
      'sent', 'delivery_delayed', 'delivered', 'complained', 'bounced', 'failed', 'suppressed'
    )),
  ADD CONSTRAINT device_share_invitations_final_delivery_state_check
    CHECK (
      (final_delivery_status IS NULL AND final_delivery_event_at IS NULL
        AND final_delivery_event_rank IS NULL)
      OR
      (final_delivery_status IS NOT NULL AND final_delivery_event_at IS NOT NULL
        AND final_delivery_event_rank IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS device_share_provider_message_id_idx
  ON device_share_invitations (provider_message_id)
  WHERE provider_message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS device_share_invitations_retention_idx
  ON device_share_invitations (created_at);

CREATE INDEX IF NOT EXISTS connector_replacement_requests_retention_idx
  ON connector_replacement_requests (expires_at);
CREATE INDEX IF NOT EXISTS reauthentication_grants_retention_idx
  ON reauthentication_grants (expires_at);
CREATE INDEX IF NOT EXISTS refresh_tokens_retention_idx
  ON refresh_tokens (expires_at);
CREATE INDEX IF NOT EXISTS account_sessions_retention_idx
  ON account_sessions (access_expires_at);
CREATE INDEX IF NOT EXISTS account_lifecycle_events_retention_idx
  ON account_lifecycle_events (occurred_at);
CREATE INDEX IF NOT EXISTS account_audit_events_retention_idx
  ON account_audit_events (occurred_at);

CREATE TABLE IF NOT EXISTS email_delivery_webhook_receipts (
  webhook_id varchar(256) PRIMARY KEY
    CHECK (length(webhook_id) BETWEEN 1 AND 256 AND webhook_id ~ '^[A-Za-z0-9_-]+$'),
  provider_message_id varchar(128) NOT NULL
    CHECK (provider_message_id ~ '^[A-Za-z0-9_-]{1,128}$'),
  message_kind text NOT NULL CHECK (message_kind IN ('email_otp', 'device_share')),
  message_id uuid NOT NULL,
  event_type text NOT NULL CHECK (event_type IN (
    'email.sent',
    'email.delivery_delayed',
    'email.delivered',
    'email.complained',
    'email.bounced',
    'email.failed',
    'email.suppressed'
  )),
  event_created_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  applied boolean NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS email_delivery_webhook_receipts_received_idx
  ON email_delivery_webhook_receipts (received_at);
CREATE INDEX IF NOT EXISTS email_delivery_webhook_receipts_message_idx
  ON email_delivery_webhook_receipts (message_kind, message_id, event_created_at DESC);

UPDATE gateway_schema_state
SET version = 15,
    updated_at = now()
WHERE singleton = true;

COMMIT;
