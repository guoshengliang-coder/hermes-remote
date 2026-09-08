# Hermes GO account-mode test plan

Status: test-impact and release-gate contract. Default-off local coverage now spans E0-E9; live mail,
physical accessibility/multi-device, privacy approval, signed artifacts, and production rollout
remain separate gates before account mode can become the default.

Current automated I1 evidence lives in `gateway/src/account-mode.test.ts` and
`gateway/src/account-database.integration.test.ts`. On 2026-09-02 the latter passed against an
isolated temporary PostgreSQL 18 instance, including database transaction/race/restart cases.
PostgreSQL is intentionally not bundled in the repository, and account mode remains disabled by
default.

`gateway/src/account-database.integration.test.ts` activates when
`ACCOUNT_TEST_DATABASE_URL` points to a disposable PostgreSQL database. It creates and removes an
isolated random schema and exercises migration, restart persistence, concurrent refresh rotation,
reuse detection, family revocation, changed display email without account reassignment, scoped
reauthentication, current-session sign-out, account-wide session revocation, idempotent lost-response
replay, and a persisted-data secret scan. It must never target a production database.

The latest command-level evidence and explicit skipped gate are recorded in
`ACCOUNT_MODE_I1_TEST_RECORD.md`.

The implemented I2 control-plane slices are covered by `gateway/src/account-control.test.ts` and
`gateway/src/account-control-database.integration.test.ts`. It exercises a real Ed25519
challenge, the one-pending/one-active database constraints, concurrent Desktop contenders,
proof/health activation gates, scoped reauthentication, atomic replacement, replacement expiry,
old-generation rejection, explicit unbind, Desktop-only installation management, current-phone
self-revocation, two-phone isolation, and cross-account denial. Current evidence and the explicitly remaining I2 work are recorded in
`ACCOUNT_MODE_I2_TEST_RECORD.md`.

`gateway/src/account-routing.integration.test.ts` adds a real Gateway/PostgreSQL/WebSocket path. It
proves Ed25519 V2 handshake and preflight, REST and WebSocket forwarding, health/offline persistence,
dual-credential rejection, account A/B denial, unauthenticated socket capacity, and separation from
a simultaneously connected legacy Connector using the same public `deviceId`. E3 extends it with two
simultaneously connected account Connectors, explicit REST/WebSocket device paths, ambiguous legacy
route rejection, and guessed cross-account device denial. The same test sends a
real account lifecycle event twice, verifies durable deduplication, gives two phones independent
delivery/read receipts, and proves another account cannot read or mutate either receipt.

## 1. Test layers

| Layer | Primary coverage | Required command/evidence |
| --- | --- | --- |
| Protocol | V2 auth/control parsers, bounds, canonical challenge bytes, compatibility | `npm test -w @hermes-remote/protocol` |
| Gateway/account | Google verification boundary, tokens, DB transactions, isolation, routes | Gateway unit/integration suite with disposable database |
| Connector | Challenge proof, generation/revocation, reconnect, migration recovery | Connector tests plus local smoke stack |
| Desktop core/UI | OAuth coordinator, Keychain, states, destructive confirmations, migration | Desktop test/app build and visual/accessibility inspection |
| Android data/UI | Credential exchange, encrypted sessions, startup states, Remote device route | JVM/unit/navigation/screenshot tests and debug build |
| Compatibility | Old Android/Connector against dual-mode Gateway | Versioned smoke clients and current baseline suites |
| Physical E2E | three owned Macs/fourth rejection, two phones, two-account sharing, restarts/outages | Timestamped test record without secrets |
| Release | APK identity/signature/hash; DMG codesign/notary when applicable | Existing package gates and clean-install evidence |

The normal repository baselines in `AGENTS.md` remain mandatory. Account tests are additive.

## 2. Contract fixtures

I1/I2 add sanitized deterministic fixtures for:

