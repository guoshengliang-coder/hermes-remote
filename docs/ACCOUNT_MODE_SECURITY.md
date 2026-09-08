# Hermes GO account-mode security model

Status: I0 threat model. It covers Hermes GO components only and requires no Hermes change.

Rollout profile (2026-09-07): the first account release enables only email OTP. Google-specific
threats and controls remain mandatory regression coverage for the retained future implementation,
but no Google route, browser origin, client ID, or live-provider dependency is enabled in the email-
first profile.

## 1. Assets and trust boundaries

Protected assets:

- provider-neutral account ownership established by verified email OTP or a later enabled OAuth provider;
- verified email OTP account ownership and transactional-mail credentials;
- Hermes GO access/refresh sessions;
- phone-installation authorization and notification cursors;
- up to three independently revocable owned Connector bindings and their private keys;
- legacy App/Connector Tokens during migration;
- the Mac-local Hermes username/password, Cookie, WS tickets, files, prompts, and responses;
- binding/audit data and user display metadata.

Trust boundaries:

```text
email recipient <- Resend HTTPS API <- Gateway/account DB <- public Nginx <- Desktop/Android
                         signed webhook -> Gateway                        ^
                                                                          |
future OAuth provider ---------------- optional proof --------------------+

Gateway/account DB <- outbound Connector -> localhost/private Hermes
```

- Resend is trusted only to accept/deliver a challenge and report signed delivery events; successful
  account verification still requires the Gateway-held keyed code hash and all local constraints.
- A future OAuth provider is trusted only to sign an identity proof for an exact registered audience.
- Desktop and each phone are separate potentially lost/compromised installations.
- The public edge and Gateway receive Hermes GO credentials but never the Mac-local Hermes password.
- The Connector receives tunnel traffic for its active account binding and alone adds local Hermes
  credentials.
- Hermes is an external local dependency. Hermes GO observes/calls its existing APIs but never patches
  its code or data.

## 2. Security invariants

1. A verified `(provider, issuer, subject)` maps to one internal account; display email/name/avatar
   cannot authorize access. The email OTP provider's canonical verified mailbox is its subject.
2. Account A cannot address, observe, revoke, or route through account B's Connector or phones.
3. There are at most three distinct active or live-pending owned Desktop device slots per account,
   enforced transactionally under one account lock; a key-rotation candidate uses its existing slot.
4. A Mac may rotate only its own active binding; replacement requires recent reauthentication and a
   single-use explicit confirmation.
5. Phone revocation affects only that installation unless an account-wide operation is explicitly
   selected.
6. An OTP or future OAuth proof is never reused as a Hermes GO session or Connector credential.
7. Connector proof is possession-based, connection-bound, short-lived, and replay-resistant.
8. The Mac Hermes credential never leaves the Mac or appears in logs/diagnostics/account storage.
9. Legacy authentication stays isolated from account authentication; ambiguous dual credentials are
   rejected.
10. A failed migration leaves the last known-good Connector active and does not touch Hermes.
11. Email OTP challenges contain keyed hashes rather than plaintext mailbox/code/source values and
    bind verification to purpose, platform, installation, expiry, attempts, and exchange retry key.
12. Equal display email from a future OAuth profile and verified email OTP text do not implicitly join accounts.
13. Adding an identity requires both fresh proof of the target identity and a single-use
    `account.identity.link` grant created from an identity already attached to the same account,
    installation, and session.
14. Every multi-device Hermes request resolves an authenticated account plus an opaque active
    `device_id`; an omitted ID is accepted only when exactly one active binding exists.

## 3. Threats and required controls

