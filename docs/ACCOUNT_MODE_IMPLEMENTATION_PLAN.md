# Hermes GO account-mode implementation plan

Status: accepted implementation plan. I0, I1, and the local I2 backend gate are complete. The local
I3-A Desktop account-client slice is complete; I3-B destructive management/reauth states are next.
Estimates are
engineering effort, not release promises.
No production deployment, Connector replacement, or Hermes change is authorized by this document.

Progress: **I0 and the local I1 backend gate completed on 2026-09-02**. The API/data contract, threat model,
migration state machine, error registry, test plan, and sanitized fixtures are present. The local I1
slice includes a default-off account runtime, Google verification boundary, token
issue/rotation/revocation, PostgreSQL schema/repository, capability discovery, bounded HTTP routes,
rate limits, audit events, persistent idempotency for credential and completion-only mutations, and
focused unit/integration tests. The isolated PostgreSQL 18 transaction/restart/race suite, expanded
Google failure mapping, persisted-secret scan, and limiter-capacity test pass locally. It has not
been deployed or enabled; live Google OAuth-client setup and production operations remain later,
separately authorized gates.

I2 started locally on 2026-09-02. Its implemented slices add the `connector_bindings` and
`connector_replacement_requests` schemas and
database-enforced one-pending/one-active constraints, independently gated installation/binding HTTP
routes, Ed25519 single-use challenge verification, proof-plus-health activation, phone listing and
single-phone/current-phone revocation, reauthentication-scoped atomic replacement, explicit unbind,
audit events, strict `/v2/connect` identify/challenge/authenticate/preflight/ready messages,
bounded unauthenticated sockets, account-aware REST/WebSocket routing, online/offline diagnostic
updates, account-scoped lifecycle events with independent per-phone delivered/read receipts, and
concurrent/cross-account PostgreSQL tests. The local I2 backend exit gate passed with six repeatable
migrations and the full isolated network/database suite. Client integration remains I3–I5 work, so
both account flags remain off and nothing has been deployed.

I3-A completed locally on 2026-09-02. It adds capability-gated system-browser Google OAuth with PKCE,
state, nonce, explicit account choice, cancellable ephemeral loopback callback, ID-token exchange,
separate Keychain account/machine identity records, crash-safe refresh idempotency, account/binding/
phone reads, one-phone removal, Desktop-session-only sign-out, account/menu status, and a collapsed
legacy pairing surface. It intentionally performs no binding/replacement/unbind/Connector mutation.
Live Google credentials and all production flags remain absent/off.

Related design: `ACCOUNT_MODE_DESIGN.md`.

## 1. Outcome and fixed boundaries

The release is complete when a user can sign into Hermes Go Desktop and Android with the same Google
account, automatically reach the one bound Hermes, use more than one phone independently, diagnose
the connection by layer, and migrate from the current Token flow without breaking existing clients.

Fixed boundaries:

- Do not modify Hermes source, configuration, data files, credentials, or update process.
- One Hermes GO account has at most one active Desktop Connector binding in V1.
- One Connector reaches one local Hermes; multiple phones may use that same binding.
- Android has no separate “Connection & devices” entry. The existing **Remote device** stat is the
  entry to the single Hermes and its connection details.
- Android account management lives only in Settings except when sign-in or recovery is required.
- Desktop owns account-wide phone listing/revocation and Connector replacement.
- Existing App Token/QR clients remain functional through the compatibility window.
- A source change never implies production deployment. Deployment remains separately authorized.

Out of scope for V1:

- multiple Hermes instances or multiple Connectors under one account;
- family/team sharing, roles, invitations, or delegated administration;
- Google Drive, Gmail, contacts, or other Google data access;
- a web management console;
- remote restart, upgrade, or configuration changes to Hermes;
- cross-device collaborative control of one interactive approval;
- removing legacy Gateway authentication in the same release that account mode becomes available.

## 2. Iteration map

