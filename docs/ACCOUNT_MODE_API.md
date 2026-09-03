# Hermes GO account-mode API and data contract

Status: I0 contract with locally gate-passing, default-off I1 authentication and I2 control-plane
implementations plus an I3-A Desktop account client. No endpoint in this document exists in
production yet. The account surface is versioned separately from the
existing Hermes-compatible `/api/*` facade and legacy `/v1/connect`.

## 1. Contract principles

- Google proves identity; Hermes GO owns accounts, sessions, installations, and Connector bindings.
- Authorization keys are internal UUIDs and verified `(provider, issuer, subject)` tuples. Email is
  display metadata only and never an authorization or join key.
- New account endpoints use `/v2/*` and `Authorization: Bearer <Hermes GO access token>`.
- Provider ID/access tokens, OAuth codes, Hermes credentials, and Connector private keys are never
  accepted as application data outside their specific one-time exchange/proof operation.
- The existing `/api/*`, `/api/ws`, and `/v1/connect` contracts remain available in legacy mode.
- New Android traffic may use a Hermes GO access token on the existing Hermes-compatible facade;
  legacy clients continue using `X-Hermes-Session-Token: <APP_TOKEN>`.
- The Mac continues to initiate the outbound connection. No public or LAN inbound Mac listener is
  introduced.
- Account authentication and Connector authentication are independent. An interactive Google outage
  must not force an already enrolled Connector to stop reconnecting.
- All mutation requests accept an `Idempotency-Key` UUID and return the same committed result when
  safely replayed by the same account/session.
- Timestamps are RFC 3339 UTC. IDs are opaque UUID strings. Clients do not derive semantics from IDs.

## 2. Token and credential classes

| Credential | Holder | Purpose | Proposed lifetime | Storage |
| --- | --- | --- | --- | --- |
| Google ID token | Desktop/Android briefly | One-time identity proof exchange | Provider-defined, accepted only while valid | Never persisted by Hermes GO |
| Hermes GO access token (`hga_…`) | Desktop UI or one phone installation | `/v2/*` and account-mode `/api/*` access | 15 minutes | Hash server-side; Keychain/encrypted client storage |
| Hermes GO refresh token (`hgr_…`) | One client session | Rotate an access/refresh pair | 30 days absolute, rotated on every use | Hash server-side; Keychain/encrypted client storage |
| Connector private key | One Desktop installation/Connector | Sign server challenges for `/v2/connect` | Until rotated, revoked, or replaced | Mac Keychain or protected Connector credential store only |
| Connector public key | Gateway | Verify Connector challenge responses | Same binding generation | Account database |
| Reauthentication grant (`hgg_…`) | Current Desktop/phone session | Confirm one scoped destructive operation | 10 minutes, single use | Hash server-side; memory/Keychain only while active |
| Legacy App Token | Existing Android clients | Existing `/api/*` and `/api/ws` | Existing behavior during compatibility window | Existing protected locations |
| Legacy Connector Token | Existing Connector | Existing `/v1/connect` | Existing behavior during compatibility window | Existing protected locations |

Access and refresh tokens are opaque 256-bit random values. The database stores a keyed hash, token
family, expiry, use/revocation state, and safe metadata—not the bearer value. Refresh-token reuse
revokes the whole family and requires interactive reauthentication on that installation.

Credential-returning mutations persist an idempotency record so a lost response can be replayed
without minting another session or falsely triggering refresh-token reuse detection. Token columns
still contain hashes only; the small replay response is authenticated-encrypted with AES-256-GCM
under a domain-separated key derived from `ACCOUNT_TOKEN_HASH_KEY`, scoped to account, operation,
session where applicable, request fingerprint, and `Idempotency-Key`. It expires no later than the
credential it can replay and never appears in logs or diagnostics.

Completion-only mutations (`sign-out` and `revoke-all`) keep an account/session/operation-scoped
completion record for up to 24 hours. This lets a client safely retry after a lost `204` even though
the first request already revoked its access token. The record contains no bearer value; its bounded
response marker is protected by the same authenticated-encryption mechanism. A changed request with
the same key returns `HR-ACCOUNT-005`.