| Threat | Impact | Required controls | Verification |
| --- | --- | --- | --- |
| Forged/tampered Google token | Account takeover | Official signature/JWKS verification; exact issuer/audience/expiry/nonce; TLS; bounded cache | Invalid signature/issuer/audience/nonce tests |
| Guessed/replayed email OTP | Account takeover | Six cryptographic digits; ten-minute expiry; five attempts; single-use consumption; platform/installation and exchange-key binding | Wrong-code/expiry/replay/cross-installation tests |
| Mailbox or source flooding | Delivery abuse and reputation loss | 60-second cooldown; database-serialized mailbox/source rolling limits; provider circuit breaker; generic account-existence response | Parallel/rate/capacity tests |
| OTP request probes or races cleanup of a deleting account | Account-state disclosure, unwanted post-deletion mail, or stranded correlation | Purpose-separated keyed deletion fingerprint; ordinary 202/cooldown/rate behavior; suppressed local correlation; no provider submission; generic verification failure; shared ordered advisory lock with terminal cleanup | Service/HTTP/PostgreSQL suppression, cleanup, and blocked-writer race tests |
| Transactional provider leak/failure | API-key or diagnostic disclosure | Sending-only key via protected file; HTTPS; bounded timeout; provider idempotency; sanitized errors; invalidated failed challenge | Adapter/error/redaction tests |
| Forged, replayed, raced, or reordered delivery webhook | Valid OTP retained after hard failure or false delivery state | Verify the untouched raw body with the dedicated Svix secret and exact unique headers; bounded body/time; durable `svix-id` deduplication with unapplied retry; provider-ID plus opaque-tag match; payload-time ordering | Signature mutation/duplicate-header tests and PostgreSQL duplicate/race/out-of-order tests |
| Delivery monitoring leaks identity data | Mailbox/account correlation | Loopback-only bearer-authenticated snapshot; fixed one-hour aggregates; 35-day opaque receipt retention; no recipient/hash/message/account/device identifiers; provider acceptance is not mislabeled as inbox delivery | Endpoint auth, response-shape, retention, SQL-window, and public-route tests |
| Stolen ordinary session links attacker identity | Durable account takeover | Reauthenticate an existing identity; bind grant to account/installation/session/scope; verify target separately; consume atomically | Wrong-account/grant/scope/replay and PostgreSQL race tests |
| Same target identity linked concurrently | Cross-account ownership ambiguity | Global identity advisory lock plus unique provider/issuer/subject constraint; never move an existing identity | Two-account concurrent-link integration test |
| Email change or duplicate display email | Cross-account join | Key only by provider issuer+subject; email display-only | Same email/different subject isolation test |
| OAuth callback interception | Desktop account takeover | System browser, PKCE S256, state, nonce, loopback-only ephemeral listener, short timeout | State/verifier/port/cancel tests |
| Stolen phone access token | Temporary account/Hermes access | 15-minute access expiry, installation binding, revocation, rate limits | Revoke and expiry tests |
| Stolen/replayed refresh token | Persistent phone access | 256-bit opaque token, hash at rest, rotate every use, family reuse detection/revocation | Parallel/reuse/restart tests |
| Cross-site Web mutation or login CSRF | Session confusion or unauthorized account mutation | Exact HTTPS Origin; `SameSite=Strict`; `__Host-` cookies; same-origin Fetch Metadata; double-submit CSRF token; reject duplicates | Missing/foreign Origin, cross-site, mismatch, duplicate-cookie tests |
| Web XSS reads a bearer | Account takeover | Access/refresh cookies are Secure+HttpOnly and never appear in JSON; deny-by-default shell CSP; no Hermes/provider credentials in Web storage | Response/cookie/CSP canary tests |
| Compromised or unavailable browser identity library | Provider-proof theft or blocked login | Fixed Google GIS URL only; CSP permits only its exact script and required GIS style/frame/connect parents; load only while signed out; exchange proof immediately; email remains available | Exact source/CSP/COOP, no-storage, invalid-proof, and live-provider acceptance |
| Browser identity gains Desktop authority | Connector takeover | Dedicated `browser/web` database identity; Desktop operations require exact `desktop/macos` installation | Schema-pair and authorization tests |
| Stolen Mac Connector key | Persistent Connector impersonation | Non-exportable/protected key where possible, signed challenge, generation/revoke/replace, no bearer copy in logs | Challenge/replay/old-generation tests |
| Malicious or confused second Mac | Redirect traffic or replace another Mac | Per-Desktop active binding, explicit opaque device selection, scoped replacement, atomic capacity check | Three-device/fourth-race and explicit-routing tests |
| Forged, leaked, or replayed share invitation | Cross-account whole-device access | 256-bit opaque token; hash at rest; 72-hour expiry; verified-mailbox match; single-use/idempotent acceptance; explicit disclosure | Malformed/expiry/replay/wrong-mailbox and storage inspection tests |
| Grantee exceeds delegated authority | Share escalation, re-sharing, or owner control | Fixed `operator` role; owner-only share/binding mutations; account+binding authorization on every query | Cross-account operation matrix and guessed-ID tests |
| Concurrent share acceptance overbooks capacity | More than five grantees or ten shared devices | Sorted advisory locks for both accounts and binding; counts and insert in one transaction; database uniqueness | Six-way and account-at-ten PostgreSQL races |
| Revoked account access keeps a live stream | Continued data access after sign-out/revoke/leave | Transaction-scoped PostgreSQL notification to every Gateway; exact account/session/installation/binding tunnel index; immediate post-upgrade plus five-second database revalidation fallback | Commit/rollback cross-connection notification, exact socket closure, and revoked REST tests |
| Cross-account object ID guessing | Data/control leak | Authorize every query by session account; uniform 404; opaque UUIDs | Cross-account matrix |
| WebSocket replay or pre-auth resource exhaustion | Impersonation/DoS | Random connection challenge, five-second timeout, single use, per-IP/global unauth limits, max payload | Replay/timeout/capacity tests |
| Token in URL/referrer/log | Credential leak | Authorization header only; reject query tokens in account mode; central redaction | Log/trace/proxy inspection |
| Ambiguous legacy + account credentials | Wrong-tenant routing | Reject requests containing both authentication modes | Dual-header test |
| Compromised Gateway/database | Account metadata/credential attack | Token hashes; authenticated-encrypted, bounded idempotency responses; public Connector key only; encrypted backups; least-privilege service role; rotation procedures | Storage inspection/restore drill |
| Connector routing bug | Cross-phone/account response leak | Owner maps include account+installation+request/tunnel; response only to exact owner | Adversarial concurrent routing tests |
| Multi-phone cursor sharing | Lost/leaked notifications | Per-installation cursor/ack; local read state remains local | Two-phone event tests |
| Logs/support bundle leak | Secret or content exposure | Strict allowlist; redact tokens/cookies/headers/queries; exclude prompts/output/files/provider claims | Canary-secret scans |
| Account/Google outage | Unnecessary service loss | Connector machine credential independent; cached valid sessions until expiry; clear layer-specific status | Dependency-outage tests |
| Migration interruption/power loss | Remote outage/duplicate Connector | Staged config, single-writer lock, exactly-one-process checks, durable state machine, automatic rollback | Kill at every state boundary |

