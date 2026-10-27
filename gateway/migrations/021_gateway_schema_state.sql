BEGIN;

-- Independent from per-device owner/operator grants. One ordered control-plane transaction.
CREATE TABLE hermes_compatibility_state (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991)
);
INSERT INTO hermes_compatibility_state(singleton) VALUES(true);
CREATE TABLE hermes_compatibility_admins (
  account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  granted_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);
CREATE TABLE hermes_compatibility_candidates (
  id uuid PRIMARY KEY,
  version text NOT NULL UNIQUE,
  tag text NOT NULL UNIQUE,
  tag_object char(40) NOT NULL,
  target_commit char(40) NOT NULL,
  installer_sha256 char(64) NOT NULL,
  discovered_at timestamptz NOT NULL DEFAULT now(),
  identity_conflict boolean NOT NULL DEFAULT false
);
CREATE TABLE hermes_compatibility_runs (
  id uuid PRIMARY KEY,
  candidate_id uuid NOT NULL REFERENCES hermes_compatibility_candidates(id),
  identity jsonb NOT NULL,
  identity_sha256 char(64) NOT NULL,
  state text NOT NULL CHECK (state IN ('queued','running','passed','failed','infrastructure_failed','cancelled')),
  requested_by uuid REFERENCES accounts(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  report jsonb,
  report_sha256 char(64),
  workflow_run_id text,
  workflow_run_attempt text,
  dispatch_attempts integer NOT NULL DEFAULT 0 CHECK (dispatch_attempts BETWEEN 0 AND 3),
  dispatch_after timestamptz NOT NULL DEFAULT now(),
  CHECK ((report IS NULL) = (report_sha256 IS NULL))
);
CREATE UNIQUE INDEX hermes_compatibility_active_identity ON hermes_compatibility_runs(identity_sha256)
  WHERE state IN ('queued','running');
CREATE TABLE hermes_compatibility_decisions (
  id uuid PRIMARY KEY,
  run_id uuid NOT NULL UNIQUE REFERENCES hermes_compatibility_runs(id),
  report_sha256 char(64) NOT NULL,
  content jsonb NOT NULL,
  content_sha256 char(64) NOT NULL,
  approved_by uuid REFERENCES accounts(id) ON DELETE SET NULL,
  approved_at timestamptz NOT NULL DEFAULT now(),
  state text NOT NULL CHECK (state IN ('approved','publishing','published','publication_failed','revoked')),
  revoked_at timestamptz
);
CREATE TABLE hermes_compatibility_snapshots (
  revision bigint PRIMARY KEY CHECK (revision BETWEEN 1 AND 9007199254740991),
  task_id uuid NOT NULL UNIQUE,
  requested_by uuid REFERENCES accounts(id) ON DELETE SET NULL,
  go_commit char(40) NOT NULL,
  payload jsonb NOT NULL,
  payload_sha256 char(64) NOT NULL,
  envelope jsonb,
  workflow_run_id text,
  workflow_run_attempt text,
  dispatch_attempts integer NOT NULL DEFAULT 0 CHECK (dispatch_attempts BETWEEN 0 AND 3),
  dispatch_after timestamptz NOT NULL DEFAULT now(),
  dispatch_accepted_at timestamptz,
  state text NOT NULL CHECK (state IN ('pending','published','failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE TABLE hermes_compatibility_operations (
  actor_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  operation_id uuid NOT NULL,
  request_sha256 char(64) NOT NULL,
  result jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_id, operation_id)
);
CREATE TABLE hermes_compatibility_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id uuid REFERENCES accounts(id) ON DELETE SET NULL,
  revision bigint NOT NULL,
  action text NOT NULL CHECK (action IN ('admin_granted','admin_revoked','candidate_discovered','candidate_conflict',
    'run_requested','run_dispatched','run_started','run_completed','run_cancelled','decision_approved','decision_revoked',
    'publication_started','publication_dispatched','publication_completed','publication_failed')),
  record_id uuid,
  content_sha256 char(64),
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX hermes_compatibility_audit_recent ON hermes_compatibility_audit(id DESC);
UPDATE gateway_schema_state SET version=21, updated_at=now() WHERE singleton=true;
COMMIT;