- valid Android and macOS provider claim shapes after signature verification;
- wrong issuer/audience/nonce, expired/missing subject, and email-change cases;
- access/refresh success, rotation, reuse, expiry, and revocation envelopes;
- no binding, healthy binding, offline Connector, Hermes unavailable, revoked generation, and
  replacement-pending responses;
- phone list with two installations and escaped/untrusted display names;
- every new structured `HR-AUTH-*`, `HR-BIND-*`, and `HR-MIGRATE-*` error;
- Connector challenge/authenticate/ready messages and canonical signed bytes;
- capability combinations for legacy-only, dual-mode, allowlisted account mode, and account default.

Fixtures contain only reserved example domains, fake UUIDs, fake keys, and impossible test tokens.

## 3. Authentication and session cases

- accept valid proof only for the registered platform audience;
- accept Web Google proof only for the distinct Web audience and persist it only as `browser/web`;
- reject invalid signature, issuer, audience, expiry, nonce, and missing subject;
- create one account for the same provider issuer+subject across Desktop/Android;
- never join accounts by equal email or display name;
- rotate access/refresh pairs and reject old refresh reuse;
- replay lost Google-exchange, refresh, and reauthentication responses only for the exact same
  account/session/request/`Idempotency-Key`; reject key reuse with changed input;
- normalize email without provider-specific dot/plus aliases; keep mailbox, code, and source out of
  challenge persistence; enforce cooldown, expiry, five attempts, single use, platform/installation
  binding, and database-serialized concurrent issuance;
- replay an email exchange only with the same challenge and exchange `Idempotency-Key`; reject a
  different key and prove equal Google display email does not merge the two identities;
- verify the Resend adapter's HTTPS endpoint, User-Agent, plaintext body, provider idempotency key,
  opaque correlation tags, provider receipt, bounded timeout, configuration rejection, and
  provider-error redaction without a live API key;
- verify Resend webhooks against the exact raw body and unique Svix headers; reject tampering,
  duplicate headers, stale timestamps, oversized bodies, malformed Hermes tags, and invalid signing
  secrets; acknowledge signed unrelated events without mutation; exercise these boundaries
  through a real loopback HTTP server as well as isolated controller tests, so Node header
  normalization cannot hide duplicate physical header lines;
- persist `svix-id` once, match both provider message ID and opaque Hermes UUID tag, reject stale
  out-of-order state changes, and prove bounce/complaint/failure/suppression invalidates an unused OTP
  or pending invitation without storing recipient, subject, body, or bounce diagnostics;
- on OTP issuance, delete no more than 1,000 expired challenges older than 35 days, preserve the
  35-day boundary, and retain enough correlation state for every accepted provider-event timestamp;
- run the unified retention sweep against disposable PostgreSQL; prove the per-table 1,000-row cap,
  exact expiry/35-day boundaries, dependency order, pending replacement-candidate revocation,
  refresh-token parent-chain detachment without disturbing a valid child, final session-tombstone
  removal, `SKIP LOCKED` concurrency, aggregate-only metrics, failure isolation, retry scheduling,
  and clean shutdown before the shared database pool closes;
- keep account deletion default-off; require an exact permanent-deletion acknowledgement plus a fresh
  `account.delete` grant; prove immediate session/installation/binding/share revocation, fixed 30-day
  state, exact replay versus conflicting replay, blocked sign-in, cancellation and temporary
  suppression of invitations addressed to a deleting identity, invalidation of its unused OTPs,
  neutral `202`/cooldown/rate responses with no provider send for later OTP requests, generic
  verification failure, removal of related cross-account email hints and OTP correlation rows under
  the shared per-table batch budget, serialization with in-flight OTP and addressed-invitation writes
  at final cleanup, durable account-free replay after final cleanup, dependency-safe PII cleanup,
  one-winner concurrent finalization, metadata-free tombstone audit, unrelated-account preservation,
  and new-account creation after the old identity tuple is removed;
