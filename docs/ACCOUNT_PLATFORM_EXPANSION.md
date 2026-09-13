# Hermes GO account-platform expansion

Status: accepted expansion contract with an email-first release sequence. The local source
implementation spans E0-E9 across Gateway, Web, Desktop, and Android. The next execution sequence is
now explicit: E10 stabilizes the Desktop restart-health fix, E11 admits multiple owned Mac/Hermes
terminals, E12 admits whole-terminal sharing from an owner account B to a grantee account A, and E13
closes the combined physical and distribution gates. Live mail-domain delivery, privacy approval,
packaged/physical accessibility, signed releases, and production rollout remain independent gates.
This document extends the completed local I0-I3A
account-mode baseline. It does not enable account mode, authorize production deployment, or change
any production service. The current Android source now includes the default-off E8 account client
and E9 deletion adoption; no APK was versioned or published.

Baseline contracts remain in `ACCOUNT_MODE_DESIGN.md`, `ACCOUNT_MODE_API.md`,
`ACCOUNT_MODE_SECURITY.md`, `ACCOUNT_MODE_MIGRATION.md`, and `ACCOUNT_MODE_TEST_PLAN.md`. Where this
document differs, it supersedes only the future product limits for identity providers, active Mac
bindings, device selection, sharing, and Desktop installation. Existing released or tested endpoints
remain compatibility contracts until an explicit migration removes them.

Current E1 evidence (2026-09-07): schema version 8 permits provider-neutral email OTP identities and
adds a plaintext-free challenge store; the Gateway contains conservative mailbox normalization,
six-digit code generation, context-bound keyed hashes, a Resend HTTPS adapter, a PostgreSQL challenge
state machine, default-off capability/configuration, and challenge/exchange HTTP routes. No live API
key or verified sending domain is configured and the feature remains off in every environment.

E2 foundation evidence (2026-09-07): schema version 9 adds the `account.identity.link` recent-auth scope;
the Gateway lists safe identity metadata and supports explicit Google/email linking with fresh target
proof, an independently verified existing identity, single-use scoped grants, protected idempotent
replay, and serialized cross-account conflict handling. The later E6 slice supersedes the original
scriptless `/account` shell with default-off Cookie sessions, CSRF enforcement, email and Google
sign-in, provider-neutral identity/device/session management, and the redacted account-security view.
Live-provider acceptance and physical browser/accessibility acceptance remain open.

Final-delivery evidence (2026-09-08): schema 15 stores only PII-minimal Resend receipt metadata,
deduplicates signed callbacks by `svix-id`, rejects reordered stale state, and correlates with both
provider message ID and opaque Hermes tags. Hard failures invalidate unused OTPs and pending share
invitations. The endpoint and configuration remain default-off; verified-domain live delivery is
still a staging gate.

## 1. Product decisions

The first account release uses one authentication path. Google and Apple are deferred providers:

```text
email one-time code
        |
        v
      Hermes GO account
       /             \
owned Macs (0..3)   shared Macs (0..10)
       |                  |
       +---- authorized client installations ----+

future: Google / Apple identity --explicit link--> same Hermes GO account
```

Fixed rules:

- A Hermes GO account is the stable internal `account_id`; email addresses and Google subjects are
  login identities, never resource ownership keys.
- A person signs in with a six-digit email one-time code. There is no reusable password in this
  release.
- Google and Apple sign-in are not first-release dependencies or acceptance gates. The provider-
  neutral account/identity model remains so either can be added later through explicit linking.
- An account may own at most three active Mac/Hermes bindings.
- An account may accept access to at most ten active shared Mac bindings.
- One Mac has exactly one owner and may be shared with at most five other accounts.
- A grantee signs in with their own account. They may use the shared Hermes but cannot transfer it,
  re-share it, unbind it, rotate its Connector credential, or manage the owner's installations.
- Same-account clients discover authorized Macs automatically. One available Mac is selected
  automatically; with more than one, the client restores its last valid selection and otherwise asks.
- Every conversation/task is permanently associated with the Mac on which it was created. Switching
  the client's active Mac does not move or merge existing conversations.
- The current App Token and Connector Token flow stays available during the compatibility window.
- Android continues on its current iteration independently. The expansion is additive and
  capability-gated; Android adopts it only after the cloud contract is stable.

Limits are server configuration with these release defaults, but raising them is a later product and
capacity decision. The database and authorization model must not assume the numbers are unlimited.

## 2. Identity and account linking

### 2.1 Canonical identity

All ownership and authorization uses an opaque internal account ID. External identities are unique
triples `(provider, issuer, subject)` linked to that account. The first release uses a verified
normalized mailbox identity. A later Google or Apple provider uses its verified issuer and subject.

The display email in a future provider token is profile data only. Equal email text must never
silently merge accounts or grant access.

### 2.2 Safe linking rules

