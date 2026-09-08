BEGIN;

ALTER TABLE installations
  DROP CONSTRAINT IF EXISTS installations_kind_check;
ALTER TABLE installations
  DROP CONSTRAINT IF EXISTS installations_platform_check;
ALTER TABLE installations
  DROP CONSTRAINT IF EXISTS installations_kind_platform_check;

ALTER TABLE installations
  ADD CONSTRAINT installations_kind_check
  CHECK (kind IN ('phone', 'desktop', 'browser'));
ALTER TABLE installations
  ADD CONSTRAINT installations_platform_check
  CHECK (platform IN ('android', 'macos', 'web'));
ALTER TABLE installations
  ADD CONSTRAINT installations_kind_platform_check
  CHECK (
    (kind = 'phone' AND platform = 'android')
    OR (kind = 'desktop' AND platform = 'macos')
    OR (kind = 'browser' AND platform = 'web')
  );

UPDATE gateway_schema_state
SET version = 12,
    updated_at = now()
WHERE singleton = true;

COMMIT;