- enforce the configured 30-day lifecycle and 180-day audit defaults at exact boundaries; prove
  lifecycle receipt cascade, preserve the existing per-account 10,000-event cap, and reject retention
  settings outside 1–3,650 days;
- aggregate the last hour of OTP/share provider acceptance, final delivery/hard failure/delay,
  initial failure, pending, and safe completion
  counts behind the loopback internal token; reject unauthenticated access and prove the snapshot has
  no mailbox, lookup hash, message/challenge/invitation ID, account, device, or provider credential;
- list identities without exposing issuer or subject; require a fresh existing-identity proof and a
  fresh target-identity proof for linking; consume an `account.identity.link` grant only on success;
  replay the exact committed result and reject cross-account identity conflicts without moving it;
- race two accounts linking the same unowned identity in disposable PostgreSQL and prove exactly one
  succeeds; keep the default-off `/account` shell uncacheable, frame-denied, and CSP-bound;
- keep Web sessions independently default-off; verify host-only Secure/HttpOnly/Strict session
  cookies, bearer-free JSON, exact Origin, Fetch Metadata, CSRF match, duplicate-cookie rejection,
  rotation replay, sign-out clearing, and native-Bearer/Cookie separation;
- replay lost sign-out and revoke-all `204` results for the exact same account/session/request/key
  even though the first request revoked the calling credential;
- revoke only current session on normal sign-out;
- revoke all sessions for one phone when that installation is removed;
- require recent reauth and correct scope for replace/unbind/revoke-all;
- rate-limit exchange/refresh without leaking account existence;
- survive service/database restart with token state intact;
- redact all token classes and provider proof from responses/logs/metrics/traces.

## 4. Binding, routing, and multiple phones

- first Desktop binding remains pending until key-possession/health proof and succeeds exactly once;
- with E3 off, two concurrent bind transactions yield one active binding;
- with E3 off, a second Desktop receives conflict and cannot change the active binding without confirmation;
- expired/used/wrong-account replacement requests fail without modifying the old binding;
- successful replacement invalidates the old key generation atomically;
- replacement same-key retries replay the committed result while a second confirmation is rejected;
- unhealthy and expired replacement candidates leave the prior generation active;
- unbind cancels a live replacement candidate, revokes only Connector credentials, and leaves
  account sessions intact;
- account A cannot route to or observe account B even with guessed IDs/device names;
- phone A and phone B share the same Hermes data path;
- revoking/signing out phone A leaves phone B and Connector online, including a lost-response retry
  after phone A's access has already been revoked;
- request/tunnel replies return only to their exact owner under concurrent traffic;
- phone lifecycle delivery cursors and notification acknowledgements are independent;
- local visual read/unread state is not cleared by another phone;
- an account request containing both legacy and account credentials is rejected;
- old App/Connector Token behavior remains unchanged while dual mode is enabled.
- with E3 enabled, four concurrent first-bind attempts admit exactly three and return
  `HR-BIND-010` for one without replacing existing bindings;
- plural discovery returns all and only the authenticated account's active devices and exactly one
  default after first activation/default selection;
- old singular and implicit Hermes routes still work with one device and return `HR-BIND-009` with
  multiple devices; explicit device REST/WebSocket routes reach only the selected binding;
- a guessed device ID from another account returns uniform `HR-BIND-011` and never reaches a Connector.
- with E5 enabled, owner share management never returns plaintext mailboxes, account IDs, or invitation
  tokens, and operators cannot list shares, invite, revoke, bind, replace, or unbind;
- six simultaneous invitation acceptances yield exactly five grants and one `HR-SHARE-002`; an account
  with nine shared Macs accepting two invitations reaches exactly ten and receives one `HR-SHARE-003`;
- wrong-mailbox, self-share, duplicate, expired, cancelled, consumed, malformed-token, and foreign-ID
  cases fail without creating a grant or disclosing another account;
- cancel, revoke, and leave replay only for the exact account/session/object/idempotency tuple and clear
  a matching shared default without changing the owner's default or binding;
