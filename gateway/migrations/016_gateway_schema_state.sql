BEGIN;

-- One push registration per phone installation. The token is a delivery address only: pushes carry
-- a data-only wake hint and the phone still fetches the durable lifecycle inbox by cursor.
CREATE TABLE IF NOT EXISTS account_push_registrations (
  installation_id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  provider text NOT NULL CHECK (provider IN ('fcm')),
  token text NOT NULL CHECK (length(token) BETWEEN 1 AND 4096),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (installation_id, account_id)
    REFERENCES installations(id, account_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS account_push_registrations_account_idx
  ON account_push_registrations (account_id);

-- Revoking an installation drops its push registration in the same statement, whichever revoke
-- path (sign-out, managed revoke, account deletion) set revoked_at.
CREATE OR REPLACE FUNCTION account_push_registrations_drop_revoked() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM account_push_registrations WHERE installation_id = NEW.id;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS installations_revoked_drop_push_registration ON installations;
CREATE TRIGGER installations_revoked_drop_push_registration
  AFTER UPDATE OF revoked_at ON installations
  FOR EACH ROW
  WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
  EXECUTE FUNCTION account_push_registrations_drop_revoked();

UPDATE gateway_schema_state
SET version = 16,
    updated_at = now()
WHERE singleton = true;

COMMIT;
