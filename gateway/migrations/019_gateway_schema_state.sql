BEGIN;

-- HG-216: account profile field revisions also advance for legacy Web writes.
ALTER TABLE account_profiles
  ADD COLUMN sync_revision numeric(20,0) NOT NULL DEFAULT 1
    CHECK (sync_revision BETWEEN 1 AND 18446744073709551615),
  ADD COLUMN name_revision numeric(20,0) NOT NULL DEFAULT 1,
  ADD COLUMN avatar_revision numeric(20,0) NOT NULL DEFAULT 0;
UPDATE account_profiles SET avatar_revision = 1 WHERE avatar_data IS NOT NULL;
ALTER TABLE account_profiles ADD CHECK (name_revision BETWEEN 1 AND sync_revision),
  ADD CHECK (avatar_revision BETWEEN 0 AND sync_revision);

CREATE FUNCTION advance_account_profile_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.display_name IS DISTINCT FROM OLD.display_name
     OR NEW.avatar_data IS DISTINCT FROM OLD.avatar_data
     OR NEW.avatar_content_type IS DISTINCT FROM OLD.avatar_content_type THEN
    NEW.sync_revision := OLD.sync_revision + 1;
    NEW.name_revision := CASE WHEN NEW.display_name IS DISTINCT FROM OLD.display_name
      THEN NEW.sync_revision ELSE OLD.name_revision END;
    NEW.avatar_revision := CASE WHEN NEW.avatar_data IS DISTINCT FROM OLD.avatar_data
        OR NEW.avatar_content_type IS DISTINCT FROM OLD.avatar_content_type
      THEN NEW.sync_revision ELSE OLD.avatar_revision END;
  ELSE
    NEW.sync_revision := OLD.sync_revision;
    NEW.name_revision := OLD.name_revision;
    NEW.avatar_revision := OLD.avatar_revision;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER account_profile_revision BEFORE UPDATE ON account_profiles
  FOR EACH ROW EXECUTE FUNCTION advance_account_profile_revision();

-- Replays contain only terminal metadata, never names or avatar bytes. Parent deletion cascades.
CREATE TABLE account_profile_sync_operations (
  account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  op_id uuid NOT NULL,
  request_hash text NOT NULL,
  result jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, op_id)
);
CREATE INDEX account_profile_sync_operations_expiry ON account_profile_sync_operations(expires_at);
UPDATE gateway_schema_state SET version = 19, updated_at = now() WHERE singleton = true;
COMMIT;