- account/session/installation/share revocation publishes only on transaction commit; a second
  PostgreSQL connection receives the exact bounded event, rollback publishes nothing, every Gateway
  closes only matching active WebSockets, and periodic authorization revalidation bounds missed-event
  stale access to five seconds;
- feature-off capability and routes remain absent, and old Connector/Android/legacy routes are byte-
  compatible before and after schema 15.

## 5. Desktop cases

Before Desktop acceptance, the Web account center additionally verifies:

- the email-first release advertises only `email_otp`, starts Web sessions without any Google client
  ID, returns `authentication.google: null`, exposes no Google routes or visible controls, and permits
  no Google origin in CSP while `ACCOUNT_GOOGLE_AUTH_ENABLED=0`;

- the page, script, and stylesheet are uncacheable, same-origin, frame-denied, and protected by the
  exact CSP/Permissions-Policy contract;
- in the later Google-provider suite, the signed-out page loads only the fixed official Google Identity Services script, uses its rendered
  button (not a lookalike control), supplies a fresh page nonce, exchanges the returned proof without
  browser storage, and retains email as fallback; CSP and COOP permit only the documented popup,
  frame, style, and connection surfaces;
- no inline script, unsafe HTML sink, browser storage, account bearer, refresh bearer, provider proof,
  invite token, or reauthentication grant is rendered or persisted by the shell;
- email challenge/exchange, silent refresh, sign-out, identity/device discovery, default selection,
  installation/audit discovery, installation revocation, and share
  reauthentication/create/list/cancel/revoke/accept/leave all use Cookie authentication;
- email identity linking requires reauthentication of an existing mailbox followed by verification
  of the new mailbox; unlink requires its distinct grant, protects the final identity, revokes every
  attributable access/refresh session, clears current-browser cookies when needed, and serializes
  concurrent attempts so two identities cannot both disappear;
- Google-only accounts can reauthenticate with an already-linked Google identity before adding an
  email, while email accounts can reauthenticate by code before selecting a Google identity to link;
  both Google Web mutations retain the same Cookie/Origin/CSRF/idempotency and conflict isolation;
- sharing and installation revocation choose an already-linked email or Google identity for recent
  authentication, retain the exact `device.share` or `account.installation.revoke` scope, and never
  force a Google-only account to attach a mailbox;
- every Web mutation rejects missing/foreign Origin, cross-site Fetch Metadata, duplicate/malformed
  Cookie input, and missing/mismatched CSRF before any account mutation;
- installation revocation requires its exact recent-authentication scope, refuses the current
  installation, supports exact idempotent replay, revokes every target account session/refresh token,
  and leaves Connector binding rows unchanged;
- audit responses contain only event type/time and safe actor-installation fields, never stored JSON
  metadata, provider subjects, email addresses, target IDs, prompts, outputs, or file content;
- guessed/foreign device, invitation, and grant identifiers retain the uniform server error contract;
- keyboard-only navigation, focus visibility, dialog semantics, Chinese/English content, light/dark
  mode, narrow layout, 200% zoom, and screen-reader labels pass on the supported browser matrix.

The final accessibility/browser row remains a physical acceptance gate; source-level DOM/CSP tests
do not count as visual or assistive-technology evidence.

- OAuth system-browser account chooser with zero/one/multiple signed-in browser accounts, success,
  explicit account switch, user cancel, timeout, state mismatch, nonce mismatch, loopback bind
  failure, duplicate callback, and app restart; verify the app never reads browser cookies/profile;
- account session and Connector key remain separate in Keychain and diagnostics;
- account management sign-out does not masquerade as Connector unbind;
- unbind and replacement require explicit confirmation/recent reauth;
- phone list displays active/last-seen/revoked state; removing another phone first verifies the
  signed-in account email for an `account.installation.revoke` grant, rejects Desktop/browser
  targets, and retries an ambiguous committed response with the exact persisted key;