The Connector has no long-lived Google token or Hermes GO bearer token. It proves possession of its
private key by signing a short-lived random Gateway challenge bound to the binding ID, protocol
version, timestamp, and WebSocket connection nonce.

Clients persist the idempotency key for any in-flight refresh or completion mutation before sending
the request and clear it only after receiving the committed result. A timeout, process restart, or
lost response therefore retries the same input with the same key; it never creates a fresh key for an
already-consumed refresh credential.

## 3. Capability discovery

### `GET /v2/capabilities`

Public, rate-limited, cacheable for at most five minutes. It contains no account or device data.

Response `200`:

```json
{
  "version": 1,
  "accountAuth": {
    "enabled": false,
    "providers": ["google"],
    "android": true,
    "macos": true
  },
  "binding": {
    "enabled": false,
    "replacement": false,
    "maxActiveConnectorsPerAccount": 1
  },
  "legacy": {
    "appTokenAccepted": true,
    "connectorTokenAccepted": true
  },
  "server": {
    "version": "0.2.0",
    "protocolVersions": {
      "legacy": 1,
      "accountConnector": 2
    },
    "minimumClients": {
      "android": "0.1.0",
      "desktop": "0.2.0",
      "connector": "0.1.1"
    }
  }
}
```

Rules:

- Missing/invalid capability responses fail closed to the currently saved mode; a client never
  deletes or overwrites working legacy configuration because discovery failed.
- `enabled=false` hides unfinished account actions but does not imply legacy removal.
- `replacement=true` is advertised only when the independently gated binding surface is enabled and
  the replacement/unbind HTTP contract is available. The flag remains false in production while
  `ACCOUNT_BINDING_ENABLED=0`.
- Capability enablement and production deployment are separate operator actions.
- `server` is additive release metadata. Clients continue to gate behavior on capability fields,
  never by comparing the Server version string.

## 4. Google proof exchange and sessions

Desktop uses the system-browser installed-app flow with PKCE S256/state/nonce and an ephemeral
loopback callback. Android uses Credential Manager. Each client obtains an ID token for its own
registered OAuth audience, then sends it once to Hermes GO.

The Desktop browser flow deliberately reuses Google sessions already present in the default browser:
Google shows its own account chooser/consent page, so an existing account normally needs no password
entry. Android first requests already-authorized accounts and may auto-select only one eligible,
action-free credential; if none exists it repeats with the authorized-account filter disabled. These
are provider UX optimizations only—the Gateway never trusts a local account choice, email address,
browser cookie, or client claim without verifying the returned proof.

### `POST /v2/auth/google/exchange`

Unauthenticated, tightly rate-limited.

Requires an `Idempotency-Key` UUID. A retry with the same verified identity, installation, request,
and key returns the originally committed session. Reusing that key for different input returns
`HR-ACCOUNT-005`.

Request:

```json
{
  "platform": "android",
  "idToken": "<provider proof>",
  "nonce": "<original client nonce>",
  "clientInstallationId": "fdaed25e-f143-4e3c-b92b-0d881df13630",
  "displayName": "Pixel 9 Pro",
  "appVersion": "0.2.0"
}
```

`platform` is `android` or `macos`. The Gateway verifies signature, exact platform audience, issuer,
expiry, subject, and nonce before resolving/creating the Hermes GO account and installation. It never
accepts a caller-supplied email as identity.

Response `200`:

```json
{
  "account": {
    "id": "0a3c5b4a-29da-4872-a2a8-36c866934588",
    "displayName": "Liang",
    "email": "liang@example.com",
    "avatarUrl": null
  },
  "installation": {
    "id": "b5791214-1583-4737-a809-b3f2f03b3c61",
    "kind": "phone",
    "displayName": "Pixel 9 Pro"
  },
  "session": {
    "accessToken": "hga_<opaque>",
    "accessExpiresAt": "2026-09-02T04:15:00Z",
    "refreshToken": "hgr_<opaque>",
    "refreshExpiresAt": "2026-10-02T04:00:00Z"
  }
}
```

For `platform=macos`, `installation.kind` is `desktop`. Provider proof and raw provider claims never
appear in the response, log, metric, audit event, or diagnostic bundle.