| Iteration | Focus | Effort | Depends on | Exit gate |
| --- | --- | ---: | --- | --- |
| I0 | Contract, threat model, UX freeze | 2–3 days | Approved product direction | API/schema/error contracts reviewed; no unresolved security boundary |
| I1 | Account authentication foundation | 4–6 days | I0 | Google proof exchange, Hermes GO sessions, rotation and revocation pass integration tests |
| I2 | Binding and installation control plane | 4–6 days | I1 | One-account/one-Connector enforced atomically; multiple phones isolated correctly |
| I3 | Desktop account client | 4–6 days | I1–I2 contracts | Desktop sign-in, Account & Devices, conflict and reauth states work without takeover |
| I4 | Android account client | 5–7 days | I1–I2 contracts | Sign-in, startup states, Settings account, Remote device detail and legacy fallback work |
| I5 | Connector credential migration | 5–7 days | I2–I4 | Existing Connector upgrades transactionally, validates health, and rolls back on failure |
| I6 | Hardening and staged release | 4–6 days | I3–I5 | Two-phone/second-Mac/restart/security/release gates pass |

Single-stream planning range: roughly **28–41 engineering days**. I3 and I4 may proceed in parallel
after the I2 contract is stable; with independent Desktop and Android ownership, the likely calendar
range is about **5–7 weeks**, excluding Google brand review, Apple signing/notarization lead time, and
production change windows.

## 3. I0 — contract, threat model, and UX freeze

Goal: remove ambiguity before credentials or migrations exist.

Work items:

- `AM-0001` Freeze the account rules and terminology: Hermes GO account, Hermes identity, Desktop
  Connector, phone installation, and unique binding.
- `AM-0002` Define provider-neutral identity and account records; Google is only the V1 provider.
- `AM-0003` Write the HTTP/WSS contract for proof exchange, refresh, sign-out, installation
  registration, binding, replacement, revocation, and status.
- `AM-0004` Define token lifetimes, refresh rotation, replay handling, server-side hashes, machine
  credential/key-pair behavior, and clock-skew policy.
- `AM-0005` Threat-model account takeover, stolen phone refresh material, stolen Mac machine
  credential, malicious replacement, token leakage, and cross-account routing.
- `AM-0006` Register new `HR-AUTH-*`, `HR-ACCOUNT-*`, `HR-BIND-*`, and migration failures in
  `ERROR_HANDLING.md`, including Chinese/English copy and recovery action.
- `AM-0007` Freeze the Android information architecture: Settings contains the account; the existing
  Remote device stat opens the single Hermes path; no duplicate connection entry.
- `AM-0008` Define rollout capabilities and feature flags so clients can discover whether account
  mode, legacy mode, binding replacement, and installation management are available.
- `AM-0009` Produce contract fixtures and a test-impact matrix before implementation.

Deliverables:

- account/auth API specification;
- data model and migration proposal;
- threat model and credential lifecycle diagram;
- error-code additions;
- final two-client screen/state inventory;
- compatibility and rollback protocol.

Exit gate:

- a forged identity cannot be accepted by design;
- account replacement cannot be ambiguous or automatic;
- the old Connector remains the active path until an explicit later migration;
- every externally visible state has one owner, one recovery action, and one stable error family.

## 4. I1 — authentication foundation

Goal: establish identity and Hermes GO sessions without changing routing or Connector behavior.

Gateway/account service work:

- `AM-1001` Add `accounts`, `external_identities`, `account_sessions`, and refresh-rotation/reuse
  records with timestamps and revocation state.
- `AM-1002` Verify Google token signature, issuer, audience, expiry, and nonce where applicable;
  resolve accounts by verified provider subject rather than email.
- `AM-1003` Issue short-lived Hermes GO access tokens plus rotating refresh credentials; store only
  server-side hashes where a bearer secret must be persisted.
- `AM-1004` Implement refresh, current-account, current-session sign-out, and all-session revocation.
- `AM-1005` Add bounded JWKS caching, rate limits, request-size limits, structured audit events, and
  redaction.
- `AM-1006` Keep Google outages outside the steady-state Connector data path; an already authorized
  machine credential must not require interactive Google login for each reconnect.
- `AM-1007` Add server capability discovery and keep account routes disabled by default until their
  release gate passes.

Tests:

- valid Desktop and Android audiences;
- wrong issuer/audience, expired token, invalid signature, missing subject, and changed email;
- access expiry, refresh rotation, replayed refresh credential, sign-out, and revoke-all;
- parallel refresh race and database restart;
- redacted logs, metrics, traces, and error serialization.

Exit gate: a test client can sign in, refresh, fetch its account, and revoke itself; no account token
can access the existing Hermes tunnel yet.

