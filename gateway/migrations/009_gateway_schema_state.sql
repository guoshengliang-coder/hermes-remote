BEGIN;

ALTER TABLE reauthentication_grants
  DROP CONSTRAINT IF EXISTS reauthentication_grants_scope_check;
ALTER TABLE reauthentication_grants
  ADD CONSTRAINT reauthentication_grants_scope_check
  CHECK (scope IN (
    'connector.replace',
    'connector.unbind',
    'account.revoke_all',
    'account.identity.link'
  ));

CREATE INDEX IF NOT EXISTS external_identities_account_created_idx
  ON external_identities (account_id, created_at, id);

UPDATE gateway_schema_state
SET version = 9,
    updated_at = now()
WHERE singleton = true;

COMMIT;
