BEGIN;
CREATE TABLE IF NOT EXISTS account_web_push_subscriptions (
  installation_id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  session_id uuid NOT NULL REFERENCES account_sessions(id) ON DELETE CASCADE,
  channel_id uuid NOT NULL,
  endpoint text NOT NULL UNIQUE CHECK (length(endpoint) BETWEEN 1 AND 2048),
  subscription jsonb NOT NULL,
  language text NOT NULL CHECK (language IN ('zh','en')),
  FOREIGN KEY (installation_id,account_id) REFERENCES installations(id,account_id) ON DELETE CASCADE
);
CREATE OR REPLACE FUNCTION account_web_push_drop_revoked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM account_web_push_subscriptions WHERE installation_id=NEW.id;
  RETURN NULL;
END;
$$;
CREATE TRIGGER installations_revoked_drop_web_push
  AFTER UPDATE OF revoked_at ON installations FOR EACH ROW
  WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
  EXECUTE FUNCTION account_web_push_drop_revoked();
CREATE OR REPLACE FUNCTION account_web_push_drop_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM account_web_push_subscriptions WHERE session_id=NEW.id;
  RETURN NULL;
END;
$$;
CREATE TRIGGER sessions_revoked_drop_web_push
  AFTER UPDATE OF revoked_at ON account_sessions FOR EACH ROW
  WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
  EXECUTE FUNCTION account_web_push_drop_session();
UPDATE gateway_schema_state SET version = 18, updated_at = now() WHERE singleton = true;
COMMIT;