The email-only release supports adding/removing verified email identities. The Google rules below
are retained as the future-provider contract and are not exposed while Google authentication is off.

- A signed-in Google user may add an email-login identity only after completing the email OTP and a
  recent-authentication check.
- A signed-in email user may link Google only after completing Google verification and a
  recent-authentication check.
- If either identity already belongs to another account, linking fails without moving or merging
  either account. Recovery requires signing into both accounts and a future explicit merge flow.
- An unauthenticated email OTP may sign into an existing linked email identity. If no linked identity
  exists, it may create a new account after successful verification.
- If that mailbox merely matches profile email stored on a Google-only account, it does not join that
  account. The user must sign in with Google first and link the email identity from account settings.
- Removing the last usable login identity is forbidden. Destructive unlinking requires recent
  authentication and revokes sessions created through the removed identity where practical.

This avoids both account takeover through recycled mailboxes and ambiguous automatic merging.

### 2.3 Email OTP policy

The first release uses a professional transactional-email provider. Hermes GO does not operate a
complete mail server. The sending domain must publish SPF and DKIM, with DMARC initially monitored
and later tightened after delivery evidence is reviewed.

OTP contract:

- six decimal digits generated with a cryptographically secure random source;
- expires ten minutes after issue;
- single-use and invalidated after successful verification;
- newest challenge invalidates earlier unconsumed challenges for the same purpose and mailbox;
- resend cooldown of 60 seconds;
- maximum five verification attempts per challenge;
- per-mailbox, per-IP, and per-installation rolling limits plus a provider-wide circuit breaker;
- store only a keyed hash of the code, never the plaintext code;
- log challenge IDs and outcomes, never the code or full mailbox;
- return the same public response whether an account exists or not;
- the email states the purpose, expiry, requesting client, and how to ignore an unexpected request.

Email addresses are normalized conservatively: Unicode/domain canonicalization and ASCII case
normalization are allowed, but provider-specific transformations such as removing dots or plus tags
are not.

## 3. Device ownership, discovery, and routing

### 3.1 Binding model

An active Connector binding represents one Mac-side Hermes service. It has one owner account, one
Desktop installation, one machine public key, and one independently revocable machine credential.
`device_id` remains globally unique.

The current database constraint that permits one active binding per account is replaced by an atomic
maximum-three check. Concurrent fourth-device attempts must produce exactly one stable capacity
failure and must not replace or revoke an existing Mac.

The existing singular `/v2/connector-binding` API remains a compatibility view for clients that only
understand one Mac. New clients use plural device resources and explicit opaque device IDs. The
singular view is available only when the result is unambiguous; otherwise it returns a capability or
selection error rather than choosing silently.

### 3.2 Client selection

Each client installation keeps a `last_selected_device_id`. Selection follows this order:

1. If exactly one accessible Mac exists, select it.
2. If the remembered Mac is still accessible, restore it.
3. If an account-level default is configured and accessible, use it.
4. Otherwise show the device picker before opening server-backed content.

The client sends the selected opaque device ID on every device-scoped REST request and WebSocket
connection. The Gateway resolves access from authenticated account plus active ownership/share; it
never trusts a display name, email, or a client-supplied owner ID.

A conversation/task stores its originating `device_id`. Opening it always routes to that device. A
device switch affects only new navigation and newly created conversations. Cross-device history
aggregation or migration is out of scope.

### 3.3 Device states

Clients distinguish at least:

- online and Hermes reachable;
- Connector online but Hermes unreachable;
- Connector offline;
- access revoked;
- device removed/unbound;
- device selection required.

Removing a device revokes only its machine credential and grants. It must not delete, stop, upgrade,
or edit local Hermes data.

## 4. Sharing and authorization

### 4.1 Grant model

A device access grant relates one active binding to one grantee account. V1 exposes one grantee role,
`operator`, with permission to use the Hermes APIs available through that binding. Ownership remains
separate and non-transferable.

Owner permissions:

- view, rename, diagnose, unbind, and rotate their device;
- invite and revoke grantees;
- see grant metadata and recent access audit events.

Grantee permissions:

- list the shared device in their own device picker;
- open REST and WebSocket sessions to its Hermes route;
- remove the shared device from their own account by leaving the grant.

Grantees cannot view or mutate Connector secrets, owner installations, other grantees, binding
ownership, binding replacement, or the owner's account settings.

### 4.2 Invitation flow

1. The owner selects a device, enters the intended mailbox, acknowledges the whole-device access
   warning, and completes recent authentication.
2. The service creates a single-use, expiring invitation token and sends it through the transactional
   email provider. The response never reveals whether the mailbox already has an account.
3. The recipient signs into their own account and accepts the invitation. The account must contain a
   verified email identity matching the invitation mailbox.
4. Acceptance atomically checks device ownership, invitation state, duplicate grants, the five-share
   device limit, and the recipient's ten-shared-device limit.
5. The recipient sees the device after acceptance; the owner sees the active grantee.