## 5. I2 — binding and phone-installation control plane

Goal: represent the one Connector and many independently revocable phones safely.

Work items:

- `AM-2001` Add `connector_bindings`, Connector public keys/machine credentials,
  `phone_installations`, and installation sessions.
- `AM-2002` Enforce one active Connector per account with a database constraint/transaction, not UI
  checks.
- `AM-2009` Keep first binding pending until the Connector proves key possession and required health;
  activate it atomically only while the account is still unbound.
- `AM-2003` Implement get-binding, bind, request-replacement, confirm-replacement, list phones,
  revoke phone, and revoke this-phone operations.
- `AM-2004` Require recent reauthentication for Connector replacement and destructive account-wide
  revocation.
- `AM-2005` Make Gateway REST/WSS routing account-aware while preserving request ownership and
  preventing cross-account/cross-installation leakage.
- `AM-2006` Split lifecycle delivery cursor, notification acknowledgement, and device activity by
  phone installation. Keep purely local read/unread presentation device-local in V1.
- `AM-2007` Add account/binding audit events without prompt text, Hermes output, file paths, or
  credentials.
- `AM-2008` Retain legacy App/Connector Token authentication beside account authentication, with
  explicit mode/capability reporting.

Tests:

- two phones on one account share the bound Hermes route;
- revoking phone A does not affect phone B or Desktop;
- two concurrent Mac bind attempts produce exactly one winner and never silently replace;
- account A cannot list, route to, revoke, or observe account B;
- replacement confirmation is single-use and expires;
- delivery and notification cursors do not leak between phones;
- legacy REST, WebSocket, lifecycle inbox, files, and update paths remain green.

Exit gate: the backend model is authoritative and race-safe, while production traffic may still remain
entirely on legacy credentials.

## 6. I3 — Hermes Go Desktop account client

Goal: deliver the account-management experience before changing the running Connector.

Work items:

- `AM-3001` Add system-browser Google OAuth with PKCE S256, state, an ephemeral loopback callback,
  cancellation, timeout, and callback ownership validation. Reuse browser Google sessions through
  Google's account chooser so an already signed-in user can authorize without entering credentials;
  do not inspect browser/OS account stores directly.
- `AM-3002` Store the account management session and machine key material separately in Keychain;
  never store a Google access token as the Connector credential.
- `AM-3003` Replace Phone Pairing with Account & Devices in the sidebar; keep legacy pairing under a
  clearly labeled compatibility entry.
- `AM-3004` Implement signed-out, no-binding, healthy, reauth-needed, revoked, and second-Mac-conflict
  screens using the approved design contract.
- `AM-3005` Add phone listing, per-phone removal, unbind-this-Mac confirmation, and explicit
  verify-and-replace flow.
- `AM-3006` Keep Overview topology focused on Desktop Agent -> Gateway -> Hermes -> end-to-end;
  account authentication is a separate diagnostic layer.
- `AM-3007` Extend menu-bar status with account-recovery/binding-revoked states without exposing the
  full email unnecessarily.
- `AM-3008` Run the existing Connector/Hermes preflight read-only. At this iteration Desktop does not
  write Connector configuration, stop it, restart it, or start a second copy.
- `AM-3009` Update unit tests, snapshots/concepts, `DESKTOP_PHASE0.md`, `DESKTOP_DESIGN.md`, and
  `DESKTOP_TEST_PLAN.md`.

Tests:

- OAuth account chooser with zero/one/multiple browser sessions, success/cancel/state mismatch/
  timeout, explicit account switching, and browser callback collision;
- Keychain persistence, rotation, sign-out, redaction, and corrupt-entry recovery;
- no-binding, phone removal, revoked binding, and second-Mac conflict UI;
- app/window/menu-bar restart behavior;
- legacy Connector PID and launch count remain unchanged throughout I3;
- Desktop core tests, app build, asset gate, codesign verification, and accessibility checks.

Exit gate: Desktop can manage the account and proposed binding safely, but the existing production
Connector is still untouched.

## 7. I4 — Android account client and single remote-device entry

Goal: make same-account login the simple path while retaining the complete existing Hermes product.

Work items:

- `AM-4001` Add Credential Manager Sign in with Google and exchange the result for Hermes GO
  credentials. Try previously authorized accounts first, permit Google's one-credential auto-select,
  and fall back to the all-on-device account chooser when no authorized credential exists.