## 4. Credential lifecycle

### Phone/Desktop account session

1. Client generates nonce and completes platform Google sign-in.
2. Client sends provider ID token once to `/v2/auth/google/exchange`.
3. Gateway verifies it and issues a Hermes GO access/refresh pair for one installation.
4. Access expires after 15 minutes. Refresh rotates both values.
5. Sign-out revokes the session; phone removal revokes all sessions for that phone.
6. Refresh reuse revokes the family and forces interactive sign-in on only that installation.

Email sign-in replaces steps 1-2 with challenge creation and a one-time code delivered through the
transactional provider. The database stores only domain-separated HMAC values for mailbox lookup,
request source, code, and the successful exchange retry key. The provider sees the destination and
message as required for delivery, but never receives Hermes GO session credentials.
After permanent deletion commits, the account's email-OTP identity hash suppresses future provider
submissions while preserving the ordinary challenge response, cooldown, and rate behavior. Existing
unused challenges are invalidated. Mail already accepted or in flight at the provider cannot be
recalled, but its code can no longer authenticate.

Desktop account UI logout does not implicitly revoke/stop the separately enrolled Connector. The UI
must say whether the user is signing out of management or unbinding remote access. Unbind is a
separate recent-reauthenticated destructive action.

### Browser account session

The Web adapter reuses the same 15-minute access and rotating 30-day refresh lifecycle but exposes
neither bearer to JavaScript. A host-only browser-installation cookie binds OTP, refresh, and account
session creation to one browser installation. Every mutation must pass exact-origin, Fetch Metadata,
and CSRF checks before it consumes an authentication rate-limit bucket or reads provider proof.
Browser installations are not Desktop installations and cannot bind, replace, unbind, or administer
Connector credentials.

The browser may list the current account's installations and revoke another installation only after
a distinct `account.installation.revoke` reauthentication. That transaction invalidates the target's
account sessions and grants but never changes Connector bindings. The security-event view exposes
only event type, occurrence time, and a safe actor-installation summary; the stored JSON metadata is
not part of the public contract.