Invitations expire after 72 hours by default, are single-use, store only a token hash, and may be
cancelled by the owner. An invitation does not reserve permanent capacity; limits are checked again
when accepted.

### 4.3 Revocation and privacy boundary

Revocation takes effect at the Gateway authorization boundary. It blocks new requests, closes active
grantee WebSockets, and invalidates device-scoped tickets. Cached client metadata is marked revoked
and cannot be used for offline authorization.

The first sharing release is explicitly **whole-device sharing**. Because the Gateway tunnels Hermes
APIs and Hermes does not yet enforce Hermes GO per-project ACLs, a grantee may see existing sessions,
files, model configuration metadata, or other data exposed by that Hermes instance. The invitation
screen must state this before confirmation.

Profile-, project-, folder-, conversation-, and tool-level sharing must not be presented as secure
until Hermes or the Connector enforces those scopes on every relevant REST, WebSocket, file, and
background-event path. They are a separate future milestone.

Account-wide lifecycle inbox delivery remains owner-only initially. Shared-user background
notifications require an initiator-aware event contract and are not inferred from globally observed
device events.

### 4.4 Authorization predicate

For every device-scoped operation:

```text
allow = active binding
    AND authenticated account
    AND (account is owner OR account has active operator grant)
    AND operation is allowed for that relationship
```

Cross-account IDs use a uniform not-found response where disclosure is unnecessary. Authorization is
re-evaluated for every REST request and before WebSocket upgrade; long-lived sockets are terminated
when relevant ownership, grant, session, or binding state changes.

## 5. Web account center

The Web account center is delivered before Android adopts the expansion. It manages account-level
state only and is not a web replacement for the Hermes client.

Initial scope:

- sign in with email OTP;
- view and link login identities;
- list and revoke account sessions/installations;
- list owned and shared Macs with health summaries;
- set a default Mac;
- create, cancel, accept, leave, and revoke shares;
- show account and device security/audit events without prompt or output content.

Web sessions use Secure, HttpOnly, SameSite cookies, CSRF protection for mutations, short access
lifetimes, rotating refresh credentials, and the same recent-authentication rules as native clients.
No Hermes credential, provider API key, prompt, model output, or local file path is stored in the Web
application.

## 6. Desktop clean-machine bootstrap

On a new Mac, installing Hermes Go Desktop and signing in should be sufficient to reach a guided
setup. Every machine-changing step is visible and confirmed.

Flow:

1. Inspect for an existing Hermes installation, Connector, process, configuration, and launch agent
   without modifying them.
2. Explain the exact proposed operations and request confirmation.
3. Install or upgrade Hermes Server from a signed, versioned manifest; verify signature and checksum
   before activation.
4. Ask the user to configure the local model/provider. Provider credentials stay in local protected
   storage and never pass through the account service.
5. Install one Connector, generate its machine key locally, bind this Mac to the signed-in account,
   and complete proof plus health activation.
6. Configure user-level automatic startup where possible and run end-to-end diagnostics.
7. On any failure, preserve the previous working installation and present a stable error code plus
   recovery action.

The bootstrap must detect and reuse or explicitly migrate an existing installation; it must never
start duplicate Connectors. Installations are staged atomically and roll back on failed health checks.
Uninstall preserves Hermes data and configuration unless the user separately and explicitly elects
to remove them. Distribution requires Apple signing/notarization and clean-machine test evidence.

## 7. Additive API direction

Exact request/response schemas are frozen in the iteration that implements them. The public shape is
additive to the current `/v2` API:

```text
POST   /v2/auth/email/challenges
POST   /v2/auth/email/exchange
POST   /v2/auth/reauth/email/challenges
POST   /v2/auth/reauth/email
GET    /v2/account/identities
POST   /v2/account/identities/email/challenges
POST   /v2/account/identities/email
DELETE /v2/account/identities/:identityId

GET    /v2/devices
GET    /v2/devices/:deviceId
POST   /v2/devices/:deviceId/select-default
DELETE /v2/devices/:deviceId

GET    /v2/devices/:deviceId/shares
POST   /v2/devices/:deviceId/share-invitations
DELETE /v2/devices/:deviceId/share-invitations/:invitationId
DELETE /v2/devices/:deviceId/shares/:grantId
POST   /v2/share-invitations/:token/accept
POST   /v2/devices/:deviceId/leave
```

The already implemented Google exchange/link/reauthentication routes remain compatibility code but
are absent unless the independent `ACCOUNT_GOOGLE_AUTH_ENABLED=1` rollout flag is set. The email-
first release keeps that flag at `0`, requires no Google client IDs, advertises only `email_otp`, and
does not show Google controls in the Web account center.

Device-scoped Hermes facade routes carry `deviceId` in a versioned path or authenticated session
ticket. They do not accept owner account IDs. Mutations use existing idempotency conventions.

`GET /v2/capabilities` will advertise provider availability and numeric limits, including:

