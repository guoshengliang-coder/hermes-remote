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
    'device.share'
  ));

ALTER TABLE account_sessions
  ADD COLUMN IF NOT EXISTS external_identity_id uuid
    REFERENCES external_identities(id) ON DELETE SET NULL;

UPDATE account_sessions AS session
   SET external_identity_id = identity.id
  FROM external_identities AS identity
 WHERE session.account_id = identity.account_id
   AND session.external_identity_id IS NULL
   AND 1 = (
     SELECT count(*)
       FROM external_identities AS candidate
      WHERE candidate.account_id = session.account_id
   );

CREATE INDEX IF NOT EXISTS account_sessions_external_identity_idx
  ON account_sessions (external_identity_id)
  WHERE revoked_at IS NULL AND external_identity_id IS NOT NULL;

UPDATE gateway_schema_state
SET version = 13,
    updated_at = now()
WHERE singleton = true;

COMMIT;
