BEGIN;

CREATE TABLE IF NOT EXISTS account_idempotency_records (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(id),
  session_id uuid REFERENCES account_sessions(id),
  refresh_token_id uuid REFERENCES refresh_tokens(id),
  reauthentication_grant_id uuid REFERENCES reauthentication_grants(id),
  operation text NOT NULL,
  idempotency_key uuid NOT NULL,
  request_hash char(64) NOT NULL,
  response_ciphertext text NOT NULL CHECK (length(response_ciphertext) BETWEEN 1 AND 32768),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  UNIQUE (account_id, operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS account_idempotency_records_expiry_idx
  ON account_idempotency_records (expires_at);

COMMIT;