```json
{
  "account": {
    "providers": ["email_otp"],
    "maxOwnedDevices": 3,
    "maxSharedDevices": 10,
    "maxGranteesPerDevice": 5,
    "supportsDeviceSelection": true,
    "supportsDeviceSharing": true
  }
}
```

Each field is emitted only when the corresponding server feature is enabled. Existing clients keep
seeing a one-binding-compatible capability until they are ready for explicit device selection.

## 8. Data-model delta

The implementation is expected to add or evolve these records:

- extend external identity provider support with an email OTP identity representation;
- `email_otp_challenges`: purpose, normalized-mailbox lookup, keyed code hash, expiry, attempts,
  consumed state, requester risk metadata, and audit references;
- remove the one-active-binding-per-account partial unique index and enforce configurable ownership
  capacity transactionally under account locking;
- `account_device_preferences`: per-account default device, with client-local last selection remaining
  per installation;
- `device_share_invitations`: owner, binding, target-mailbox lookup, token hash, expiry, state;
- `device_access_grants`: binding, owner, grantee, role, active/revoked state and timestamps;
- authorization-generation or equivalent invalidation state to close active sessions after revoke.

Database invariants include unique active `(binding_id, grantee_account_id)`, no self-grant, one owner
per binding, immutable owner on an active grant, and transactional capacity checks. Migration is
forward-only, repeatable in a disposable PostgreSQL environment, and safe while account features are
disabled.

## 9. Security and operations gates

Before enabling any expansion capability:

- complete provider-domain SPF, DKIM, and DMARC verification and delivery monitoring;
- add generic-response, enumeration, brute-force, replay, clock-skew, and concurrency tests for OTP;
- verify identity conflicts never merge or move ownership;
- prove fourth-owner-device, sixth-grantee, and eleventh-shared-device races have one deterministic
  outcome without partial writes;
- test cross-account REST, WebSocket, file, lifecycle, invitation, and object-ID isolation;
- prove grant revocation closes active sockets and prevents ticket reuse;
- preserve redaction for mailbox, invite tokens, OTPs, sessions, machine credentials, local paths,
  prompts, and outputs;
- keep new flags default-off and demonstrate legacy traffic before and after migrations;
- run Desktop install/upgrade/rollback tests on a clean supported macOS machine;
- run staging soak and rollback before any separately authorized production rollout.

The provider gate is now reproducible with `scripts/verify-email-staging.mjs`: read-only preflight
checks Gateway readiness, the email-only provider contract, and protected aggregate metrics; an
exactly confirmed staging exercise uses only Resend's official delivered/bounced test recipients and
requires an isolated one-message aggregate delta. The tool never exposes internal metrics publicly,
prints no mailbox or challenge identifier, and performs no deployment. Actually running both cases,
domain-level inbox acceptance, soak, and rollback remain operator staging work.

Public mail DNS is covered by the separate read-only `scripts/verify-email-domain.mjs` gate. Its
strict staging configuration carries public DNS expectations only, checks SPF TXT plus Return-Path
MX, rejoins split DKIM TXT chunks, and validates DMARC policy/reporting without echoing record values.
This closes the repeatability gap in the DNS review, but it does not configure the domain or replace
Resend's own verification and real delivered-header inspection.

Account retention is now active and bounded rather than dependent only on new mail traffic. A
process-local scheduler starts after 60 seconds and repeats every six hours, deleting at most 1,000
rows per eligible table under one transaction. A saturated table budget or any account-deletion
progress schedules another bounded sweep after one second, preventing the one-account-per-six-hour
backlog while returning to the ordinary interval as soon as catch-up drains. It removes expired
idempotency responses, then 35-day-old Webhook receipts, OTP challenges, share invitations,
connector-replacement requests, and now-unreferenced reauthentication grants in foreign-key order.
Expired refresh hashes remain for a
further 35 days, after which the sweeper detaches only historical parent links; a session tombstone
is deleted only when no refresh, idempotency, or reauthentication row remains. PostgreSQL
`SKIP LOCKED` makes multiple Gateway instances safe; a pending candidate is revoked before its stale
replacement request is removed. The private aggregate snapshot exposes timestamps and per-class
totals only. Sanitized lifecycle events now use the configured 30-day default plus the existing
10,000-row/account cap, and allowlisted audit events use the configured 180-day default from the
Cloud product requirements. Installation and device-access-history retention remain separate review
gates.