The account-center application is delivered with no inline executable content. CSP denies all sources
by default. In the email-first profile it permits only the same-origin application/API and uses
`Cross-Origin-Opener-Policy: same-origin`. A later Google-enabled profile adds only the exact official
Google Identity Services script and its documented style/frame/connect parents and changes COOP to
`same-origin-allow-popups` so the provider popup can return its result. The page remains frame-denied
in both profiles. The Google library is loaded only after an unauthenticated bootstrap that advertises
the provider. Its ID token is submitted once with a page-generated nonce and is not retained in state
or Web storage. Untrusted account, identity,
device, and masked-email values enter the DOM through text nodes rather than HTML parsing. Access and
refresh credentials never enter JavaScript storage. A short-lived share reauthentication grant may
exist only in a local function while it is immediately consumed to create one invitation. An
identity-management grant remains only in page memory while its modal is open, is cleared on close or
failure, and is consumed only by the next provider-link or unlink mutation.
Google-only accounts use the same provider-neutral reauthentication endpoint for device sharing and
installation revocation; those grants retain their exact scope and are consumed by only the pending
mutation.

Every new session stores the opaque identity row that established it. Identity unlink uses a distinct
`account.identity.unlink` recent-authentication scope, serializes all identity removals for the
account, and refuses to reduce the usable set below one. In the same transaction it revokes every
known access session and refresh family created through the removed identity. The Web adapter clears
its cookies when that set includes the current browser. Pre-schema-13 sessions are backfilled only
when the account had exactly one identity, avoiding unsafe attribution guesses.

### Connector binding

1. Desktop generates a Connector key pair on the Mac.
2. Authenticated Desktop registers only the public key as a pending or active binding.
3. Gateway challenges each outbound `/v2/connect` connection.
4. Connector signs the challenge with the private key; Gateway verifies active account/generation.
5. Replacement atomically activates a new generation and invalidates only that Desktop's old generation.
6. Key rotation creates a new generation and commits only after candidate health validation.

The private key is never uploaded, copied into the DMG, stored in source control, or returned by a
diagnostic API.

## 5. Authorization matrix

| Operation | Phone | Desktop UI | Browser | Connector |
| --- | ---: | ---: | ---: | ---: |
| Read own account | Yes | Yes | Yes | No |
| Read active remote-device status | Yes | Yes | Yes | Own binding during proof only |
| Use Hermes facade | Yes | Optional diagnostic probe | No | Tunnel only |
| Sign out current session | Yes | Yes | Yes | No |
| Revoke current phone installation | Yes | No | No | No |
| List/revoke account installations | No in V1 | Phones only in V1 | Yes + recent reauth to revoke | No |
| Read metadata-free account audit events | No in V1 | No in V1 | Yes | No |
| Create first Connector binding | No | Yes | No | Proves key after creation |
| Replace/unbind Connector | No | Yes + recent reauth | No | No |
| List/create/cancel/revoke own-device shares | No in E5 | Yes; creation needs recent reauth | Yes; creation needs recent reauth | No |
| Use an owner-shared Hermes device | Yes when adopted | Yes | No | Tunnel only for owner binding |
| Accept/leave a device share | No in E5 | Yes | Yes | No |
| Re-share or bind/unbind someone else's device | No | No | No | No |
| Read prompts/responses/files from account DB | Never | Never | Never | Never; only transient tunnel access |
| Change Hermes source/config/update | Never | Never | Never | Never |

## 6. Privacy and retention

Store only what V1 needs:

- account UUID and provider subject tuple;
- display email/name/avatar only for UI, refreshable and deletable;
- installation names/platform/app versions/last seen;
- binding public key/fingerprint/generation/status/last seen;
- credential hashes and lifecycle timestamps;
- allowlisted security/audit events.

Do not store Hermes prompts, responses, session history, tool output, local file paths/content,
approval payloads, Hermes Cookie/password, provider ID token, raw OAuth claims, or exact provider
responses in the account database or audit log.