- `AM-4002` Add encrypted account/installation session storage, refresh rotation, sign-out-this-phone,
  and invalid-session recovery.
- `AM-4003` Replace default URL/Token/QR onboarding with Google sign-in; keep legacy setup available
  through compatibility navigation.
- `AM-4004` Implement startup states: signed out, account with no Desktop, binding offline, healthy,
  reauth needed, and phone revoked.
- `AM-4005` Keep the existing Hermes identity card unchanged. Make the existing Remote device stat
  clickable and show the Mac name plus Hermes connection summary.
- `AM-4006` Add one Remote device detail screen showing the single Mac, Connector, Hermes, Gateway,
  and end-to-end state, plus Diagnostics and Legacy connection. Do not add a device picker or a
  “Connection & devices” setting.
- `AM-4007` Put only the Google account and this-phone session management in Settings. Do not repeat
  the remote-device state there.
- `AM-4008` Keep chat, sessions, projects, models, cron, files, updates, and composer behavior
  unchanged apart from their authenticated transport.
- `AM-4009` Key notification registration, lifecycle cursor, and server delivery acknowledgement by
  installation; keep local read presentation local.
- `AM-4010` Update localized errors, TalkBack semantics, large-font/dark-mode screenshots,
  `DESIGN.md`, and Android tests.

Tests:

- Credential Manager with zero, one, and multiple authorized Google accounts; auto-select
  eligibility; all-account fallback; account switching; cancel and provider error;
- sign in after fresh install and migrate after an existing Token install;
- phone signs in before Desktop, then becomes connected without reinstalling;
- Remote device stat navigation and healthy/offline/Hermes-unreachable details;
- account appears in Settings but not on Sessions, chat, or card page during healthy operation;
- phone session expiry does not delete unrelated local preferences;
- phone A sign-out does not affect phone B;
- unit, navigation, screenshot, accessibility, process-death, and Android baseline build tests.

Exit gate: Android account mode is usable against the I2 backend, and legacy configuration still
works unmodified.

## 8. I5 — Connector machine credential and rollback migration

Goal: move the real Mac bridge to account-bound authentication without risking Hermes or the current
remote path.

Work items:

- `AM-5001` Add Connector challenge/registration using the Desktop-generated machine key or an
  independently rotatable device credential.
- `AM-5002` Authenticate Connector hello/reconnect with account binding while preserving the current
  outbound-only network shape.
- `AM-5003` Define the smallest authenticated local control boundary between Desktop and Connector
  for staged credential provisioning and status; never expose a public Mac listener.
- `AM-5004` Implement transactional migration: snapshot allowed legacy state, stage new credential,
  validate account binding, start exactly one candidate, verify Gateway and end-to-end health, commit,
  or restore the old state automatically.
- `AM-5005` Prevent parallel legacy/account Connector instances and detect stale launchd/process
  ownership before every transition.
- `AM-5006` Rotate/revoke the machine credential without touching the local Hermes credential.
- `AM-5007` Preserve all Hermes REST, WebSocket, files, lifecycle observation, and profile behavior.
- `AM-5008` Provide a documented manual rollback that restores the last known-good legacy path.

Tests:

- upgrade succeeds with no dropped long-lived Hermes credential;
- every failure point before commit restores the old Connector and authentication mode;
- kill/restart/power-loss during staging converges to exactly one known-good Connector;
- old machine is rejected after confirmed replacement; failed replacement leaves it active;
- account backend or Google outage does not force an interactive login for ordinary reconnect;
- Hermes files/config/source hashes or equivalent monitored boundaries remain unchanged;
- complete protocol, Gateway, Connector, smoke, Desktop, and Android baselines pass.

Exit gate: a real Mac can migrate and roll back repeatably, with evidence that Hermes was not changed.

## 9. I6 — hardening, physical tests, and staged release

Goal: prove the whole lifecycle before account mode becomes the default.

Required physical scenarios:

- `AM-6001` Install/upgrade Desktop on the target Mac mini without changing the legacy Connector
  before the migration step.
- `AM-6002` Sign in two physical phones with the same account; run sessions from each and verify
  independent sign-out, notification, and read behavior.
- `AM-6003` Exercise a second Mac login, cancelled replacement, failed replacement, and successful
  replacement.
