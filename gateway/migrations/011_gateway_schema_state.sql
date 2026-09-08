BEGIN;

ALTER TABLE reauthentication_grants
  DROP CONSTRAINT IF EXISTS reauthentication_grants_scope_check;
ALTER TABLE reauthentication_grants
  ADD CONSTRAINT reauthentication_grants_scope_check
  CHECK (scope IN (
    'connector.replace',
    'connector.unbind',
    'account.revoke_all',
    'account.identity.link',
    'device.share'
  ));

ALTER TABLE account_device_preferences
  DROP CONSTRAINT IF EXISTS account_device_preferences_default_binding_id_account_id_fkey;
ALTER TABLE account_device_preferences
  ADD CONSTRAINT account_device_preferences_default_binding_id_fkey
  FOREIGN KEY (default_binding_id) REFERENCES connector_bindings(id);

CREATE TABLE IF NOT EXISTS device_share_invitations (
  id uuid PRIMARY KEY,
  binding_id uuid NOT NULL,
  owner_account_id uuid NOT NULL REFERENCES accounts(id),
  target_email_lookup_hash char(64) NOT NULL,
  target_email_hint text NOT NULL CHECK (length(target_email_hint) BETWEEN 3 AND 320),
  token_hash char(64) NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'accepted', 'cancelled', 'expired', 'delivery_failed')),
  delivery_status text NOT NULL DEFAULT 'pending'
    CHECK (delivery_status IN ('pending', 'sent', 'failed')),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  delivered_at timestamptz,
  accepted_at timestamptz,
  accepted_by_account_id uuid REFERENCES accounts(id),
  cancelled_at timestamptz,
  FOREIGN KEY (binding_id, owner_account_id)
    REFERENCES connector_bindings(id, account_id),
  CHECK (expires_at > created_at),
  CHECK (delivered_at IS NULL OR delivery_status = 'sent'),
  CHECK ((status = 'accepted') = (accepted_at IS NOT NULL)),
  CHECK ((status = 'accepted') = (accepted_by_account_id IS NOT NULL)),
  CHECK ((status IN ('cancelled', 'delivery_failed')) = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS device_share_invitations_one_pending_target_idx
  ON device_share_invitations (binding_id, target_email_lookup_hash)
  WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS device_share_invitations_owner_binding_idx
  ON device_share_invitations (owner_account_id, binding_id, created_at DESC);
CREATE INDEX IF NOT EXISTS device_share_invitations_expiry_idx
  ON device_share_invitations (expires_at)
  WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS device_access_grants (
  id uuid PRIMARY KEY,
  binding_id uuid NOT NULL,
  owner_account_id uuid NOT NULL REFERENCES accounts(id),
  grantee_account_id uuid NOT NULL REFERENCES accounts(id),
  grantee_email_hint text NOT NULL CHECK (length(grantee_email_hint) BETWEEN 3 AND 320),
  role text NOT NULL DEFAULT 'operator' CHECK (role = 'operator'),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked', 'left')),
  authorization_generation integer NOT NULL DEFAULT 1 CHECK (authorization_generation > 0),
  granted_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  left_at timestamptz,
  FOREIGN KEY (binding_id, owner_account_id)
    REFERENCES connector_bindings(id, account_id),
  CHECK (owner_account_id <> grantee_account_id),
  CHECK ((status = 'revoked') = (revoked_at IS NOT NULL)),
  CHECK ((status = 'left') = (left_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS device_access_grants_one_active_grantee_idx
  ON device_access_grants (binding_id, grantee_account_id)
  WHERE status = 'active';
CREATE INDEX IF NOT EXISTS device_access_grants_owner_binding_idx
  ON device_access_grants (owner_account_id, binding_id, status, granted_at DESC);
CREATE INDEX IF NOT EXISTS device_access_grants_grantee_idx
  ON device_access_grants (grantee_account_id, status, granted_at DESC);

CREATE OR REPLACE FUNCTION prevent_active_device_grant_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status = 'active'
     AND (NEW.binding_id <> OLD.binding_id
       OR NEW.owner_account_id <> OLD.owner_account_id
       OR NEW.grantee_account_id <> OLD.grantee_account_id
       OR NEW.role <> OLD.role) THEN
    RAISE EXCEPTION 'active device grant identity is immutable';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS device_access_grants_identity_immutable
  ON device_access_grants;
CREATE TRIGGER device_access_grants_identity_immutable
BEFORE UPDATE ON device_access_grants
FOR EACH ROW EXECUTE FUNCTION prevent_active_device_grant_identity_change();

ALTER TABLE account_idempotency_records
  ADD COLUMN IF NOT EXISTS device_share_invitation_id uuid
    REFERENCES device_share_invitations(id);
ALTER TABLE account_idempotency_records
  ADD COLUMN IF NOT EXISTS device_access_grant_id uuid
    REFERENCES device_access_grants(id);

UPDATE gateway_schema_state
   SET version = 11,
       updated_at = now()
 WHERE singleton = true;

COMMIT;
