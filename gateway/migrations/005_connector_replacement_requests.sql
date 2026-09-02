BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS connector_bindings_id_account_unique_idx
  ON connector_bindings (id, account_id);

CREATE TABLE IF NOT EXISTS connector_replacement_requests (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  requesting_installation_id uuid NOT NULL,
  previous_binding_id uuid NOT NULL,
  candidate_binding_id uuid NOT NULL,
  reauthentication_grant_id uuid NOT NULL REFERENCES reauthentication_grants(id),
  status text NOT NULL CHECK (status IN ('pending', 'consumed', 'cancelled', 'expired')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  cancelled_at timestamptz,
  FOREIGN KEY (requesting_installation_id, account_id)
    REFERENCES installations(id, account_id),
  FOREIGN KEY (previous_binding_id, account_id)
    REFERENCES connector_bindings(id, account_id),
  FOREIGN KEY (candidate_binding_id, account_id)
    REFERENCES connector_bindings(id, account_id),
  UNIQUE (candidate_binding_id),
  CHECK (previous_binding_id <> candidate_binding_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS connector_replacement_requests_one_pending_per_account_idx
  ON connector_replacement_requests (account_id) WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS connector_replacement_requests_account_status_idx
  ON connector_replacement_requests (account_id, status, created_at DESC);

ALTER TABLE account_idempotency_records
  ADD COLUMN IF NOT EXISTS connector_replacement_request_id uuid
    REFERENCES connector_replacement_requests(id);

COMMIT;
