# Account mode I2 local test record

Date: 2026-09-02
Status: local I2 backend gate complete; not production-enabled.

## Implemented scope

- `connector_bindings` migration with one active and one pending binding per account enforced by
  partial unique indexes plus account-level transaction locking;
- first-binding creation and idempotent retry, with no last-login-wins replacement;
- pending-binding visibility only to its requesting Desktop; phones see no binding until activation;
- five-second, connection-specific, single-use Ed25519 possession challenge with origin-bound,
  length-prefixed canonical bytes;
- activation only after key proof and a successful Gateway-owned Hermes/end-to-end health record;
- Desktop-only installation list and single-phone revocation;
- phone-only current-installation revocation with lost-`204` replay after the caller is revoked;
- recent-reauthentication-gated replacement requests whose candidate does not disturb the active
  binding;
- atomic replacement confirmation after candidate key proof and healthy preflight, with immediate
  old-generation rejection;
- replacement expiry/consumption handling and same-key confirmation replay;
- explicit reauthentication-gated unbind, including atomic cancellation/revocation of a pending
  replacement candidate while leaving account sessions intact;
- independent `ACCOUNT_BINDING_ENABLED=0` rollout flag while legacy App/Connector tokens remain
  accepted;
- safe audit metadata and stable structured errors;
- strict protocol-v2 identify/challenge/authenticate/preflight/ready parsing while every legacy
  control and tunnel message remains protocol v1;
- `/v2/connect` Ed25519 authentication with five-second proof/preflight deadlines, bounded pending
  challenges, and global/per-IP unauthenticated socket capacity;
- account-token REST and WebSocket routing through the current active binding, with bearer stripping
  before Hermes forwarding and rejection of ambiguous legacy-plus-account credentials;
- separate `legacy:<deviceId>` and `account:<bindingId>` ownership so a same-ID legacy Connector
  cannot answer an account request;
- persisted Connector reachability/version/latency/online diagnostics and disconnect handling that
  cannot let a replaced socket mark its successor offline;
- periodic account WebSocket authorization/binding revalidation;
- bounded PostgreSQL account lifecycle storage with active-binding validation, durable duplicate
  acknowledgement, conflict rejection, and atomic fan-out to then-active phone installations;
- independently authenticated per-phone pagination, delivered, and read receipts, with cross-account
  reads/mutations returning no events or changes while legacy JSON lifecycle behavior stays intact.

## Results

| Command | Result | Notes |
| --- | --- | --- |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-db> RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | Pass | 31 passed; 0 skipped; PostgreSQL, V2 account routing, and legacy loopback integration passed |
| `npm test -w @hermes-remote/gateway` | Pass | 27 passed; 4 explicit PostgreSQL/loopback gates skipped in default mode |
| `ACCOUNT_DATABASE_URL=<temporary-local-db> npm run account:migrate -w @hermes-remote/gateway` (twice) | Pass | all 6 migrations applied and safely replayed |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-db> RUN_NETWORK_TESTS=1 npm test` | Pass | 98 passed; 0 skipped across Protocol, Connector, Gateway, Release Server, and scripts |
| `npm test -w @hermes-remote/protocol` | Pass | 13 passed, including all committed V2 handshake fixtures and malformed/version-confused inputs |
| `npm run build` | Pass | Protocol, Gateway, and Connector TypeScript builds pass |
| `git diff --check` | Pass | no whitespace errors in tracked changes; new I2 files also pass a direct trailing-whitespace scan |

The PostgreSQL test uses a random isolated schema. Two Desktop sessions race to create a first
binding and exactly one succeeds. The winner cannot activate before proof or after an unhealthy
preflight; a real Ed25519 signature and healthy preflight permit one activation and same-key replay.
An independently signed candidate then proves that wrong-scope grants fail, unhealthy/expired
replacement attempts preserve the original binding, concurrent same-key confirmation is replayable,
successful replacement leaves exactly one active generation, and the old proof material is rejected.
Two phone sessions verify that phone A can revoke itself and replay the lost response while phone B
and the active binding remain usable. Explicit unbind cancels a pending candidate without revoking
phone/Desktop sessions. A second account cannot observe the binding or target account A's
installations. The V2 routing test also connects account and legacy Connectors with the same
`deviceId`: their REST responses remain distinct, the account WebSocket reaches only its binding,
health is visible, dual credentials and account B are rejected, and disconnect persists offline.
One sanitized lifecycle transition is then replayed identically and acknowledged without duplication.
Both phones in account A receive it, phone A's delivered state and phone A2's read state remain
independent, and account B sees no event and cannot change either receipt.

## Next iteration and release gates

- Desktop/Android/new Connector end-to-end integration, including Keychain migration and UI states
  owned by I3–I5;
- physical two-phone, replacement-during-open-WebSocket, restart, and staged rollback exercises.

No production host, Mac mini service, legacy Connector configuration, or Hermes source,
configuration, credentials, data, or update path was changed. Both new account flags remain off by
default.
