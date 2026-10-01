BEGIN;

-- HG-181: the Web app lets a signed-in account set a display name and upload an avatar, stored per
-- account and keyed to the mailbox the account signs in with. Android and Desktop keep reading the
-- identity's own name/avatar (external_identities); only the Web endpoints resolve this profile.
-- The avatar lives in the row as bytes: one small square image per account, no object store to
-- provision or back up separately.
CREATE TABLE IF NOT EXISTS account_profiles (
  account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 40),
  avatar_data bytea,
  avatar_content_type text CHECK (avatar_content_type IN ('image/png', 'image/jpeg', 'image/webp')),
  avatar_updated_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((avatar_data IS NULL) = (avatar_content_type IS NULL)),
  CHECK ((avatar_data IS NULL) = (avatar_updated_at IS NULL))
);

UPDATE gateway_schema_state
SET version = 17,
    updated_at = now()
WHERE singleton = true;

COMMIT;
