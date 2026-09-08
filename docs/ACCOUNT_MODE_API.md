# Hermes GO account-mode API and data contract

Status: provider-neutral API/data contract with a default-off local E0-E9 implementation across
Gateway, Web, Desktop, and Android. No account endpoint in this document is enabled in production.
The account surface is versioned separately from the existing Hermes-compatible `/api/*` facade and
legacy `/v1/connect`.

Current rollout decision (2026-09-07): the first account release advertises and accepts only
`email_otp`. Google exchange/link/reauthentication contracts remain implemented for future use but
are absent unless the independent default-off `ACCOUNT_GOOGLE_AUTH_ENABLED` capability is enabled.
Email-only account and Web sessions require no Google client IDs.

## 1. Contract principles

- A Hermes GO email OTP proves identity in the first release; a later enabled Google/Apple provider
  may prove another identity. Hermes GO owns accounts, sessions, installations, bindings, and shares.
- Authorization keys are internal UUIDs and verified `(provider, issuer, subject)` tuples. A Google
  display email is metadata only. Email OTP uses a service-keyed subject after mailbox verification;
  equal email text never silently merges a Google identity and an email identity.
- New account endpoints use `/v2/*` and `Authorization: Bearer <Hermes GO access token>`.
- Provider ID/access tokens, OAuth codes, Hermes credentials, and Connector private keys are never
  accepted as application data outside their specific one-time exchange/proof operation.
- The existing `/api/*`, `/api/ws`, and `/v1/connect` contracts remain available in legacy mode.
- New Android traffic may use a Hermes GO access token on the existing Hermes-compatible facade;
  legacy clients continue using `X-Hermes-Session-Token: <APP_TOKEN>`.
- The Mac continues to initiate the outbound connection. No public or LAN inbound Mac listener is
  introduced.
- Account authentication and Connector authentication are independent. A mail-provider or future
  OAuth-provider outage must not force an already enrolled Connector to stop reconnecting.
- All mutation requests accept an `Idempotency-Key` UUID and return the same committed result when
  safely replayed by the same account/session.
- Timestamps are RFC 3339 UTC. IDs are opaque UUID strings. Clients do not derive semantics from IDs.

## 2. Token and credential classes

| Credential | Holder | Purpose | Proposed lifetime | Storage |
| --- | --- | --- | --- | --- |
| Google ID token | Desktop/Android/Web briefly | One-time identity proof exchange | Provider-defined, accepted only while valid | Never persisted by Hermes GO |
| Email OTP | Recipient briefly | One-time mailbox proof | 10 minutes, five attempts, single use | Keyed challenge hash only; plaintext sent through transactional provider |
| Hermes GO access token (`hga_…`) | One Desktop, phone, or browser session | Native `/v2/*`, account-mode `/api/*`, or Web account access | 15 minutes | Hash server-side; Keychain/encrypted native storage or Secure HttpOnly Web cookie |
| Hermes GO refresh token (`hgr_…`) | One client session | Rotate an access/refresh pair | 30 days absolute, rotated on every use | Hash server-side; Keychain/encrypted native storage or Secure HttpOnly Web cookie |
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

Completion-only mutations (`sign-out`, `revoke-all`, and permanent account deletion) keep an account/session/operation-scoped
completion record for up to 24 hours. This lets a client safely retry after a lost `204` even though
the first request already revoked its access token. The record contains no bearer value; its bounded
response marker is protected by the same authenticated-encryption mechanism. A changed request with
the same key returns `HR-ACCOUNT-005`.