- account/Connector/Gateway/Hermes/end-to-end states remain distinct;
- I3 never changes legacy Connector PID, launch count, configuration, or Hermes;
- dark/light mode, keyboard navigation, VoiceOver labels, long names, and network recovery;
- packaging continues to use the canonical app icon.
- sharing capability-off UI stays absent; capability-on UI separates owned/operator devices, shows the
  whole-Hermes disclosure, and never permits an operator to delegate access;
- in the email-first Desktop, create invitation performs fresh owner-email reauthentication, persists
  only the scoped grant and request key in Keychain for ambiguous-response replay, keeps the six-digit
  code in UI memory, and redacts `hgg_`/`hsi_` values from diagnostics; the future Google-provider
  matrix separately repeats this case through browser reauthentication;
- invitation acceptance accepts only an exact `hsi_` token or account-link fragment, keeps it in memory,
  requires disclosure acknowledgement, and clears the field after success;
- cancel/revoke/leave affect only the selected invitation/grant/device and refresh the dashboard.

## 6. Android cases

- fresh-install email-code sign-in and existing-install opt-in migration;
- capability-off fallback, delivery failure, valid/invalid/expired code, and add/switch account;
- challenge UI derives expiry/resend countdowns from server timestamps, blocks early resend and
  locally known-expired submission, replaces old timing after resend, and stops ticking off-screen;
- process-death recovery restores only a still-valid encrypted challenge and exchange replay key,
  never restores the six-digit input, and fail-closes malformed/expired challenge timestamps;
- retryable account errors repeat their originating operation: initial email send, resend, code
  exchange, device refresh, or selected-Mac probe; non-retryable errors never execute a retry;
- no-Desktop state polls only while Remote devices is visible, stops after navigation away, and
  automatically becomes connected after a sole Desktop binds and passes the explicit-device probe;
- account session expiry asks for login without deleting preferences/history navigation;
- startup maps only expired/revoked/reused session families to interactive sign-in; refresh rate
  limiting, disabled account, temporary account service failure, and Connector offline preserve
  `HR-AUTH-007`, `HR-ACCOUNT-001`, `HR-ACCOUNT-002`, and `HR-CONN-005` respectively;
- after an unexpected account-session invalidation, an encrypted reauthentication gate survives
  process death and blocks REST, lifecycle inbox, WebSocket, startup, share, and new-chat fallback to
  any retained legacy Relay/App Token. A successful account exchange, explicit phone sign-out, or an
  explicit tap on Legacy connection clears the gate; merely retaining legacy credentials does not;
- transport mode is encrypted and survives process death. A signed-in account with no selected Mac
  blocks retained legacy REST/WebSocket/startup fallback with `HR-BIND-009`; only the explicit Legacy
  entry enables compatibility transport. A successful email exchange or device selection returns to
  account mode, while the account-level phone inbox remains available before a Mac is selected. Local
  sign-in/device repair gates stop WebSocket endpoint backoff until explicit/foreground recovery;
- invalidation retains the last account Gateway origin without credentials. Reauthentication uses
  that origin before any unrelated retained legacy Relay, and successful email login clears the gate;
- account sign-in/device repair is pushed over the current navigation stack. After the startup gate
  succeeds it removes the repair screens and restores the exact previous chat/page; a fresh install
  or missing previous stack falls back to Chats;
- live account WebSocket revocation closes with one classification reconnect: a `401` clears only
  the account session and persists the sign-in gate, while a `404` stops backoff and clears only the
  rejected selected-device route. Revocation of a historical/shared chat Mac preserves the valid
  default Mac and restores its transport route. The client acknowledges Gateway's 4403 close; if the
  one classification handshake is itself unavailable, it stops until explicit/foreground recovery;
- `HR-AUTH-006` recent-authentication requirements remain operation-scoped and never destroy the
  current account session or turn into the persistent sign-in gate;