Retention is explicit and bounded per data class. A process-local scheduler starts 60 seconds after
account mode, then runs every six hours. Each transaction deletes at most 1,000 rows per eligible
table and uses locked, skip-locked batches so multiple Gateway processes may sweep safely. Expired
work is normally revisited on that cadence. When a sweep fills a table budget, progresses an account
deletion, or finalizes one account, it schedules another bounded sweep after one second; catch-up
stops as soon as a sweep no longer signals backlog. This avoids a six-hour-per-account deletion queue
without increasing any transaction's row budget. Idempotency responses are removed as soon as their
replay window ends. Email OTP correlation rows,
PII-minimizing Webhook receipts, device-share invitations, connector-replacement requests, and
their now-unreferenced reauthentication grants use a reviewed 35-day window. Refresh-token hashes
also remain for 35 days after their individual expiry for reuse investigation; deletion first clears
the historical `parent_id` of any newer child and never revokes or removes that child. A session
tombstone is removed only after its access expiry crosses the same boundary and no refresh token,
idempotency record, or reauthentication grant still references it. The window exceeds the 31-day
accepted provider-event time so valid retries remain matchable. Replacement cleanup revokes an
expired pending candidate binding before removing its request.

Sanitized lifecycle events use the configured 30-day default in addition to the existing per-account
10,000-row limit, so whichever limit is reached first wins; receipt rows cascade with their event.
Allowlisted security audit events use the configured 180-day default from the Cloud product
requirements. Both settings accept 1–3,650 days, are non-secret deployment configuration, and retain
their documented defaults when omitted.

Permanent account deletion is a separate default-off retention lane. A recent `account.delete`
proof and explicit permanent-deletion acknowledgement atomically move the account to
`pending_deletion` and immediately revoke its sessions, installations, bindings, invitations, and
device grants. Invitations addressed to any verified account email are also cancelled; a keyed
email fingerprint blocks replacement invitations while deletion is pending without exposing the
address. A distinct keyed email-OTP fingerprint immediately invalidates unused codes and suppresses
new delivery without changing the public challenge/cooldown/rate contract. After exactly 30 days,
the same skip-locked scheduler removes every account-linked Cloud row, matching invitation, OTP
correlation row, temporary fingerprint, and any other-account audit row containing that
invitation/grant hint in dependency-safe order using the remaining per-table batch budget. It then
keeps only a non-PII account tombstone and one metadata-free `account.deleted` audit event. The delay
is not an account-recovery promise, and the operation never deletes local data from a Mac.
Terminal cleanup acquires the same purpose-separated advisory locks as OTP and invitation creation,
in a stable order, before removing either correlation class. A writer that already holds a lock wins
and commits first; cleanup then removes its row. A writer that arrives after cleanup sees no deletion
fingerprint and follows the normal new-account/new-invitation rules.
One account-free deletion completion receipt remains as two keyed hashes so a long-offline client
can resolve an ambiguous retry; it contains no account, email, token, grant, or response data.

Sweep failure never disables login or readiness. It is reported only as `HR-OPS-013`, retried on the
next interval, and exposed through an authenticated aggregate-only operations snapshot. No account,
installation, binding, invitation, provider-message, or recipient identifier is emitted. Routine
installation history and device-access history are deliberately excluded until their product
retention policies are approved; the terminal account-deletion lane removes them because retaining
those account relationships would defeat the deletion request.

## 7. Failure behavior

- Provider verification unavailable: no new login; existing valid Hermes GO/Connector credentials
  continue according to their own expiry.
- Account DB unavailable: fail closed for new authorization/mutations; do not silently fall back from
  an account credential to a global legacy token.
- Connector proof failure: reject only that connection; do not modify the binding or Hermes.
- Replacement failure before commit: old binding remains active.
- Replacement commit succeeds but new Connector disconnects: show the new binding offline; never
  resurrect the old generation without an explicit rollback operation.
- Refresh reuse: revoke the family and require sign-in; do not reveal whether another device used it.
- Unknown account-owned resource: return the same not-found response as a cross-account identifier.
- Share email failure: retain only the pending hashed invitation state and permit exact retry; never
  reveal the destination or provider response in an error.
- Session/install/account/share revocation: deny new REST immediately and publish only after database
  commit. Every listening Gateway closes the exact account/session/installation/binding WebSocket;
  unrelated users, bindings, the Connector, and Hermes remain active. A missed notification remains
  bounded by the existing five-second authorization recheck.

## 8. Security review gate

I0 is accepted only when:

- all invariants map to API/database constraints and tests;
- no credential class has ambiguous ownership or storage;
- account mode adds no public Mac listener;
- replacement and migration have explicit commit/rollback boundaries;
- logs/diagnostics use allowlists rather than best-effort redaction alone;
- the old App/Connector Token path cannot grant account-management privileges;
- no Hermes modification is needed for authentication, routing, diagnostics, or rollback.
