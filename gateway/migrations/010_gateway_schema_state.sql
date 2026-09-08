BEGIN;

DROP INDEX IF EXISTS connector_bindings_one_active_per_account_idx;
DROP INDEX IF EXISTS connector_bindings_one_pending_per_account_idx;
DROP INDEX IF EXISTS connector_replacement_requests_one_pending_per_account_idx;

CREATE UNIQUE INDEX IF NOT EXISTS connector_bindings_one_active_per_desktop_idx
  ON connector_bindings (desktop_installation_id) WHERE status = 'active';

CREATE UNIQUE INDEX IF NOT EXISTS connector_bindings_one_pending_per_desktop_idx
  ON connector_bindings (desktop_installation_id) WHERE status = 'pending';

CREATE UNIQUE INDEX IF NOT EXISTS connector_replacement_requests_one_pending_per_binding_idx
  ON connector_replacement_requests (previous_binding_id) WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS account_device_preferences (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  default_binding_id uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (default_binding_id, account_id)
    REFERENCES connector_bindings(id, account_id)
);

INSERT INTO account_device_preferences (account_id, default_binding_id)
SELECT account_id, id
  FROM connector_bindings
 WHERE status = 'active'
ON CONFLICT (account_id) DO NOTHING;

UPDATE gateway_schema_state
   SET version = 10,
       updated_at = now()
 WHERE singleton = true;

COMMIT;
