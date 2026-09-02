BEGIN;

CREATE TABLE IF NOT EXISTS account_lifecycle_events (
  sequence bigserial PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  connector_binding_id uuid NOT NULL,
  event_id text NOT NULL CHECK (length(event_id) BETWEEN 1 AND 256),
  device_id text NOT NULL CHECK (length(device_id) BETWEEN 1 AND 128),
  profile text CHECK (profile IS NULL OR length(profile) <= 128),
  runtime_session_id text NOT NULL CHECK (length(runtime_session_id) BETWEEN 1 AND 256),
  stored_session_id text NOT NULL CHECK (length(stored_session_id) BETWEEN 1 AND 256),
  event_kind text NOT NULL CHECK (event_kind IN (
    'run.started', 'run.waiting', 'run.resumed', 'run.completed', 'run.interrupted', 'run.unknown'
  )),
  lifecycle_state text NOT NULL CHECK (lifecycle_state IN (
    'starting', 'working', 'waiting', 'idle', 'unknown'
  )),
  occurred_at timestamptz NOT NULL,
  title text CHECK (title IS NULL OR length(title) <= 256),
  received_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (connector_binding_id, account_id)
    REFERENCES connector_bindings(id, account_id),
  UNIQUE (account_id, event_id),
  UNIQUE (sequence, account_id)
);

CREATE INDEX IF NOT EXISTS account_lifecycle_events_account_sequence_idx
  ON account_lifecycle_events (account_id, sequence);

CREATE TABLE IF NOT EXISTS account_lifecycle_receipts (
  event_sequence bigint NOT NULL,
  account_id uuid NOT NULL REFERENCES accounts(id),
  installation_id uuid NOT NULL,
  delivered_at timestamptz,
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (installation_id, account_id)
    REFERENCES installations(id, account_id),
  FOREIGN KEY (event_sequence, account_id)
    REFERENCES account_lifecycle_events(sequence, account_id) ON DELETE CASCADE,
  PRIMARY KEY (event_sequence, installation_id)
);

CREATE INDEX IF NOT EXISTS account_lifecycle_receipts_installation_sequence_idx
  ON account_lifecycle_receipts (installation_id, event_sequence);

COMMIT;