- every account-routed Hermes REST response uses one centralized invalidation boundary: HTTP `401`
  and `HR-AUTH-003/004/005` persist the sign-in gate, while only explicit `HR-BIND-011` repairs the
  request's actual device route. Ordinary `404` and `HR-AUTH-006` preserve the account, selected Mac,
  and historical route;
- authenticated Account API calls from the account/device control surface use the same classifier:
  invalid session families immediately stop the active transport, current-device `HR-BIND-011`
  clears only that selection and stops its route, and `HR-AUTH-006` keeps the current transport while
  presenting its registered bilingual recent-authentication copy;
- healthy account is visible only in Settings, not Sessions/chat/card page;
- existing Hermes identity selection remains unchanged;
- Remote device stat is clickable and opens owned/shared Mac selection;
- a device switch updates the cloud default and passes an explicit-device end-to-end probe before
  local REST/WebSocket activation; a failed probe preserves the previous active mode;
- when the selected Mac disappears, one remaining accessible Mac is selected/probed automatically
  with one reconnect; zero or multiple remaining Macs clear the stale selection and retire its
  route. A failed sole replacement leaves no false local selection, while a failed ordinary switch
  keeps the still-valid current Mac;
- every session row is tagged with its source device and persists an encrypted, account/profile-
  scoped affinity; process recreation and notification/deep-link entry reopen it on that exact Mac;
- switching the default Mac changes lists, search, and newly created sessions only. An already-opened
  historical conversation keeps its explicit REST metadata/history and foreground WebSocket route;
  returning to Chats restores the selected/default route;
- equal profile/session IDs on two Macs remain separate runtime, unread, pin, history-cache, and
  notification identities; concurrent fetches and lifecycle events do not merge them;
- rename/archive/delete target the row's device explicitly, and successful deletion removes only
  that account conversation's stored affinity;
- revoked access to a conversation's original Mac returns and presents `HR-BIND-011` without falling
  through to an equal session ID on the current default Mac;
- account REST/WebSocket Bearer requests use a dedicated client and cannot inherit the legacy
  dashboard cookie jar, authenticator, App Token header, or query-token behavior;
- lifecycle inbox page/delivery ACK/read requests use the account-level Relay endpoint with the
  phone Bearer even when no Mac is selected; they never receive a device-route prefix or legacy
  credential. Local cursors are independently hashed/scoped by account installation, preserve the
  existing legacy cursor, and fail without consume/ACK/cursor advance if the account changes mid-sync;
- detail renders healthy, Connector offline, Hermes unavailable, Gateway unavailable, and revoked
  binding separately;
- no duplicate Connection & devices Settings entry exists;
- Legacy connection is reachable from Remote device details during compatibility, and entering it
  is the explicit user action that authorizes use of retained legacy credentials after invalidation;
- “Sign out on this phone” revokes the current installation and leaves another physical phone working;
- permanent account deletion is completely absent while `accountAuth.accountDeletion` is false.
  When enabled in Settings it requires exact ASCII `DELETE`, an independent acknowledgement, and a
  fresh current-email OTP for scope `account.delete`; none of those UI inputs enters persistence or
  diagnostics;
- the encrypted deletion challenge restores its absolute expiry/cooldown but never its OTP. After
  reauthentication, the grant and final mutation key commit before DELETE; network ambiguity and
  process death replay the exact request, while `HR-AUTH-006/009` discards stale proof without
  destroying the valid account session and `HR-ACCOUNT-012` completes local cleanup;
- committed deletion clears account credentials, stops account REST/inbox/WebSocket, preserves the
  last non-secret Gateway origin and all local/Legacy data, and persists a terminal gate across
  restart. Retained App Token credentials remain inactive until the user explicitly chooses Legacy;
  choosing another email account instead enters fail-closed sign-in without touching Legacy;
- process death, rotation, background/foreground, offline start, refresh race, and clock skew;
- TalkBack, 48dp targets, Chinese/English, large font, dark mode, and screenshots.

## 7. Migration fault-injection matrix