- `AM-6004` Restart Android processes, both phones, Desktop, Connector, and Mac; test network loss,
  Gateway restart, account service outage, clock skew, and credential rotation.
- `AM-6005` Verify distinct account/Gateway/Connector/Hermes/end-to-end diagnostics and bilingual
  recovery actions.
- `AM-6006` Audit logs, diagnostics, crashes, metrics, database rows, and support exports for secrets
  and unnecessary personal data.
- `AM-6007` Run migration rollback after successful account use and then migrate forward again.
- `AM-6008` Build the versioned Android APK through `package-debug-apk.sh`; build/verify the Desktop
  app and DMG. Developer ID distribution additionally requires notarization/stapling gates.
- `AM-6009` Update release notes, deployment/smoke docs, version/capability matrix, and operator
  rollback instructions.

Release sequence:

1. Deploy account schema/routes disabled; run migrations and backup/restore validation.
2. Enable account authentication only for explicit test accounts.
3. Ship opt-in Desktop and Android account mode while legacy remains the default/available path.
4. Complete Mac mini + two-phone beta and observe authentication/binding errors and rollback metrics.
5. Make account mode the default onboarding path; keep legacy recovery visible to authorized testers.
6. Remove legacy UI only after an explicit later review. Remove legacy server acceptance in a
   separate release after telemetry, support, and rollback criteria are met.

Exit gate: signed artifacts and physical evidence pass, rollback is rehearsed, operator docs are
current, and a separate production authorization is recorded.

## 10. Cross-cutting backlog and ownership

### Protocol and data

- version auth/binding messages independently from the Hermes compatibility facade;
- make database migrations forward-compatible and backup before destructive schema changes;
- define idempotency keys for bind, replace, revoke, and refresh operations;
- retain minimal audit data with explicit retention and cleanup;
- never store Google email as the authorization key.

### Security and privacy

- least-privilege Google scopes (`openid`, basic profile, email only if needed for display);
- platform-specific OAuth clients and exact audiences;
- CSRF/state/PKCE/replay defenses and bounded callback lifetime;
- encrypted transport and protected at-rest secrets;
- recent reauthentication for binding replacement and account-wide revoke;
- no prompt, response, tool output, local path, Hermes credential, or file contents in account audit
  events.

### Operations

- database health, migration status, auth failure rate, refresh-reuse alerts, active Connector count,
  binding conflicts, and rollback events;
- key rotation and emergency revoke procedure;
- backup/restore drill before production binding data is authoritative;
- feature flags and capability discovery with fail-closed defaults;
- preserve existing HK edge routes, DERP, Xray, and release paths.

### Documentation and tests

- update design, architecture, deployment, smoke, error, signing, and component docs in the same
  iteration as behavior changes;
- add contract fixtures shared by Gateway, Connector, Desktop, and Android;
- keep focused tests during development and run each component baseline before handoff;
- label anything checked only in a mock/emulator and keep physical verification explicitly pending.

## 11. External prerequisites

Development through mocked identity and contract tests can begin before all prerequisites are ready,
but real end-to-end Google sign-in is blocked until these exist:

- Google Cloud project and OAuth consent/brand configuration;
- separate Desktop and Android OAuth clients;
- final Android application ID and signing certificate fingerprint for the Android client;
- approved macOS bundle identifier and redirect/callback configuration;
- public privacy-policy/support URLs and verified domains required by the consent configuration;
- a protected account database in the HK environment with backup/restore and secret rotation;
- Developer ID/notarization credentials only when a distributable macOS release is requested.

No prerequisite requires a Hermes source change.

## 12. Go/no-go checkpoints

- **G0 — start implementation:** I0 contracts, threat model, UX, and compatibility rules approved.
- **G1 — client alpha:** I1–I2 security and isolation tests pass; Desktop/Android may integrate in dev.
- **G2 — migration beta:** I3–I4 pass and legacy remains green; I5 may touch a test Connector.
- **G3 — target-Mac beta:** I5 rollback passes locally; explicit authorization is given for the Mac
  mini test.
- **G4 — production default:** I6 physical/security/release evidence passes; explicit deployment
  authorization is given.
- **G5 — legacy retirement:** a later, separate decision with adoption evidence and a rehearsed
  rollback. It is not part of the first account-mode launch.