The account-lifecycle slice now implements permanent Cloud-account deletion behind the independent
default-off `ACCOUNT_DELETION_ENABLED` gate. Native and secure Web APIs require recent
`account.delete` verification, a stable idempotency key, and explicit permanent-deletion
acknowledgement. Web and Desktop require typed `DELETE`; Desktop additionally persists only the
grant/key needed to retry an ambiguous response. Access, installations, owned bindings, and sharing
relationships are revoked immediately. Invitations addressed to the deleting account are cancelled
and suppressed by a temporary keyed email fingerprint until dependency-safe cleanup at the fixed
30-day deadline removes those invitations and their cross-account email hints. A purpose-separated
OTP fingerprint invalidates unused codes and makes later requests return the neutral challenge
contract without provider delivery; its correlation rows are removed by the same deletion lane. The
final state leaves a non-PII tombstone audit plus an account-free keyed completion receipt for
long-offline retry recovery. Local Mac Hermes data is never touched. Server, Web, Desktop, and the
default-off Android client are automated locally; privacy approval, physical-client acceptance,
live staging, signed releases, and every production enablement remain separate gates.

All new user-visible failures must be registered in `ERROR_HANDLING.md` before runtime code uses them.

## 10. Iteration plan

| Iteration | Scope | Exit gate |
| --- | --- | --- |
| E0 | Expansion contract and test-impact map | Identity, device, sharing, privacy, compatibility, and ownership decisions are frozen |
| E1 | Email OTP service foundation | Provider-neutral sessions, secure challenge lifecycle, adapter contract, limits, and disposable-PostgreSQL tests pass; feature off |
| E2 | Identity linking and Web account shell | Local linking/conflict gate complete; interactive cookie session, CSRF, unlinking, and live-provider gates remain |
| E3 | Multi-device control and routing | Three owned Macs, explicit selection, chat affinity, race safety, and legacy one-binding compatibility pass |
| E4 | Desktop multi-device UX and bootstrap | Existing-install detection, signed install, rollback, local-secret storage, binding, and auto-start pass on clean macOS |
| E5 | Whole-device sharing | Invite/accept/revoke/leave, 5/10 limits, permissions, disclosure warning, and live socket revocation pass |
| E6 | Email-first provider decoupling and hardening | Google is independently off; email-only Web configuration, routes, CSP, delivery metrics, and security tests pass |
| E7 | Desktop email-code adoption and staged migration | Desktop can request/exchange email codes and use email reauthentication without any Google client configuration |
| E8 | Android adoption | Android selects owned/shared devices and preserves existing functionality; local JVM/debug baseline passes and the separate APK release remains gated |
| E9 | Account lifecycle and deletion | Default-off Server/Web/Desktop/Android deletion state machine and privacy cleanup pass locally; privacy, physical, release, and production gates remain separate |
| E10 | Desktop restart-health stabilization | Desktop 0.2.10/build 13 carries the post-restart Cloud-health freshness proof, passes the package gate, and preserves managed release 0.3.4; versioning, installation, and publication remain separate gates |
| E11 | Multiple owned terminals | A separately reversible production canary admits up to three owned Mac/Hermes terminals, preserves the first terminal and Legacy traffic, and proves selection plus conversation affinity before broadening access |
| E12 | Account B shares a terminal with account A | Identity/Web prerequisites and whole-device sharing advance through separate sub-gates; B invites A, A accepts and uses the terminal as an operator, and revoke/leave remove only A's access |
| E13 | Combined physical and distribution acceptance | Two accounts, two Macs, two phones, restart/outage/revocation and rollback matrices pass; Desktop Developer ID signing, notarization, stapling, and clean-Mac launch pass before public distribution |
| Future P1 | Google and Apple providers | Explicit provider-linking design, platform credentials/review, live-provider tests, and separate rollout gate pass |

### 10.1 Post-E9 execution order

E10 is the immediate release-stabilization iteration. It allocates Desktop 0.2.10/build 13 only after
the version gate is confirmed, keeps the already published managed component at 0.3.4, rebuilds from
a clean `origin/main`, and repeats the Desktop asset, test, app-build, package-integrity, and clean
diff checks. Installing the resulting internal build is a separate publish/deployment decision. The
target-Mac acceptance must show that an ordinary app upgrade does not restart managed services and
that any separately initiated Connector restart produces a newer Cloud `endToEnd.checkedAt` for the
same binding/generation.

E11 turns the implemented E3/E7/E8 multi-device source into an independently operable production
capability. Its operator path enables only the plural device/discovery and explicit device-routing
surface, records the exact prior environment and Nginx bytes, and keeps sharing, Google, deletion,
and unrelated account capabilities unchanged. Before a second binding commits, rollback must restore
the exact single-terminal capability and routes. After a second owned binding commits, disabling the
flag would strand valid state; recovery must instead move forward or explicitly remove the canary
binding through the normal owner-authorized unbind flow before restoring single-terminal mode.

E11 acceptance uses one owner account and at least two Macs. The existing Mac remains reachable while
the second is added; each Connector has a distinct binding, generation, machine key, and `deviceId`.
Desktop and Android list both without display-name authorization, selection changes only new
navigation, an existing conversation stays pinned to its originating terminal, and concurrent
fourth-device attempts admit exactly three owned terminals overall. Restarting either Mac, Connector,
Desktop, or phone must not switch or revoke the other terminal.

