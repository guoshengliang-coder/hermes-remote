# Hermes Go Desktop test plan

## Automated baseline

Run on macOS:

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
```

The current automated suite covers:

- overall status reduction and optional-capability degradation;
- legacy Connector config parsing with a strict non-secret allowlist;
- current Gateway WSS to Relay health URL conversion;
- legacy launchd/install/log discovery through injected command execution;
- log/header/query/known-secret redaction;
- profile validation that rejects insecure remote HTTP, URL credentials, queries, and fragments;
- exact Android v1 JSON payload compatibility;
- Core Image QR output decoded back to the exact v1 payload with the native QR detector, including payload-size rejection;
- authenticated `/api/status` request path and header behavior;
- 401/403, offline Connector, and unmapped Relay error classification;
- bilingual error code, retryability, recovery-action, and diagnostic redaction contracts;
- recent-log warning counting independent of current health;
- full SwiftUI target compilation.
- Desktop account capability discovery and default-off behavior;
- PKCE S256, cryptographic state/nonce, provider cancellation, state mismatch rejection, and a real
  ephemeral `127.0.0.1` loopback callback;
- account HTTP paths/headers, bounded responses, stable error mapping, and diagnostic redaction;
- email sign-in challenge/exchange paths, normalized email, stable installation identity and
  exchange idempotency, macOS session validation, and bilingual `HR-AUTH-009` through
  `HR-AUTH-011` recovery contracts;
- email-only gray-rollout capability gating: after a successful exchange, disabled installation and binding routes
  are not requested, the session remains stored, and the account summary reaches the signed-in state;
- separate account-session and Connector-machine identity stores, Ed25519 challenge signing, access
  refresh, and persisted idempotency-key reuse after a lost refresh response;
- signed-in dashboard reduction, two-phone listing, current-account-email reauthentication before
  one-phone removal, rejection of non-phone targets, and Desktop-only sign-out without deleting the
  machine identity;
- bounded phone-revocation request bodies, scoped `account.installation.revoke` grants,
  Keychain-preserved exact retry after a lost response, and bilingual `HR-ACCOUNT-009` mapping;
- capability-gated permanent Cloud deletion, exact `DELETE /v2/account` body/headers,
  `account.delete` email reauthentication, explicit acknowledgement, Keychain-preserved exact retry
  after a lost response, account-session removal on success, and Connector machine-identity
  preservation;
- capability-gated whole-device sharing routes and decoding, owned/operator device separation,
  owner-only share lookups, exact disclosure acknowledgement, invite-link validation, bilingual
  `HR-SHARE-001` through `HR-SHARE-008` mapping, and credential redaction;
- fresh-email-code `device.share` reauthentication plus Keychain-preserved idempotency/grant recovery
  after lost reauthentication or invitation responses, without persisting the six-digit code;
- strict signed Desktop manifest parsing, pinned Ed25519 keys, tamper/expiry/origin/architecture and
  unknown-field rejection;
- offline managed-release publication with owner-only Ed25519 key custody, exact two-archive output,
  independent public-key verification, no-overwrite/partial-cleanup behavior, and redacted
  `HR-RELEASE-004` diagnostics;
- component-archive construction from exact clean Git identities, an allowlisted secret-free Hermes
  source/runtime boundary, production-only Connector JavaScript, bundled architecture-matched
  runtimes, relative launchers, bounded trees, and no-overwrite cleanup;
- redirect-free bounded downloads, exact size and streaming SHA-256 artifact validation;
- tar member preflight, including traversal/link/special-file rejection before extraction, realistic
  dependency trees above the former 4,096-entry limit, and rejection beyond the new 65,536 bound;
- private credential/LaunchAgent writes, immutable release staging, atomic activation and rollback;
- a rolled-back, app-marked inactive release can be atomically replaced by a freshly verified
  same-version retry, while active, unmarked, mismatched, or unsafe directories remain immutable;
- a terminal rollback can resume an already committed Cloud binding only when its recorded ID,
  generation, and retained machine-key fingerprint all match; it performs no replacement or second
  remote confirmation, while every mismatch remains blocked;
- acquisition ordering and lifecycle: private roots exist before verifier pinning, both signed
  components are required, digest/extraction failures remove only the current UUID workspace, and
  explicit discard preserves the parent workspace;
- managed-bootstrap preparation performs acquisition only and returns the exact signed release plus
  confirmation; commit rejects wrong or foreign preparations, while cancel and terminal paths discard
  the private workspace;
- launch configuration is derived from verified manifest entrypoints, the managed layout, frozen
  runtime, and HTTPS account origin rather than caller-supplied executable paths;
- committed cleanup trouble retains a one-purpose retry handle, maps to `HR-MIGRATE-005`, and neither
  claims rollback nor offers a second install;
- exact legacy/account user LaunchAgent labels, duplicate-Connector prevention, health-gated binding
  confirmation, lost-response idempotency, automatic rollback, ambiguous-commit stop, and restart
  recovery;
- persistent legacy-label disablement before managed startup, matching re-enablement before rollback,
  and startup repair that suppresses a Migration Assistant-restored duplicate only when an exact
  `account_active` journal and both managed services prove the committed authority;
- bounded launchd convergence after every managed bootstrap/bootout, including delayed legacy
  removal before managed startup or rollback decisions;
- signed Hermes entrypoint-only plist generation, separate exact Hermes/Connector labels, Hermes-first
  startup, process-specific post-checkpoint ready evidence plus loopback health, bounded/symlink-safe
  log reads, Connector suppression on Hermes timeout, and reverse-order rollback;
- default-off packaged bootstrap configuration, strict HTTPS/key/channel/architecture validation,
  exact `hermes-serve-v1` loopback arguments, Desktop marker, private session-token file path and
  sentinels, absent/mismatched Gateway capability rejection, and readiness only when both gates match;
- private installation-local Hermes token creation/reuse, unsafe file rejection, no token value in
  either LaunchAgent, signed-wrapper file validation, and Connector file loading with no symlink or
  group/world-readable fallback;
- an existing responder on reserved port 9119, including 401/403, blocks clean install;
- packaged ATS configuration explicitly permits local-network health probes while leaving arbitrary
  public and WebView HTTP loads disabled;
- candidate Hermes readiness uses a dedicated ephemeral session that explicitly disables HTTP, HTTPS,
  SOCKS, FTP, PAC, and automatic proxy discovery, so configured proxies cannot intercept or stall the
  `127.0.0.1:9119` commit gate;
- the production migration coordinator passes a 75-poll window to candidate readiness, covering the
  measured 35-second physical-Mac cold start while tests can still inject shorter deterministic limits;
- restart inspection recognizes only an `account_active` journal plus both exact managed LaunchAgents
  as active; intermediate journals recover before a second install and mismatches fail closed;
- overview Agent reduction prefers that exact active managed installation over a stopped legacy
  label, reports a transferred managed service as running-but-unverified until account sign-in, and
  fails closed when the signed-in binding ID or generation differs;
- a newly confirmed run atomically replaces only a terminal `legacy_active` or `clean_uninstalled`
  rollback journal; intermediate, active, and manual-attention journals reject a different run ID;
- a revoked pending binding is recreated only when its generation matches the same Desktop's terminal
  `legacy_active`/`clean_uninstalled` rollback journal; missing or mismatched proof fails closed with
  `HR-BIND-006`, remains visible through the migration presentation boundary, and does not call binding
  creation;
- existing-install observation/recovery remains available when new-install rollout is disabled, and
  active state must match the current account's exact binding ID/generation before it is claimed;
- a recognized running legacy Connector uses its own configured Hermes status URL for the final
  migration preflight and can enter the signed two-stage migration only while that health check and
  the complete managed-install capability agree; stopped/unhealthy or unsigned cases stay read-only;
- bilingual `HR-MIGRATE-001` through `HR-MIGRATE-005` terminal-state mapping.

Remaining email-first release acceptance requires live-provider tests for resend/cooldown, expiry,
account-existence-neutral delivery behavior, packaged-UI inspection proving that an `email_otp`-only
Gateway exposes no Google action, and a packaged two-phone run proving the verification sheet and
selective revocation. The deterministic E7 transport/controller/error and retry cases are now
automated.

Every new phase-0 behavior requires a regression test when its boundary is deterministic. User-visible
error codes additionally require localization, retryability, recovery-action, and redaction tests under
the project-wide `ERROR_HANDLING.md` contract.

## Manual phase-0 matrix

| Case | Expected result | Status |
|---|---|---|
| Existing Connector running | Desktop observes it and does not launch a replacement | Verified on target Mac 2026-09-02; PID and launch count unchanged |
| Existing Connector migration gate | Healthy configured Hermes plus matching signed-release capability exposes preparation; stopped/unhealthy/unsigned cases preserve legacy | Automated; packaged target-Mac migration still pending |
| Migration Assistant preserves managed services | Overview reports the effective managed Agent rather than the stopped legacy label; missing this-device-only account credentials require sign-in; a restored legacy label is persistently suppressed only for a proven `account_active` installation | Desktop 0.2.4 packaged reboot passed on migrated Mac 2026-09-11: only managed labels recovered and legacy stayed disabled/unloaded; post-reboot Android REST/WebSocket pending |
| Existing Connector absent | UI reports not detected and offers no destructive action | Verified 2026-09-02 |
| Gateway available | Gateway layer is healthy with safe latency | Verified on target Mac 2026-09-02; 15–18 ms observed |
| Gateway offline/DNS failure | Only Gateway layer fails; Hermes wording remains accurate | Pending fault injection |
| Hermes returns 2xx | Local Hermes layer is healthy | Verified on target Mac 2026-09-02; 117–128 ms observed |
| Hermes returns 401/403 | Layer says reachable/authentication required, not “down” | Pending real-app check |
| Logs contain credentials | Display/export contains `<redacted>` only | Automated; target redacted UI preview inspected 2026-09-02, credential injection still pending |
| Window closes | Existing Connector remains running | Verified on target Mac 2026-09-02; menu-bar app remained and window reopened |
| Two phones use current Gateway | Existing phone configuration remains usable | Pending device check |
| Light/dark mode | Same cool-blue surface language and semantic states | Light verified 2026-09-02; dark pending |
| App icon | Desktop packaging copy equals canonical Android icon | Verified by packaging gate 2026-09-02 |
| Keychain profile | App Token persists across restart and is never shown in visible UI | Verified locally and on target with disposable test Token 2026-09-02 |
| Invalid App Token | End-to-end check reports `HR-AUTH-001` with recovery guidance | Automated + local/target UI verified 2026-09-02 |
| v1 QR payload | JSON contains only compatible `v`, `url`, and `token` fields | Automated 2026-09-02 |
| QR reveal | Real QR is hidden by default and carries an explicit long-lived-token warning | Local + target UI verified 2026-09-02; Android scan pending |
| End-to-end success | Saved App Token reaches Gateway → Connector → Hermes through `/api/status` | Pending target production-token check |
| Account-mode presentation | A signed-in account omits the legacy App-Token probe from Overview, Diagnostics, and aggregate health while preserving the underlying legacy profile for rollback | Automated |
| Account mode disabled | Account & Devices reports unavailable and legacy connection remains usable | Automated core behavior; packaged UI inspection pending |
| Email account login | Email challenge and six-digit exchange create/restore only the Desktop management session | Controller/API automated; live delivery and packaged UI pending |
| Browser OAuth loopback | Listener binds an ephemeral `127.0.0.1` port and rejects mismatched state | Automated locally; live Google client pending |
| Account session restart | Keychain session refreshes without changing the Connector machine identity | In-memory/store contract automated; packaged Keychain run pending |
| Two account phones | Account & Devices lists both; owner-email verification removes only the selected phone | Controller/API/PostgreSQL automated; packaged UI and physical phones pending |
| Whole-device sharing off | No share controls appear and existing device selection remains unchanged | Automated core behavior; packaged UI pending |
| Owner invites account | Explicit sessions/files/config warning and fresh owner-email verification precede mail request | Controller/API automated; live mail/two-account run pending |
| Recipient accepts invite | Pasted link requires exact token + acknowledgement and creates operator access only | Parser/API automated; two-account physical run pending |
| Owner revokes / recipient leaves | Matching access and live stream end; owner, other grantees, Connector, and Hermes continue | Gateway automated; packaged Desktop and multi-node run pending |
| Account deletion off | No Desktop danger-zone action appears and the route is not called | Core/API automated; packaged UI pending |
| Permanent account deletion | Typed `DELETE`, acknowledgement, and fresh email code precede immediate Cloud logout; success and recovered ambiguous completion land on “deletion submitted”; the explicit other-email exit reaches an empty sign-in flow, while local Hermes remains intact | Core/API/PostgreSQL automated; disposable packaged-account and privacy review pending |
| Android account-mode cutover | Android signs in by email, selects the intended owned/shared Mac, proves REST and WebSocket traffic, then Desktop migrates; protected Connector status matches the exact binding/generation before and after Desktop/Mac restart | Primary single-phone 0.1.113 account path physically accepted 2026-09-10; activation-interruption, multi-phone/shared-Mac, and restart matrix still pending |

The first real-app check verified that the ad-hoc app launches and remains running. The target Mac run
then verified the installed DMG against a live legacy Connector without changing its PID, launch count,
configuration, log size, or public Relay attachment. The diagnostics UI correctly kept the App-Token
end-to-end check unavailable. Physical two-phone traffic, injected fault cases, and takeover/rollback
remain pending; public Relay health alone is not evidence for a phone UI test.

The `0.2.0-dev` target upgrade additionally verified Keychain persistence across restart, explicit QR
reveal, and the invalid-token UI mapping without restarting the legacy Connector. The disposable Token
and temporary rollback package were removed after the run. A real production Token was deliberately
not retrieved as part of this test, so the successful end-to-end and Android scan rows remain pending.

## Android-account coordinated acceptance

Run this matrix only after the Android account branch has passed its package gate. Do not migrate the
Mac merely because email login or `/v2/devices` succeeds.

1. Keep the legacy Connector active. Install the versioned Android test artifact, sign in with email,
   and confirm the intended owned or shared Mac is listed without importing a legacy App Token.
2. Record the protected `/internal/account-connectors` snapshot and the authenticated Android device
   row. The former may show zero account connections before migration; `/relay-health` must still show
   the expected legacy Connector.
3. Complete the Desktop's two-confirmation migration. Require an `account_active` journal, both exact
   managed LaunchAgents, healthy local Hermes, and an internal snapshot row whose binding UUID and
   generation match Desktop state.
4. From Android, exercise one REST status request and one real WebSocket session through the selected
   device. For a shared Mac, repeat using the grantee account and confirm revocation closes only that
   grantee's live stream.
5. Restart Android and Desktop independently, then reboot the Mac. Require the same account binding,
   automatic managed-service recovery, a newer process-local `connectedAt`, and successful Android
   REST/WebSocket traffic without re-entering a legacy Token.
6. On any failed gate, capture redacted diagnostics and use the journaled Desktop rollback. Confirm
   the legacy Connector returns, `/relay-health` reports it online, and the old Android path remains
   usable. Do not call a mixed legacy/account state successful.

## Later takeover gate

Managed Agent takeover must not ship until automated and real-machine tests prove:

1. the new Agent does not start while the old Connector is active;
2. configuration validates before the old service stops;
3. the new Agent receives `hello_ack` and passes local/end-to-end checks;
4. failure stops the new Agent and restores the old service;
5. the phone retains its URL, token, sessions, and history after takeover and rollback.

Items 1–4 now have injected local core tests. They are not considered target-Mac verified: the
packaged UI path is compiled but hidden in the default-off build, and no real signed/notarized
artifact has exercised it. Item 5 and the physical clean-install/upgrade/interruption matrix remain
manual gates.