### `POST /v2/auth/refresh`

Requires an `Idempotency-Key` UUID. Retrying the same rotation with the same key returns the original
committed access/refresh pair. Reusing the old refresh token with a different key is credential reuse,
revokes the family, and returns `HR-AUTH-005`.

Request:

```json
{
  "refreshToken": "hgr_<opaque>",
  "clientInstallationId": "fdaed25e-f143-4e3c-b92b-0d881df13630"
}
```

Response `200` returns a newly rotated access/refresh pair. The old refresh token becomes used before
the transaction commits. Reuse returns `HR-AUTH-005`, revokes the family, and never returns another
token.

### `POST /v2/auth/sign-out`

Requires the current access token and an `Idempotency-Key` UUID. Revokes only the current client
session. Android's “Sign out on this phone” then removes its local access/refresh material. It does
not revoke another phone, Desktop, or the Connector binding. An exact same-key retry returns the
same empty `204` result during the completion-record lifetime.

### `POST /v2/auth/reauth/google`

Requires a current access token plus a fresh Google ID token/nonce for the same external identity.
Response returns a single-use, operation-scoped grant for `connector.replace`, `connector.unbind`, or
`account.revoke_all`. A grant is not a general access token. The request requires an
`Idempotency-Key` UUID so a lost grant response can be safely replayed within its lifetime.

### `POST /v2/auth/revoke-all`

Requires the current access token, an `Idempotency-Key` UUID, and a fresh `account.revoke_all`
reauthentication grant in the JSON body. The transaction consumes the grant, revokes every account
session/refresh family, and invalidates the account's other outstanding reauthentication grants. It
does not unbind, stop, or reconfigure the Connector, and it never changes Hermes. An exact same-key
retry returns the same empty `204` after the first request revokes the caller. A used, expired,
wrong-account, wrong-installation, or wrong-scope grant returns `HR-AUTH-006` without partial
revocation.

### `GET /v2/account`

Returns the safe display account, current installation/session, and whether recent reauthentication
is present. It does not return the provider subject, token hashes, Connector public key, other
accounts, or Hermes credentials.

## 5. Phone installations

### `GET /v2/installations`

Desktop account management only in V1. Returns authorized phone installations plus the current
Desktop UI installation:

```json
{
  "items": [
    {
      "id": "b5791214-1583-4737-a809-b3f2f03b3c61",
      "kind": "phone",
      "platform": "android",
      "displayName": "Pixel 9 Pro",
      "lastSeenAt": "2026-09-02T04:00:00Z",
      "status": "active"
    }
  ]
}
```

An installation is created/reattached only after verified provider exchange. Display names are
untrusted metadata: length-limited, escaped, and never used for routing.

### `DELETE /v2/installations/{installationId}`

Desktop management removes one phone. It atomically revokes that installation's sessions, refresh
families, push registrations, and future lifecycle delivery. It cannot target the current Desktop
installation or Connector binding; those have explicit operations.

### `DELETE /v2/installations/current`

Phone-only convenience operation for “Sign out on this phone”. It has the same account isolation as
the explicit-ID route and cannot revoke other installations. It requires the current access token
and an `Idempotency-Key` UUID. The exact retry returns `204` even after the first call revoked the
calling phone's installation and all of its sessions.

## 6. Connector binding

### `GET /v2/connector-binding`

Returns exactly one of:

- `state=no_binding`;
- `state=binding_pending` to the requesting Desktop while a first Connector proves its key/health;
- `state=bound` with safe Desktop name, binding ID/generation, Connector online/last seen, observed
  Hermes reachability/version, Gateway latency, and end-to-end status;
- `state=replacement_pending` to the requesting Desktop only;
- `state=revoked` to an old Desktop whose binding generation was replaced.

Phone clients use this response to populate the existing Remote device stat and its detail page.
They do not list or choose multiple Connectors in V1.

### `POST /v2/connector-binding`

Desktop only. Creates the first **pending** binding candidate.

Request:

```json
{
  "desktopInstallationId": "979d7035-9ba5-456f-979a-98ab28ae89ec",
  "displayName": "Living-room Mac mini",
  "connectorPublicKey": "<base64url Ed25519 public key>",
  "keyAlgorithm": "Ed25519"
}
```