E12 advances in two separately reversible sub-gates. E12-A enables the identity-management and
recipient acceptance surface required by sharing, with the verified email provider, HTTPS account
center origin, Cookie/CSRF boundary, and delivery monitoring all proven. E12-B then enables
`ACCOUNT_DEVICE_SHARING_ENABLED` without changing owned-terminal limits or enabling Google. Account
B selects an owned terminal, completes fresh `device.share` email verification, acknowledges the
whole-Hermes disclosure, and invites account A. A signs into its own account, accepts the exact
72-hour invitation, sees the terminal as `operator`, and can select it for REST, WebSocket, normal
prompt, `/model`, `/compact`, and file traffic.

The E12 authorization gate proves that A cannot re-share, bind, replace, unbind, rotate, rename, or
manage B's account or other grantees. B's revoke and A's leave each clear only the matching grant,
reject new access immediately, and close A's active WebSocket within five seconds while B and all
other authorized clients continue. A pre-grant rollback cancels the canary invitation before
disabling sharing. Once a grant exists, rollback first revokes that canary grant and verifies socket
closure; the operator must never hide the capability while leaving an unaccounted active grant.
Capacity acceptance keeps the existing limits of three owned terminals per account, five grantees per
terminal, and ten accepted shared terminals per account.

E13 repeats the E11/E12 routes across two physical phones and two physical Macs, then restarts each
client, each Connector, and the Mac hosts, injects Gateway/provider/network outages, and exercises
explicit rollback. The release record must name every device and preserve redacted evidence for
binding/generation, `connectedAt`, `endToEnd.checkedAt`, revocation latency, artifact identity, and
Legacy compatibility. Public Desktop distribution remains blocked until the exact artifact also
passes Developer ID signing, notarization, stapling, and clean-Mac Gatekeeper launch.

### 10.2 Unattended development window

When the product owner cannot reach the Mac mini or physical phones, development may continue through
reversible repository gates: implementation, local fault-injection tests, documentation, pull requests,
manual inspection of every reported check, and merge after all checks complete successfully. Work in this
window must stop before Desktop version allocation, artifact installation, physical-device acceptance,
Developer ID signing/notarization, production artifact upload, remote service restart, or any account flag
change. Those actions remain explicit version, physical, signing, or production gates.

The preferred order is R5-F4 code gate first, then F5-A identity/Web acceptance prerequisites, then F5-B
sharing. E10 release notes and acceptance commands may be prepared in parallel, but Desktop 0.2.10/build 13
is not allocated until the version gate is confirmed. The handback record must leave the owner a short list
containing only the exact version decision, Mac/package actions, named-device evidence, and separately
authorized production commands that could not run unattended.

Local E3 backend status (2026-09-07): schema 10, the atomic three-owned-device limit, plural
discovery/detail/default selection, explicit device-scoped REST/WebSocket routing, singular-route
ambiguity protection, and disposable-PostgreSQL/network tests are implemented behind
`ACCOUNT_MULTI_DEVICE_ENABLED=0`. Client-side remembered selection and conversation persistence are
deliberately left for the Desktop/Android adoption iterations; no production flag was changed.

Local E4-B Desktop status (2026-09-07): capability-gated owned-Mac discovery, Desktop-local remembered
selection, cloud default selection with crash-safe idempotency, and a read-only clean/existing-install
Bootstrap plan are implemented. The default-off core also implements signed manifest acquisition,
checksum/archive validation, atomic install/rollback, account-mode Connector v2 proof, first-binding
activation, exact-label user LaunchAgent control, and interrupted-run recovery. The UI remains at the
signed-release gate. Production signing keys/artifacts, Developer ID notarization, packaged-UI
enablement, and physical clean-Mac evidence remain E4-C gates; no real process or installation,
Android source, or production flag was changed.

Local E4-C contract status (2026-09-08): Desktop now validates the complete packaged manifest origin,
channel, architecture, pinned Ed25519 key, and the official `hermes-serve-v1` loopback launch/readiness
contract. Gateway exposes the matching contract only behind a separate default-off flag. An absent,
invalid, or mismatched side keeps the setup card read-only; this slice performs no download,
LaunchAgent write, process change, or binding mutation.

Local E4-D orchestration status (2026-09-08): the packaged source now connects the clean-Mac card to a
two-stage executor. Preparation downloads, verifies, and safely extracts only; an exact signed-version
sheet and a second fresh preflight precede every machine-changing commit. LaunchAgent paths are derived
from the verified manifest, an existing responder on port 9119 blocks install, cleanup failures retain
a cleanup-only retry, and restart inspection/recovery blocks duplicate installation. The default plist
and Gateway capability remain off, no production key/artifact is configured, and no real Mac service,
Android source, or production environment was changed. Physical E4 acceptance is still pending.

