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
    'account.identity.unlink',
    'account.installation.revoke',
    'device.share'
  ));

UPDATE gateway_schema_state
SET version = 14,
    updated_at = now()
WHERE singleton = true;

COMMIT;
