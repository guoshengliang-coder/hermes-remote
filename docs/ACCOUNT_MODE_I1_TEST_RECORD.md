# Account mode I1 local test record

Date: 2026-09-02
Status: local I1 authentication backend gate passed; account mode is not production-enabled.

## Scope under test

- default-off `/v2` routing and unchanged legacy routing;
- Google verifier audience/issuer/subject/nonce boundary and sanitized failures;
- opaque access, rotating refresh, and scoped reauthentication credentials;
- encrypted-response idempotency for Google exchange, refresh, reauthentication, sign-out, and
  account-wide revocation, including lost-`204` replay after the caller is revoked;
- refresh reuse mapping, current-session-only sign-out, and account-wide session revocation;
- request bounds, the 10,000-source limiter capacity boundary, structured errors, log/response
  redaction, and a persisted-database canary-secret scan;
- ordered PostgreSQL migrations, repository restart recovery, changed display-email account
  continuity, and concurrent same-key refresh rotation;
- existing Protocol, Connector, Gateway lifecycle, Release Server, and release-script regressions.

## Results

| Command | Result | Notes |
| --- | --- | --- |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-db> RUN_NETWORK_TESTS=1 npm test` | Pass | 88 passed; 0 skipped across Protocol, Connector, Gateway, Release Server, and scripts |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-db> RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | Pass | 24 passed; 0 skipped; real PostgreSQL and loopback HTTP/WebSocket integrations passed |
| `npm test -w @hermes-remote/gateway` | Pass | 22 passed; 2 explicit environment-gated integrations skipped in default mode |
| `node --check gateway/scripts/migrate-account.mjs` | Pass | migration runner parses cleanly |
| `git diff --check` | Pass | no whitespace errors |

## Gate outcome and remaining boundaries

The isolated-schema PostgreSQL test ran against an ephemeral PostgreSQL 18 instance bound only to
`127.0.0.1`. It passed ordered migrations, process/pool restart, concurrent same-key refresh replay,
different-key refresh reuse/family revocation, same-key sign-out/revoke-all replay, scoped
reauthentication, account-wide session revocation, multi-session isolation, changed display-email
continuity, and persisted-secret inspection. The temporary database is test evidence only, not a
production topology decision.

Real Desktop and Android Google authorization still requires the separately managed OAuth client
IDs, consent configuration, bundle/application identities, and client work in I3/I4. Binding and
multi-phone routing remain I2. Production database operations, retention/cleanup, backup/restore,
secret rotation, staged enablement, and physical-device acceptance remain later release gates.

No production host, Mac mini service, Connector configuration, or Hermes source/configuration/data
was changed during this run. `ACCOUNT_AUTH_ENABLED` remains off by default.