Local E5 status (2026-09-07): schema 11, hashed/masked 72-hour invitations, whole-device disclosure,
owner/operator authorization, atomic five-grantee and ten-shared-device limits, invite/accept/cancel/
revoke/leave APIs, shared-device routing, and live WebSocket revocation are implemented behind
`ACCOUNT_DEVICE_SHARING_ENABLED=0`. Desktop can list owned/shared Macs, reauthenticate and invite,
cancel/revoke/leave, and accept a pasted invite link with crash-safe Keychain replay material. The Web
shell documents the handoff and remains non-interactive; the Secure HttpOnly Cookie/CSRF foundation
is implemented separately below, but the management UI gate is still open. Live provider delivery,
packaged UI inspection, two-account physical acceptance, production secrets/flags, and
deployment remain E6 gates. No Android source, real Mac service, or production environment changed.

Local E6 Web-session and account-center status (2026-09-07): schema 12 adds the exact `browser/web`
installation pair, and schema 13 records the source identity for new sessions plus safe identity
unlinking; schema 14 adds the dedicated installation-revocation grant scope. A separate default-off flag provides email/Google Web exchange, refresh, account
read, and sign-out using Secure HttpOnly SameSite=Strict host-only cookies. Every mutation requires
an exact HTTPS Origin, same-origin Fetch Metadata when present, and a matching host-only CSRF
cookie/header. Account bearers are absent from JSON and browser installations cannot inherit Desktop
binding authority. The same-origin interactive slice now provides email-code sign-in, email identity
link/unlink, identities,
owned/shared devices, default selection, provider-neutral recent reauthentication, create/list/cancel/revoke/
accept/leave whole-device sharing, account-installation/session revocation, and metadata-free security
audit views. PostgreSQL transaction-scoped account/session/installation/binding notifications now
propagate committed revocations across Gateway instances, with five-second database revalidation as
a missed-notification fallback. The login page now renders the official Google Identity Services
button only after unauthenticated bootstrap, sends its short-lived ID token directly to the existing
Web exchange with a browser nonce, and never stores that proof. Google and email identities can be
linked after re-verifying an already-attached identity through either provider. Physical
accessibility/browser acceptance, real-provider tests, and staging remain open E6 work.

Email-first decision (2026-09-07): Google is now an independent default-off provider. Account/Web
sessions no longer require Android, macOS, or Web Google client IDs when that provider is off;
capability discovery advertises only `email_otp`, Google HTTP routes are absent, the Web page hides
Google entry points, and its CSP has no Google origins. Existing Google implementation and isolation
tests remain in source for a later provider milestone. `scripts/test/account-platform-docs.test.mjs`
guards the current architecture/design/product outcome against accidentally restoring Google-first or
one-account/one-Mac wording while allowing clearly labelled historical implementation records.

Local E7 Desktop status (2026-09-08): Account & Devices now exposes email plus six-digit code as its
only signed-out action, binds challenge/exchange to the stable macOS installation, validates the
returned Desktop session, and uses owner-email reauthentication for scoped `device.share` and
`account.installation.revoke` grants. Removing another phone is capability-gated, rejects non-phone
targets, and preserves the single-use grant plus exact mutation key across an ambiguous response;
the old unverified server-side phone-removal entry point has been removed. Codes remain only in UI
memory. Transport/controller/error and PostgreSQL transaction tests pass locally. Live transactional
delivery, packaged UI/accessibility inspection, physical multi-device acceptance, production flags,
and deployment remain open gates.