Permanent deletion additionally writes one durable, account-free completion receipt made only from
keyed hashes of the idempotency key and exact request fingerprint. It contains no account ID, email,
bearer, grant, or response payload. This lets an intermittently used Desktop resolve the same
ambiguous deletion request even after the 30-day cleanup removes its session-scoped record; changed
input still conflicts.

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
    "providers": ["email_otp"],
    "android": true,
    "macos": true,
    "identityManagement": false,
    "accountDeletion": false,
    "webAccountCenter": false
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
  },
  "desktopBootstrap": {
    "runtimeContract": "hermes-serve-v1"
  }
}
```

`desktopBootstrap` is absent by default. Gateway emits it only when account binding and the separate
`ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=1` rollout flag are both enabled. Its presence does not
authorize an install by itself: Desktop additionally requires its packaged HTTPS manifest URL,
artifact origin, channel, architecture, pinned Ed25519 public key, and the exact same runtime
contract. Missing or mismatched inputs keep the install surface read-only.

When the independently gated E3 multi-device control plane is enabled, `binding` instead advertises
`maxActiveConnectorsPerAccount: 3` and `supportsDeviceSelection: true`. The new fields are absent and
the limit remains one while `ACCOUNT_MULTI_DEVICE_ENABLED=0`.

Rules:

- Missing/invalid capability responses fail closed to the currently saved mode; a client never
  deletes or overwrites working legacy configuration because discovery failed.
- `enabled=false` hides unfinished account actions but does not imply legacy removal.
- `providers` contains only independently enabled providers. The email-first profile returns
  `['email_otp']`; Google appears only with `ACCOUNT_GOOGLE_AUTH_ENABLED=1`. A disabled provider's
  routes use the uniform not-found contract and never invoke its verifier.
- `identityManagement` and `webAccountCenter` are separate default-off gates. The Web field may be
  true only while identity management is also enabled.
- `accountDeletion: true` is emitted only while the independent `ACCOUNT_DELETION_ENABLED` gate is
  enabled with identity management and at least one authentication provider. Its absence means every
  native and Web deletion route is not found and clients must hide the action.
- `webSessions: true` is emitted only while the separately gated HTTPS Cookie/CSRF contract is
  enabled. Its absence means `/account` may still be a static shell and must not handle credentials.
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

### `POST /v2/auth/email/challenges`

Unauthenticated, enabled only by the independent email OTP flag. It normalizes the mailbox
conservatively, binds the challenge to the client installation and platform, and applies per-mailbox
and per-source limits without looking up whether an account exists.

Request:

```json
{
  "email": "person@example.com",
  "platform": "macos",
  "clientInstallationId": "fdaed25e-f143-4e3c-b92b-0d881df13630"
}
```

Response `202`:

```json
{
  "challenge": {
    "challengeId": "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea",
    "expiresAt": "2026-09-07T00:10:00Z",
    "resendAfter": "2026-09-07T00:01:00Z"
  }
}
```

The public response shape does not disclose account existence. Within the 60-second cooldown, an
equivalent request returns the existing challenge without sending a second message. The provider
request uses `email-otp/{challengeId}` as its idempotency key. A failed provider request invalidates
the challenge and returns `HR-AUTH-010` without provider response details. A verified later
`bounced`, `complained`, `failed`, or `suppressed` event also invalidates an unconsumed challenge.

If the normalized mailbox is attached to an account whose permanent deletion has committed, the
same `202` shape, expiry, cooldown, and rolling-rate behavior still apply, but the Gateway stores a
suppressed correlation row and does not submit mail to the provider. Verification returns the same
generic `HR-AUTH-009` contract as any invalid or expired code. This prevents the challenge endpoint
from becoming an account-deletion lookup oracle. A provider submission already accepted or in flight
before deletion cannot be recalled; the deletion transaction invalidates its challenge so that code
cannot create a session. Challenge creation and final cleanup serialize on the same fingerprint lock,
so an in-flight request cannot strand a correlation row after the 30-day boundary.

### `POST /v2/webhooks/resend`

This is a provider callback, not a client API. It is present only while
`ACCOUNT_RESEND_WEBHOOK_ENABLED=1` and authenticates the untouched request body with the dedicated
Resend/Svix signing secret plus exactly one each of `svix-id`, `svix-timestamp`, and
`svix-signature`. Valid tracked events are durably deduplicated by `svix-id`, ordered by payload
`created_at`, and matched using both Resend's message ID and the opaque `hermes_kind` /
`hermes_message_id` tags attached at send time. Signed unrelated events are acknowledged and ignored.
Invalid input returns a generic `400`; a durable-store failure or a temporarily unmatched Hermes
message returns `500` so Resend retries. An unapplied duplicate retries matching after the outbound
provider ID commits, closing the send-response/webhook race.

The receipt table contains no mailbox, sender, subject, body, provider diagnostic, account ID, or
device ID. Hard terminal events invalidate an unused OTP and terminate a pending share invitation;
they never undo an already consumed OTP or accepted invitation.

### `POST /v2/auth/email/exchange`

Unauthenticated and requires an `Idempotency-Key` UUID. The request must use the same normalized
mailbox, platform, and client installation as the challenge.

```json
{
  "challengeId": "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea",
  "email": "person@example.com",
  "code": "012345",
  "platform": "macos",
  "clientInstallationId": "fdaed25e-f143-4e3c-b92b-0d881df13630",
  "displayName": "Office Mac mini",
  "appVersion": "0.4.0"
}
```

The code expires after ten minutes and after five failed attempts. Successful consumption records a
keyed derivative of the exchange idempotency key, allowing the exact lost-response retry to replay
the original Hermes GO session while a different key cannot reuse the code. Response `200` has the
same account/installation/session shape as Google exchange.

If no email identity exists, exchange creates a new account. If one exists, it reuses that account.
It never joins a Google-only account merely because the Google profile email has the same text;
cross-provider linking is a later authenticated account-settings operation.

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
Response returns a single-use, operation-scoped grant for `connector.replace`, `connector.unbind`,
`account.revoke_all`, `account.identity.link`, `account.identity.unlink`,
`account.installation.revoke`, or `device.share`. A grant is not a general access token. The request requires an
`Idempotency-Key` UUID so a lost grant response can be safely replayed within its lifetime.

### Email reauthentication and identity management

These routes require `ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1`; email variants also require the email
OTP feature. Issuer and provider subject are never returned.

- `GET /v2/account/identities` returns `{ "items": [...] }` with opaque identity ID, provider,
  optional display fields, and `verifiedAt`.
- `POST /v2/auth/reauth/email/challenges` requires an access token and `{ "email": "..." }`. The
  normalized mailbox must already be an email identity of the current account. The challenge is
  bound to the authenticated installation and has purpose `reauthenticate`.
- `POST /v2/auth/reauth/email` requires the current access token, `Idempotency-Key`, challenge ID,
  mailbox, code, and scope. It returns the same ten-minute, single-use grant shape as Google
  reauthentication only when the verified email identity belongs to the current account.
- `POST /v2/account/identities/email/challenges` creates an installation-bound `link_identity`
  challenge for the intended new mailbox.
- `POST /v2/account/identities/email` requires the current access token, `Idempotency-Key`, verified
  link challenge, and an `account.identity.link` grant created by re-verifying an existing identity.
- `POST /v2/account/identities/google` requires the current access token, `Idempotency-Key`, fresh
  Google ID token/nonce for the intended new identity, and the same scoped grant.
- `DELETE /v2/account/identities/{identityId}` requires the current access token, `Idempotency-Key`,
  and an `account.identity.unlink` grant. It returns the removed public identity plus
  `currentSessionRevoked`; removing the last usable identity fails with `HR-ACCOUNT-011`.

Linking is atomic. If the target provider/issuer/subject already belongs to another account, the
service returns `HR-ACCOUNT-008` and never moves or merges it. The exact successful retry replays the
protected committed identity response even though its single-use grant has already been consumed.
Schema 13 records the source identity on every new session. Unlinking is account-serialized, refuses
to remove the last identity, consumes a dedicated recent-authentication grant, deletes the identity,
and atomically revokes every access/refresh session known to have been created through it. Older
sessions that predate identity attribution are unaffected unless schema 13 could unambiguously
backfill their only account identity.

### `GET /account`

`ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=1` exposes an uncacheable account center. Its executable assets
are served only from exact same-origin `/account/assets/account.js` and `account.css` routes. The page
uses a deny-by-default CSP with only same-origin script, style, image, form, and API connections;
framing, base-URL rewriting, camera, microphone, location, payment, and USB are denied. Dynamic
provider/account/device strings are inserted with `textContent`, never HTML sinks. The UI becomes
interactive only when the independent secure-session and underlying capability flags are also on.

### Secure Web session endpoints

`ACCOUNT_WEB_SESSION_ENABLED=1` adds a Web-only session adapter without changing native Bearer APIs:

```text
GET  /v2/web/session
POST /v2/web/auth/email/challenges
POST /v2/web/auth/email/exchange
POST /v2/web/auth/refresh
GET  /v2/web/account
GET  /v2/web/identities
POST /v2/web/identities/email/challenges
POST /v2/web/identities/email
DELETE /v2/web/identities/{identityId}
GET  /v2/web/installations
DELETE /v2/web/installations/{installationId}
GET  /v2/web/audit-events
GET  /v2/web/devices
POST /v2/web/devices/{deviceId}/select-default
GET  /v2/web/devices/{deviceId}/shares
POST /v2/web/auth/reauth/email/challenges
POST /v2/web/auth/reauth/email
POST /v2/web/devices/{deviceId}/share-invitations
DELETE /v2/web/devices/{deviceId}/share-invitations/{invitationId}
DELETE /v2/web/devices/{deviceId}/shares/{grantId}
POST /v2/web/share-invitations/{token}/accept
POST /v2/web/devices/{deviceId}/leave
POST /v2/web/auth/sign-out
```

`GET /v2/web/session` establishes opaque browser-installation and CSRF cookies and returns
`authentication.google: null` in the email-first release. When the future Google provider is
explicitly enabled, this field contains its public Web OAuth client ID and the Google Web routes
listed in the provider appendix become available. Login creates a `kind=browser`, `platform=web`
installation using an installation-bound email challenge. Successful exchange and refresh place
`hga_` and `hgr_` values only in
`__Host-` cookies with `Secure`, `HttpOnly`, `Path=/`, and `SameSite=Strict`; JSON returns only safe
account/installation metadata and expiry timestamps. The readable CSRF cookie never contains a
session bearer.

Every Web `POST` requires the exact configured HTTPS `Origin`, non-cross-site Fetch Metadata when
present, a valid host-only CSRF cookie, and the same token in `X-Hermes-CSRF`. Duplicate/malformed or
oversized Cookie input fails with `HR-AUTH-012`. Refresh rotates the underlying account pair with the
existing replay-safe `Idempotency-Key` contract. Sign-out revokes the current account session and
expires all four Web cookies. Web routes ignore caller-supplied Authorization headers; native routes
continue to ignore cookies.

Committed sign-out, revoke-all, identity-session, installation, and sharing revocations emit a
bounded transaction-scoped PostgreSQL event. Every Gateway indexes active account tunnels by account,
session, installation, and binding so it can close only the affected sockets. PostgreSQL rollback
emits no event; the existing per-request check and five-second WebSocket revalidation remain the
authoritative fallback if a listener is reconnecting.

The email-first interactive slice supports email-code sign-in, current account/browser display, verified
identity discovery, owned/shared Mac discovery, default-device selection, and the whole-device share
lifecycle. Share creation obtains a short-lived `device.share` reauthentication grant from an
already-linked email identity and consumes it immediately; invite tokens are accepted only from the exact URL fragment and
are removed from browser history after success. The page never places account bearers in JavaScript
storage. Email identity link/unlink is available through a two-mailbox-verification flow. A Web
unlink that revokes its current source session also expires all four browser cookies. No third-party
identity script or origin is permitted by the email-first CSP. Identity management reauthenticates
through an already-linked email and can verify and link another email. The Gateway still resolves
ownership only by verified provider/issuer/subject and never by equal display email.

`GET /v2/web/installations` returns only the current account's active phone, Desktop, and browser
installations, their safe display fields, timestamps, current marker, and active-session count.
`DELETE /v2/web/installations/{installationId}` requires a single-use
`account.installation.revoke` grant and may target only another installation. It atomically revokes
that installation, all its account access/refresh sessions, and unused grants, but deliberately does
not mutate `connector_bindings` or local Hermes data. The current browser uses sign-out instead.
`GET /v2/web/audit-events` returns the latest 50 account events with event type, time, and an optional
safe actor-installation summary. Stored metadata is never serialized, so identity subjects, email
addresses, invitation details, target IDs, bearer material, prompts, outputs, and file content cannot
leave through this view.

The interactive installation-revocation flow likewise obtains its
`account.installation.revoke` grant from an already-linked email identity.

### Deferred Google provider appendix

When and only when `ACCOUNT_GOOGLE_AUTH_ENABLED=1`, the existing native and Web Google exchange,
reauthentication, and identity-link routes are restored to capability discovery. That later rollout
requires the platform-specific client IDs, exact Web origin, provider configuration, and live
acceptance already described by this contract. Equal display email never auto-merges identities.

### Internal email-delivery snapshot

Authenticated loopback operations may call `GET /internal/account-email-metrics` with the same
`INTERNAL_STATUS_TOKEN` used by `/internal/version`. It returns fixed one-hour aggregate counters for
OTP and device-share messages: requested, provider-accepted, provider-failed, pending, final
delivered/hard-failed/delayed, and the safe downstream verified/accepted count. `providerAccepted`
records only successful provider API acceptance; final fields come from verified callbacks. The response contains no email, recipient hash, challenge
or invitation ID, provider message ID, account/device identifier, or credential. Public Nginx routes
must continue returning 404 for `/internal/*`.

### Internal account-retention snapshot

Authenticated loopback operations may call `GET /internal/account-retention` with the same
`INTERNAL_STATUS_TOKEN`. The response reports whether a sweep is running, the last attempt/success/
failure timestamps, and process-lifetime deleted-row totals grouped only by data class, including
credential tombstones, lifecycle events, and audit events. It never
returns an account, installation, binding, invitation, provider-message, recipient, or credential
identifier. A null `lastAttemptAt` is normal during the 60-second startup delay. A newer
`lastFailureAt` than `lastSuccessAt` is the `HR-OPS-013` alert condition; login remains available and
the scheduler retries on its next six-hour interval. This endpoint also remains absent from public
Nginx routing.

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

### `DELETE /v2/account`

Permanent Cloud-account deletion is independently default-off. It requires the current access token,
an `Idempotency-Key` UUID, a fresh single-use `account.delete` reauthentication grant, and the exact
JSON acknowledgement:

```json
{
  "grant": "hgg_…",
  "acknowledgedPermanentCloudDeletion": true
}
```

On the first committed request, one transaction changes the account to `pending_deletion`, fixes its
cleanup deadline at exactly 30 days, revokes every account session, refresh token, installation, and
owned Connector binding, cancels invitations created by the account or addressed to any of its
verified emails, revokes owned shares, and leaves shares owned by others. A keyed, non-displayable
email fingerprint temporarily prevents a new invitation from recreating that relationship during
the deletion period. It also invalidates every unused email OTP for the account and retains a
purpose-separated keyed fingerprint so later challenge requests receive the ordinary neutral `202`
response without provider delivery. Affected live routes receive revocation notifications. The
response is empty `204`; an exact retry returns the same result even though the caller is already
revoked, while changed input with the same key returns `HR-ACCOUNT-005`.

The account cannot sign in during the waiting period. The period is a bounded cleanup delay, not a
restore window: there is no silent recovery or cancellation API. The retention scheduler deletes
Cloud identity/profile, installation, binding, invitation/share, lifecycle, credential, temporary
email/OTP fingerprints and correlation rows, and related cross-account audit rows in dependency-safe
bounded batches, then leaves only the account UUID/timestamps, one metadata-free `account.deleted`
audit event, and the account-free keyed completion receipt. Removing the final identity tuple permits
a later signup to create a new account ID. This operation never reaches into a Mac and never deletes
local Hermes sessions, files, configuration, credentials, or models.

Secure Web sessions expose the same operation at `DELETE /v2/web/account`; it additionally requires
the exact Origin/Fetch Metadata/CSRF boundary and clears all browser session cookies on `204`.

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

Desktop management removes one phone only when `ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1`. The request
requires the current Desktop access token, an `Idempotency-Key` UUID, and a JSON body containing a
single-use recent-authentication grant with scope `account.installation.revoke`:

```json
{ "grant": "hgg_..." }
```

For the email-first Desktop flow, that grant comes from `POST /v2/auth/reauth/email` after a code is
sent to and verified against an already-linked email identity of the current account. The operation
atomically revokes the selected phone installation's sessions, refresh families, unused grants, push
registrations, and future lifecycle delivery. It cannot target a Desktop/browser installation, the
calling Desktop, or a Connector binding; those have explicit operations. A successful exact retry
uses the same grant and `Idempotency-Key` and returns `204` even though the first request already
consumed the grant and revoked the phone. Reusing the key with a different target, grant, or target
kind fails with `HR-ACCOUNT-005`.

### `DELETE /v2/installations/current`

Phone-only convenience operation for “Sign out on this phone”. It has the same account isolation as
the explicit-ID route and cannot revoke other installations. It requires the current access token
and an `Idempotency-Key` UUID. The exact retry returns `204` even after the first call revoked the
calling phone's installation and all of its sessions.

## 6. Connector binding

### E3 plural device resources and routing

These resources are exposed only when `ACCOUNT_MULTI_DEVICE_ENABLED=1`:

```text
GET  /v2/devices
GET  /v2/devices/{deviceId}
POST /v2/devices/{deviceId}/select-default
DELETE /v2/devices/{deviceId}
```

`GET /v2/devices` returns `{ "items": [...], "maxOwnedDevices": 3 }`. Each active owned device
contains the existing safe binding/health view plus `access: "owner"` and `isDefault`. Detail lookup
authorizes by the authenticated account and opaque `deviceId`; absent, inactive, and foreign-account
IDs all return `HR-BIND-011`. Selecting a default requires an `Idempotency-Key` UUID and is replayable
only for the exact account/session/device tuple.

`DELETE /v2/devices/{deviceId}` requires JSON `{ "grant": "<fresh connector.unbind grant>" }`
and an `Idempotency-Key` UUID. Any signed-in installation may perform this explicit account action
after recent reauthentication. It revokes only the selected Connector generation and its pending
replacement, chooses another active owned Mac as default when necessary, and never deletes or edits
Hermes data on the Mac.

Hermes traffic uses explicit authenticated device paths:

```text
HTTP      /v2/devices/{deviceId}/api/{hermesPath}
WebSocket /v2/devices/{deviceId}/ws
```

The Gateway removes only the device-routing prefix before forwarding, preserves the query string,
and resolves the active binding from the authenticated account plus device ID. It never accepts an
owner account ID or display name. `/api/...` and `/api/ws` remain compatible when exactly one active
device exists; with multiple devices they fail with `HR-BIND-009` instead of choosing silently.
Clients persist `deviceId` on each conversation/task and use that explicit route when reopening it;
changing the current/default device affects only new navigation.

### E5 whole-device sharing

These resources exist only when `ACCOUNT_DEVICE_SHARING_ENABLED=1`. That switch additionally requires
account authentication, binding, multi-device selection, and identity management. Capabilities then
advertise `supportsDeviceSharing: true`, `maxSharedDevices: 10`, and `maxGranteesPerDevice: 5` under
`binding`; all three fields are absent while the switch is off.

`GET /v2/devices/{deviceId}/shares` is owner-only and returns:

```json
{
  "invitations": [{
    "id": "UUID",
    "deviceId": "opaque-device-id",
    "targetEmailHint": "g***@example.com",
    "status": "pending",
    "expiresAt": "RFC3339",
    "createdAt": "RFC3339"
  }],
  "grants": [{
    "id": "UUID",
    "deviceId": "opaque-device-id",
    "granteeEmailHint": "g***@example.com",
    "role": "operator",
    "status": "active",
    "grantedAt": "RFC3339"
  }],
  "maxGranteesPerDevice": 5
}
```

No plaintext destination mailbox, account ID, invitation token, or Hermes content is returned.

`POST /v2/devices/{deviceId}/share-invitations` is owner-only, requires an `Idempotency-Key` UUID and
a fresh single-use `device.share` reauthentication grant:

```json
{
  "email": "guest@example.com",
  "grant": "hgg_...",
  "acknowledgedWholeDeviceAccess": true
}
```

The exact `true` acknowledgement confirms that the recipient may access all sessions, files, and
configuration metadata exposed by the Hermes instance. The Gateway creates a 72-hour invitation,
stores keyed mailbox lookup material and a token hash only, and sends the plaintext address/link only
to the transactional email adapter. Response `202` is `{ "invitation": <masked invitation> }`; the
owner response never reveals the `hsi_...` bearer invitation. A failed mail handoff returns
`HR-SHARE-008` while retaining the pending invitation so an exact same-email/idempotency retry can
resume delivery without creating a duplicate.

The following owner mutations require an `Idempotency-Key` and return `204`:

```text
DELETE /v2/devices/{deviceId}/share-invitations/{invitationId}
DELETE /v2/devices/{deviceId}/shares/{grantId}
```

The first cancels a pending invitation. The second revokes one active operator grant, removes that
device as the grantee's default if necessary, rejects all new access immediately, and closes matching
live WebSockets. It never unbinds the owner's Connector or changes Mac data.

`POST /v2/share-invitations/{hsiToken}/accept` requires an authenticated account, an
`Idempotency-Key`, and `{ "acknowledgedWholeDeviceAccess": true }`. A verified email identity on that
account must match the invitation. The acceptance transaction locks both accounts and the binding,
then rechecks expiry, active ownership, duplicate state, the five-grantee device limit, and the
ten-shared-device account limit. It returns `{ "device": <operator AccountDevice> }` and consumes the
invitation exactly once.

`POST /v2/devices/{deviceId}/leave` requires an active operator grant and an `Idempotency-Key`, returns
`204`, clears a matching default selection, and invalidates that account's live access without
affecting the owner. An operator may route Hermes REST/WebSocket traffic and select the shared device
as a default, but cannot list/manage its shares, bind/replace/unbind it, or delegate access further.

Revocation notifications are immediate within the Gateway process. Every long-lived account-mode
WebSocket also revalidates its account/binding authorization immediately after upgrade and at least
every five seconds, so another Gateway process that did not receive the in-memory notification still
fails closed within that bounded interval. A future multi-node connection directory may replace the
bounded fallback with cross-process publication; it must not weaken per-request database checks.

### `GET /v2/connector-binding`

Returns exactly one of:

- `state=no_binding`;
- `state=binding_pending` to the requesting Desktop while a first Connector proves its key/health;
- `state=bound` with safe Desktop name, binding ID/generation, Connector online/last seen, observed
  Hermes reachability/version, Gateway latency, and end-to-end status;
- `state=replacement_pending` to the requesting Desktop only;
- `state=revoked` to an old Desktop whose binding generation was replaced.

Phone clients use this response to populate the existing Remote device stat and its detail page.
With E3 enabled it remains a compatibility view: a Desktop sees only its own binding state, and a
phone receives `HR-BIND-009` whenever more than one active device makes the singular response
ambiguous.

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

The database transaction creates a short-lived pending generation only when the current Desktop has
no active/live-pending binding and the account has free capacity. With E3 off, a concurrent loser
receives `409 HR-BIND-002`; with E3 on, up to three distinct Desktop installations reserve slots and
a concurrent fourth receives `409 HR-BIND-010`. The candidate must
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
- `client_installation_id`, `kind phone | desktop | browser`, `platform android | macos | web`
- only the pairs `phone/android`, `desktop/macos`, and `browser/web` are valid;
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

`email_otp_challenges`, `device_share_invitations`, and `email_delivery_webhook_receipts`

- outbound rows retain the bounded provider message ID plus latest final event type/time/rank;
- webhook receipts deduplicate by `svix-id` and retain only provider message ID, opaque Hermes
  message kind/UUID, event type/time, received time, and whether it applied;
- receipt ingestion removes up to 1,000 opaque receipts older than 35 days per transaction;
- new OTP issuance removes up to 1,000 challenges that are both expired and older than 35 days,
  preserving the full accepted provider-event correlation window without retaining mailbox/source/
  code hashes indefinitely;
- matching requires both the provider message ID and Hermes message UUID, and a newer event wins;
- hard terminal delivery events invalidate only unused OTPs and pending invitations.

`account_audit_events`

- `id uuid primary key`, account/installation/session references where safe
- action, outcome, target type/opaque target ID, occurred timestamp, correlation ID
- strict allowlisted metadata JSON; no provider proof, token, prompt, output, file path/content,
  Hermes credential, Cookie, authorization header, IP precision beyond the retention policy, or raw
  user-agent string

### Required transaction invariants

- with E3 off, an account has zero or one active Connector binding for legacy account-mode
  compatibility; with E3 on, an account has at most three active/live-pending owned device slots and
  each Desktop installation has at most one active binding generation;
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