Inject failure or termination before and after each durable transition:

| Boundary | Expected recovery |
| --- | --- |
| Before snapshot | No mutation; legacy remains active |
| After snapshot, before staging | Remove/retain bounded snapshot per policy; legacy active |
| After key staging | Cancel pending key; legacy active |
| After legacy stop | Automatic rollback restarts exact legacy service |
| Candidate process start failure | Stop candidate remnants; restore legacy |
| Connector proof failure | Revoke/cancel pending generation; restore legacy |
| Local Hermes probe failure | Restore legacy; do not touch Hermes |
| End-to-end account probe failure | Restore legacy |
| Immediately before remote commit | Restore legacy; old binding authoritative |
| During/after remote commit response loss | Query generation; complete new mode or stop for attention, never guess |
| New generation reconnect failure after commit | Show account Connector offline; explicit post-commit rollback only |
| Rollback launch failure | Enter attention-required state; no retry loop/second Connector |

For every row verify:

- exactly one or zero intentionally stopped Connector processes, never two serving instances;
- no Hermes source/config/data/credential change;
- no secret in migration state/log/diagnostic output;
- deterministic restart recovery from the durable state;
- the phone receives a layer-accurate status.

## 8. Physical acceptance run

Hardware:

- target Apple Silicon Mac mini with current Hermes and legacy Connector;
- phone A and phone B with independent Android installations;
- a second Mac or isolated Desktop installation for replacement testing.

Sequence:

1. Record baseline Connector PID/launch count, Gateway device count, Hermes health/version, and
   legacy Android access.
2. In an otherwise idle staging environment, run read-only email preflight and the separately
   confirmed delivered/bounced Resend test-address cases; require an exact one-message provider and
   final-webhook aggregate delta, with no identifiers in the evidence.
3. Before the provider probe, run the credential-free DNS audit and require exact SPF TXT,
   Return-Path MX, DKIM TXT, and monitored DMARC checks; then confirm the provider dashboard is
   verified and inspect a delivered message for SPF/DKIM/DMARC pass headers.
4. Upgrade Desktop without migration; confirm every baseline remains unchanged.
5. Sign into Desktop and phone A with the same account; bind/migrate only after the explicit test gate.
6. Verify sessions, streaming, files, profiles, lifecycle notifications, and restart recovery.
7. Add phone B; run concurrent independent requests and notification/read-state checks.
8. Revoke/sign out phone A; verify phone B and Desktop continue.
9. Add a second and third owned Mac, reject a concurrent fourth, and exercise failed/successful
   replacement of one explicitly selected Mac without affecting the others.
10. Share one selected Mac to a second account, accept and use it, then verify revocation closes only
    that grantee's REST/WebSocket access.
11. Exercise Gateway/account/provider/network outages and all device restarts.
12. Run explicit post-commit rollback, verify legacy clients, then migrate forward again.
13. Inspect logs/diagnostics/storage for canary secrets and personal content.

The record includes timestamps, versions, hashes, status codes, safe correlation IDs, and pass/fail;
it never contains real tokens, provider proofs, Cookies, passwords, private keys, or message content.

## 9. Release gates

- **R0 Contract:** API, data, threat, error, migration, and test contracts reviewed.
- **R1 Backend:** auth/binding isolation, token lifecycle, DB restart, and compatibility tests pass.
- **R2 Client alpha:** Desktop and Android state/navigation/security tests pass against dev backend.
- **R3 Migration:** every fault-injection boundary returns to a known state; exactly-one-Connector
  invariant passes.
- **R4 Physical:** Mac mini + two-phone + second-Mac acceptance passes.
- **R5 Artifact:** versioned APK and Desktop package gates pass; docs and rollback are current.
- **R6 Production:** separately authorized staged enablement succeeds with monitored rollback signals.

Any cross-account access, credential leak, ambiguous binding, unrecoverable migration state, Hermes
mutation, or legacy regression is a release blocker regardless of other passing tests.