Local E8 Android status (2026-09-08): default onboarding and Settings now expose the capability-
gated email-code account flow, with a stable random phone installation ID, encrypted session and
lost-response idempotency state, refresh rotation, this-phone revocation, owned/shared device
discovery, explicit selection, and legacy setup fallback. The email challenge screen derives its
expiry and resend cooldown from server timestamps, refuses
early resend and locally known-expired verification, replaces timing after resend, and restores only
a still-valid encrypted challenge after process death without persisting the six-digit input.
Retryable account errors repeat the originating send, exchange, refresh, or Mac-selection operation
instead of reducing every recovery action to a page refresh.
Account-mode Hermes REST and WebSocket requests use explicit opaque-device paths plus a bearer-only
client that cannot inherit legacy cookies, authenticators, or the App Token. The Relay-owned
lifecycle inbox is deliberately different: it uses the phone Bearer on the direct account endpoint,
works without a selected Mac, and keeps a hashed local cursor per Gateway/account/phone installation;
an account switch during synchronization cannot consume, acknowledge, or advance another cursor.
Startup preserves the structured account cause: session-family invalidation alone requests sign-in,
while refresh throttling, disabled accounts, temporary account service failure, and an offline
Connector retain their own registered recovery codes and actions.
Unexpected account-session invalidation also persists an encrypted reauthentication gate across
process death. It prevents REST, lifecycle inbox, WebSocket, startup, share, and new-chat paths from
silently using retained legacy credentials; only successful account login, explicit phone sign-out,
or the user's explicit Legacy entry releases that gate.
Compatibility selection is independently durable and explicit. A still-signed-in account with no
selected Mac never consumes a retained App Token for REST, WebSocket, startup, or new-chat traffic;
device requests stop with `HR-BIND-009` and the device picker repairs the route. Entering Legacy mode
is the only action that enables those retained credentials across restart, and selecting an account
Mac or completing account login switches back. The account-level phone inbox remains available while
the only missing state is a Mac selection.
The non-secret last account Gateway origin remains available after invalidation so reauthentication
cannot be redirected to an unrelated retained legacy Relay. Repair screens preserve the existing
navigation stack and return to the interrupted chat/page after account and device readiness succeed;
fresh installations or an unavailable prior stack enter Chats.
Runtime account WebSocket revocation is classified by one follow-up handshake and then stops retrying:
`401` requires account sign-in, while `404` removes only the rejected selected-device route. Revoking
a historical/shared chat Mac preserves the valid default Mac and restores its transport route. The
client acknowledges the Gateway's 4403 close; a failed classification attempt also stops until a new
explicit or foreground recovery cycle.
Operation-scoped `HR-AUTH-006` recent authentication never clears the account session.
Account-routed Hermes REST now applies the same policy centrally for every page: HTTP `401` or
`HR-AUTH-003/004/005` persists the sign-in gate, while only explicit `HR-BIND-011` repairs the tagged
request device. Ordinary `404` and `HR-AUTH-006` preserve the account and routes; losing a historical
shared Mac restores the valid default, whereas rejection of the default clears only its selection.
Authenticated Account API calls from the account/device control surface reuse this classification as
well. Session-family or current-device revocation immediately stops the stale live transport, while
`HR-AUTH-006` preserves it and presents the registered bilingual recent-authentication explanation.
A sole available Mac auto-connects; multiple Macs require an
explicit choice. A local device switch commits only after the cloud default mutation and an
end-to-end probe both pass, and revoked device access clears only the device selection. Conversation-
device affinity is persisted per account/profile/session and propagated
through navigation, search, startup recovery, runtime/read/pin state, REST metadata/history, and
notifications. Changing the default Mac affects lists/search/new conversations only; existing chats
route to their originating Mac, equal session IDs on different Macs remain isolated, and revoked
historical access surfaces `HR-BIND-011`. A signed-in no-Mac device page performs a visibility-scoped five-second discovery poll,
stops immediately when left, and auto-selects/probes the first sole Desktop binding. The expanded
client also deterministically recovers when the current Mac disappears: a sole remaining Mac passes
the full selection gate and takes over once, while zero/multiple devices retire the stale route and
wait. Failed replacement probes never create a false local selection, and failed ordinary switches
preserve a valid current Mac. The expanded local Android JVM/debug-build baseline passes 978 tests,
including Robolectric light/dark/large-font
inspection. Physical email delivery/two-account/two-Mac verification, emulator or real-device
accessibility inspection (including the compiled encrypted-store persistence regression), APK
version/package gate, production flags, deployment, and live two-Mac acceptance remain separate gates.

Local E9 Android status (2026-09-08): Settings exposes permanent deletion only when
`accountAuth.accountDeletion` is true. Exact typed `DELETE`, a separate acknowledgement, and a fresh
current-email `account.delete` code gate the request. The encrypted store persists challenge timing,
grant, and the exact final replay key but never the typed confirmation or OTP. Lost responses and
process death retry the same DELETE; `HR-ACCOUNT-012` completes local cleanup, while stale proof is
discarded without damaging a valid account session. A committed deletion clears account credentials,
stops account transport/inbox/startup, and persists a terminal gate that prevents retained App Token
fallback. Only explicit new-email sign-in or Legacy selection leaves that state, and local Mac Hermes
data remains untouched. The complete 978-test JVM baseline, debug build, instrumented-test compile,
and danger-zone/terminal Roborazzi inspection pass. Emulator/physical dialog accessibility, live
email/account deletion, APK release, privacy approval, and deployment remain external gates.

The local E0-E9 implementation can now move through those independent acceptance gates. The earlier
single-stream estimate excluded future provider approval, Apple notarization, mail-domain DNS
propagation, and production change windows; those exclusions still apply.

## 11. Definition of done

The expansion is complete only when:

- email OTP creates and reauthenticates one stable internal account without reusable passwords;
- an account can operate three owned Macs and ten shared Macs with deterministic selection;
- an owner can grant and immediately revoke whole-device access for up to five accounts per Mac;
- authorization remains correct across REST, WebSocket, file, and event paths under races and restart;
- Desktop can bootstrap a clean Mac without leaking model or Hermes credentials or damaging an
  existing installation;
- Web provides complete account/device/share management;
- legacy clients remain operational through the documented window;
- production enablement and Android release each pass their own separately authorized gate.

Google/Apple linking is a later milestone and is not part of the email-first definition of done.