The database transaction creates a short-lived pending generation only when the account has no active
binding or live first-bind request. A concurrent loser receives `409 HR-BIND-002`. The candidate must
prove possession of the registered key and pass the required Connector/local-Hermes/end-to-end
preflight before activation. No last-login-wins behavior exists.

Response `201` returns the pending candidate without its public key:

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "generation": 1,
  "deviceId": "hermes-10000000-0000-4000-8000-000000000001",
  "displayName": "Living-room Mac mini",
  "publicKeyFingerprint": "<sha256 hex>",
  "state": "binding_pending",
  "expiresAt": "2026-09-02T04:10:00.000Z",
  "keyProved": false,
  "healthVerified": false
}
```

The request requires an `Idempotency-Key` UUID. Same-session retries return the same candidate;
changed input with that key returns `HR-ACCOUNT-005`.

### `POST /v2/connector-binding/confirm`

Desktop only. Confirms the first binding after the pending Connector has authenticated and passed the
required health checks. The transaction activates the candidate only if the account still has no
active binding, the request has not expired, and the proven key/generation match. Failure leaves the
account unbound and does not affect any legacy Connector.

Request:

```json
{
  "bindingId": "10000000-0000-4000-8000-000000000001",
  "generation": 1
}
```

The request requires an `Idempotency-Key` UUID. Confirmation returns `state=bound` and is safely
replayable after activation. Until both the single-use Ed25519 proof and Gateway-owned health check
are recorded, it returns `HR-BIND-005` without activating the candidate.

### `POST /v2/connector-binding/replacement-requests`

Desktop only. Requires a recent `connector.replace` reauthentication grant. Creates a pending
candidate without touching the active binding.

Request uses the same Desktop/key fields as first binding plus the scoped grant:

```json
{
  "desktopInstallationId": "979d7035-9ba5-456f-979a-98ab28ae89ec",
  "displayName": "Replacement Mac mini",
  "connectorPublicKey": "<base64url Ed25519 public key>",
  "keyAlgorithm": "Ed25519",
  "grant": "hgg_<opaque>"
}
```

It requires an `Idempotency-Key` UUID. The transaction consumes the single-use grant only when it
successfully commits the pending request. Same-session/same-input retries return the original
request and candidate; a wrong, expired, consumed, cross-account, cross-installation, or wrong-scope
grant returns `HR-AUTH-006` without changing the active binding.

Response `201`:

```json
{
  "id": "30000000-0000-4000-8000-000000000003",
  "state": "replacement_pending",
  "expiresAt": "2026-09-02T04:10:00.000Z",
  "previousBinding": { "id": "10000000-0000-4000-8000-000000000001", "generation": 1 },
  "candidate": {
    "id": "20000000-0000-4000-8000-000000000002",
    "generation": 2,
    "state": "binding_pending",
    "publicKeyFingerprint": "<sha256 hex>",
    "keyProved": false,
    "healthVerified": false
  }
}
```

The actual response includes the complete safe `ActiveBinding` and `BindingCandidate` fields shown
by the earlier endpoint examples, but never either public-key bytes or a private key.

### `POST /v2/connector-binding/replacement-requests/{requestId}/confirm`

Desktop only. Atomically:

1. verifies the request ownership, account, expiry, candidate key proof, and health result;
2. marks the previous generation replaced;
3. activates the candidate generation;
4. commits one audit event;
5. consumes the request (the scoped grant was already consumed when the request was committed).

If the transaction fails, the previous binding remains active. Connector health validation and
legacy process migration occur before this final commit where possible; the migration protocol in
`ACCOUNT_MODE_MIGRATION.md` owns the exact order. The endpoint has no JSON body and requires an
`Idempotency-Key` UUID. An exact retry returns the activated binding; a different confirmation after
the request was consumed returns `HR-BIND-003`.

### `DELETE /v2/connector-binding`

Desktop only. Requires a recent `connector.unbind` grant. Revokes the machine credential and remote
access. It accepts `{ "grant": "hgg_<opaque>" }` and requires an `Idempotency-Key` UUID. If a
replacement candidate is pending, unbind atomically cancels that request and revokes its candidate
key as well. It does not revoke the Desktop/phone account sessions, and it does not delete, stop,
restart, upgrade, or reconfigure Hermes.

## 7. Account-mode Connector WebSocket

Legacy Connector remains on `/v1/connect` with the existing version-1 `hello` token during the
compatibility window.

New Connector uses `/v2/connect`:

1. Open outbound WSS without a bearer token; unauthenticated connections have a small global/per-IP
   limit and a five-second proof timeout.
2. Connector sends `connector.identify` with its non-secret binding ID, generation, and public-key
   fingerprint. No routing/tunnel operation is permitted in this state.
3. Gateway loads that binding/candidate and sends `connector.challenge` with protocol version,
   binding ID, random 32-byte challenge, connection nonce, and server timestamp.
4. Connector signs the canonical binary payload containing all fields plus the expected Gateway
   origin and sends `connector.authenticate` with binding ID, generation, public-key fingerprint,
   and signature.
5. Gateway checks generation/key/revocation/expiry and signature, then sends a bounded
   `connector.preflight.request`.
6. Connector performs its existing read-only Hermes probe and returns
   `connector.preflight.result`; Gateway persists reachability, version, latency, and end-to-end
   health, registers the socket, and returns `connector.ready`.
7. An active binding may carry existing tunnel messages only after `connector.ready`. A pending
   binding may complete proof and health verification but is not routable until explicit activation.

The account Connector control messages use protocol version 2 with strict shared-parser tests. V1
messages retain protocol version 1 and `/v1/connect`; both parsers and paths coexist.

Replays fail because the challenge and connection nonce are random, connection-bound, short-lived,
and single-use. An old binding generation is rejected immediately after replacement commits.

## 8. Hermes-compatible facade authentication

### REST `/api/*`

Account-mode Android sends `Authorization: Bearer hga_<opaque>`. Gateway resolves the phone session,
installation, account, and active Connector binding, then routes to that binding's registered
Connector. The access token and account identifiers are removed before tunnelling to Hermes.

Legacy Android continues to send `X-Hermes-Session-Token: <APP_TOKEN>` and follows the existing
default-device behavior.

If both headers are present, Gateway rejects the request as ambiguous rather than guessing a mode.

### WebSocket `/api/ws`

Account mode uses the Authorization header on the WebSocket upgrade. Tokens are forbidden in query
strings. If a future platform cannot set an upgrade header, it must mint a short-lived, single-use,
origin/path-bound ticket through an authenticated `/v2/ws-ticket`; provider tokens are never tickets.

The Gateway records request/tunnel ownership by account session and installation. Connector replies
can return only to the exact owning tunnel/request, preserving the current request-owner invariant.
Open account WebSockets are periodically revalidated and close if the session or active binding
changes.

### Relay-owned lifecycle endpoints

`/api/mobile/events`, delivery acknowledgement, and notification read state resolve the phone
installation from the account session. Each installation has an independent delivery cursor. Local
visual read/unread state remains device-local in V1.

Account events are persisted separately from the legacy JSON inbox. Connector acknowledgement is
sent only after the event and its per-phone receipts commit. Duplicate identical events are safely
acknowledged; reuse of an event ID with different content fails closed. A phone can only page or
update receipts for its authenticated installation and account.

## 9. Data model

The account control plane requires a transactional relational store. PostgreSQL is the I0 production
target; the existing lifecycle JSON file is not extended into an account database.

### Core tables

`accounts`

- `id uuid primary key`
- `status active | disabled`
- `created_at`, `updated_at`, optional `disabled_at`

`external_identities`

- `id uuid primary key`, `account_id` foreign key
- `provider`, `issuer`, `subject`
- display-only `email`, `display_name`, optional `avatar_url`, `claims_updated_at`
- unique `(provider, issuer, subject)`

`installations`

- `id uuid primary key`, `account_id` foreign key
- `client_installation_id`, `kind phone | desktop`, `platform android | macos`
- escaped display name, app version, created/last-seen/revoked timestamps
- unique `(account_id, client_installation_id)`

`account_sessions`

- `id uuid primary key`, account/installation foreign keys
- access-token hash, access expiry, refresh family ID
- created/last-used/revoked timestamps and safe client metadata
- indexes on access hash, account, installation, and expiry

`refresh_tokens`

- `id uuid primary key`, session/family foreign keys, parent ID
- token hash, issued/expires/used/revoked timestamps
- unique token hash; one unused active leaf per family

`connector_bindings`

- `id uuid primary key`, account/desktop-installation foreign keys
- display name, logical `device_id`, public key, key algorithm/fingerprint, generation
- status pending | active | replaced | revoked
- created/activated/last-seen/replaced/revoked timestamps
- one partial unique active row per account; unique `(account_id, generation)`

`connector_replacement_requests`

- `id uuid primary key`, account/requesting-installation/candidate-binding foreign keys
- reauthentication grant hash/reference, expires/consumed/cancelled timestamps
- at most one live request per account; pending rows do not affect the active binding

`account_lifecycle_events` and `account_lifecycle_receipts`

- each sanitized event belongs to one account and active binding generation and is deduplicated by
  `(account_id, event_id)`;
- ingestion atomically creates one receipt for every then-active phone installation in the account;
- each receipt independently records delivered/read timestamps for one installation;
- pagination uses the event sequence while gaps from other accounts or retention are valid;
- phones added later do not receive historical notification receipts, and revoking one phone does
  not mutate another phone's receipts.

`account_audit_events`

- `id uuid primary key`, account/installation/session references where safe
- action, outcome, target type/opaque target ID, occurred timestamp, correlation ID
- strict allowlisted metadata JSON; no provider proof, token, prompt, output, file path/content,
  Hermes credential, Cookie, authorization header, IP precision beyond the retention policy, or raw
  user-agent string

### Required transaction invariants

- exactly zero or one active Connector binding per account;
- a pending first binding cannot activate before key-possession and required-health proof;
- no active session belongs to a revoked installation;
- refresh reuse revokes the family atomically;
- installation revocation revokes all its sessions and push registrations atomically;
- replacement either activates the new generation and replaces the old one together, or changes
  neither;
- every mutating idempotency key is scoped to account/session/operation and cannot replay across
  accounts;
- account A identifiers never authorize reads or mutations of account B, even when a display name or
  email matches.

## 10. Common response and error envelope

Successful responses use explicit JSON fields; absent optional fields are omitted rather than set to
misleading placeholder values.

Failures use:

```json
{
  "error": {
    "code": "HR-BIND-002",
    "message": "An active Desktop is already bound to this account.",
    "retryable": false,
    "recoveryAction": "verify_and_replace",
    "correlationId": "fcb51334-9ee2-4494-9dc4-81b67f878d74"
  }
}
```

HTTP mapping:

- `400` malformed/invalid bounded input;
- `401` missing, invalid, expired, replayed, or revoked authentication;
- `403` valid session without permission/recent reauthentication;
- `404` account-owned target absent or not visible (same response for another account's ID);
- `409` binding/idempotency/state conflict;
- `410` expired single-use replacement request;
- `429` rate limit;
- `503` disabled capability or temporary service dependency;
- `500` sanitized unexpected failure with a correlation ID.

Provider errors and database messages never become the primary client message. Stable localized
semantics come from `ERROR_HANDLING.md`.

## 11. Compatibility matrix

| Client/Connector | Legacy Gateway auth enabled | Account auth enabled | Result |
| --- | --- | --- | --- |
| Old Android + old Connector | Yes | Either | Existing behavior unchanged |
| New Android in legacy mode + old Connector | Yes | Either | Existing behavior unchanged |
| New Android account mode + old Connector before migration | Yes | Yes | Account can exist; remote path remains unavailable until binding/migration commits |
| New Android account mode + new Connector | Either | Yes | Account-aware routing |
| Old Android + new Connector | Yes | Yes | Supported only while the legacy mapping/grace period is explicitly active |
| Any account client | Either | No | Client stays on saved legacy mode or shows account capability unavailable |

No release disables a legacy column merely because the new client has shipped. Retirement follows
the separate G5 decision in `ACCOUNT_MODE_IMPLEMENTATION_PLAN.md`.
