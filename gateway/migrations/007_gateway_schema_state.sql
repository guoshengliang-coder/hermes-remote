BEGIN;

CREATE TABLE IF NOT EXISTS gateway_schema_state (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  version integer NOT NULL CHECK (version > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO gateway_schema_state (singleton, version)
VALUES (true, 7)
ON CONFLICT (singleton) DO UPDATE
SET version = EXCLUDED.version,
    updated_at = now();

COMMIT;
