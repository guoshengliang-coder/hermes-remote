# Hermes Go Desktop phase 0

## Objective

Prove that a native macOS GUI can observe the current Connector safely, preserve the existing Android,
Gateway, and Hermes behavior, and be packaged as an app/DMG before any background-service takeover.

## Implemented in the first slice

- SwiftUI menu-bar app and reopenable main window.
- Shared overview, diagnostics, logs, pairing, and settings navigation.
- Read-only detection of the existing `com.hermesremote.connector` user LaunchAgent.
- Strict allowlist parsing of non-secret fields from the legacy `connector.env`.
- Public Relay and local Hermes reachability probes.
- Bounded legacy log tailing with centralized redaction.
- Explicit compatibility-observation messaging.
- Canonical Android app-icon reuse with a packaging consistency gate.
- Ad-hoc local `.app` and `.dmg` build scripts.
- Unit tests and macOS CI entry.

## Implemented in phase 0.5

- Manual local profile name, Relay URL, and App Token editor.
- This-device-only macOS Keychain persistence for the complete connection profile.
- Android-compatible v1 payload generation using only `v`, `url`, and `token`.
- Native Core Image QR rendering, hidden by default with an explicit long-lived-token warning.
- Authenticated `/api/status` end-to-end probe using `X-Hermes-Session-Token`.
- Stable mappings for invalid Token, offline Connector, invalid Relay URL, network failure, and
  unmapped Relay failure using the existing error registry.
- Bilingual structured Desktop issues with retryability, recovery actions, and redacted copyable
  diagnostics.
- Separate recent-log warning counts that do not override current reachability health.

## Deliberately not implemented yet

- No Connector process replacement or second Connector instance.
- No launch-at-login registration for a new Agent.
- No Connector Token import.
- No one-time pairing code; the compatible v1 QR contains the saved long-lived App Token.
- No Connector control IPC.
- No automatic repair, Hermes restart, or configuration write.
- No Developer ID signing, notarization, or automatic update.

## I3-A account-client alpha — local only

The next local slice adds account management without changing the phase-0 compatibility boundary:

- **Account & Devices** replaces Phone Pairing as the primary navigation item.
- Google authorization uses the system browser, PKCE S256, cryptographic state/nonce, explicit
  account selection, a temporary IPv4-loopback callback, cancellation, and a three-minute timeout.
- Only the returned ID token is sent once to the Gateway; the Google access token is discarded.
- Hermes GO access/refresh credentials and the future Connector Ed25519 machine key use separate
  this-device-only Keychain items.
- The account client discovers capabilities, refreshes sessions, displays binding/phone state,
  removes exactly one phone, and signs out only the Desktop management session.
- Refresh and completion-operation idempotency keys are persisted before transmission so a lost
  response can be safely retried after restart.
- The existing URL/Token/QR flow remains under Advanced: Legacy connection.

I3-A does not create/confirm a binding, request replacement, unbind, migrate credentials, start a
second Connector, or mutate Hermes. Live Google OAuth, production capability enablement, real
Keychain restart, and target-Mac UI inspection remain separate gates.

The current release sequence supersedes Google-first onboarding. E7 now provides the local
email challenge/code login and email recent reauthentication path; the Google implementation remains
dormant behind the server provider flag and an absent Desktop client ID. I3-A is retained as local
historical evidence, not as the email-first shipping UI.

## E7 email-first Desktop account client — local only

- The signed-out screen exposes email plus six-digit code as its only account action.
- Challenge requests bind to the stable macOS client installation identity; exchange uses a stable
  per-challenge idempotency key and validates that the returned installation is this macOS Desktop.
- Whole-device invitations require a second code sent to the signed-in account email and exchange it
  for the scoped `device.share` grant. Lost reauthentication and invitation responses reuse persisted
  idempotency state without persisting the code.
- Removing another phone requires the same owner-email proof for an
  `account.installation.revoke` grant. The Gateway accepts only a phone target, and Desktop retains
  the grant plus exact mutation key only long enough to recover a lost response.
- `HR-AUTH-009` through `HR-AUTH-011` have bilingual Desktop copy, retryability, and recovery actions.
- `HR-ACCOUNT-009` has bilingual Desktop copy when identity management is not enabled.
- Google OAuth code remains available only for future provider work and has no first-release UI entry.
- A successful email exchange is sufficient to retain the Desktop management session. Dashboard refresh always
  validates `/v2/account`, but requests installations only when `identityManagement` is advertised and requests
  Connector binding only when `binding.enabled` is true. An email-only gray rollout therefore renders a signed-in
  account with empty management sections instead of converting disabled-route 404 responses into a login failure.

The default-off account-lifecycle slice adds capability-gated permanent Cloud-account deletion to
Desktop. The danger sheet requires typed `DELETE`, an explicit permanence acknowledgement, and a
fresh email code exchanged for `account.delete`. Ambiguous delete responses reuse Keychain-held
grant/idempotency material; success clears only the account management session and leaves the Mac
machine identity, Connector installation, and local Hermes data intact. The current process then
shows a truthful `Cloud account deletion submitted` terminal state instead of treating submission as
completed erasure or immediately returning to sign-in. A later fresh launch has no retained account
credential and therefore starts signed out. This does not enable the production flag or exercise any
real account.

This slice does not enable the production flag, configure transactional mail, revoke arbitrary account
installation kinds, or prove delivery/resend behavior against a live provider.

## E4-A multi-device and Bootstrap preflight — local only

The first E4 slice adopts the E3 discovery contract without changing the compatibility boundary:

- capability-gated `GET /v2/devices` discovery and `POST .../select-default` support;
- a per-account, Desktop-local current Mac selection with deterministic fallback to the account
  default when a saved device disappears;
- crash-safe persisted idempotency for cloud default changes;
- owned-Mac status rows distinguishing the local Mac, current selection, and account default;
- a read-only Bootstrap plan that detects clean, running-existing, stopped-existing, and inconsistent
  Connector states and shows every future machine-changing step;
- fail-closed behavior that preserves an existing Connector and never enables a clean install until
  a signed release source is available.

E4-A performs no download, filesystem mutation, LaunchAgent registration, process start/stop, binding
creation, or production capability change. Those operations require the signed-manifest verifier,
atomic rollback executor, account-mode Connector v2 implementation, user confirmation, and clean-Mac
evidence in later E4 slices.

## E4-B signed bootstrap and migration core — local only

The local core now contains the later-slice safety path while the packaged UI remains disabled:

- strict Ed25519 manifest verification with pinned public keys, expiry, compatibility, exact-field,
  origin, filename, size, and checksum rules;
- redirect-free, bounded manifest/artifact downloads and streaming SHA-256 verification;
- tar preflight rejecting traversal, duplicate members, links, devices, and FIFOs before extraction;
- ordered release acquisition that creates a private UUID workspace, verifies and extracts the exact
  Hermes Server plus Connector pair, and removes partial downloads/extractions on any failure;
- one managed-bootstrap executor that passes only the acquired manifest/sources into migration,
  attempts temporary cleanup after every migration result, preserves the original migration failure
  when safe, and never mislabels post-commit cleanup trouble as a rollback;
- private staging and immutable version directories with atomic `current` activation/rollback;
- separate exact-label managed Hermes and Connector user LaunchAgents; Hermes starts first, proves a
  fresh bounded ready marker plus loopback health, and Connector cannot start after a Hermes timeout;
- account Connector credentials written separately at mode `0600`, with no legacy Token or Hermes
  password in the LaunchAgent;
- account-mode Connector v2 challenge proof and local Hermes preflight;
- crash-safe binding create/confirm idempotency, durable migration state, exact-label user launchd
  control with bounded bootstrap/bootout convergence, one-Connector enforcement, automatic
  pre-commit rollback, and restart recovery;
- fail-closed manual-attention behavior when remote commit status cannot be proven.

This is not a release enablement. No real signing key/artifact URL is embedded, no LaunchAgent is
written by the current UI, no real process is stopped or started, and no production flag is changed.
See `DESKTOP_RELEASE_MANIFEST.md` and `DESKTOP_E4_TEST_RECORD.md`.

The next local E4-C gate now has a strict dual-sided readiness contract. The packaged app must contain
the complete signed-release configuration and `hermes-serve-v1`; Gateway must independently advertise
the same contract behind its rollout flag, which remains
`ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=0`. The official loopback
`hermes serve` arguments, `HERMES_HOME` boundary, readiness line, and port-conflict line are frozen in
the core, but the packaged UI still performs no install or process mutation.

## E4-D default-off packaged orchestration — local only

The packaged source now connects the readiness card to the core without enabling the default build.
When and only when both rollout gates match, a clean Mac can first download, verify, and unpack the
signed release into a private cache. This preparation phase cannot write credentials or LaunchAgents,
start/stop services, create a binding, or activate a release. It returns the exact signed version and
release-specific confirmation for a second native sheet. Commit re-runs the clean-machine preflight,
derives both executable paths from the verified manifest, and then enters the existing journaled
migration. A foreign/stale preparation or wrong confirmation cannot invoke migration.

Any responder already reachable on reserved port 9119 blocks clean install, including an authenticated
Hermes response. After success, the UI recognizes only an `account_active` journal plus both exact
managed LaunchAgents as active. Intermediate journals enter restart recovery before another install;
unknown/mismatched state fails closed. Temporary cleanup failure retains a cleanup-only retry and uses
`HR-MIGRATE-005`. The production/default plist and Gateway flag remain off, so this source connection
does not authorize a real download, installation, process change, or rollout.

Observation and interrupted-run recovery are deliberately independent of the new-install rollout
configuration. Turning off downloads after a machine is installed therefore does not orphan its
managed services. Active state must also match the current account's exact binding ID and generation;
signing into another account cannot claim or overwrite the first account's managed Mac.

## E4-E offline signed-release publisher — local only

The repository now has a default-inert publisher and an independent verifier for the E4 envelope and
its exact Hermes Server/Connector archives. The publisher requires an external owner-only Ed25519
private-key file, safe regular source archives, canonical release identity/lifetime/origin fields,
and absent output targets. It copies the two archives, computes their size and SHA-256 values, signs
the exact payload bytes, derives the pin-safe raw public key, and then verifies its own output through
the public-key-only path. Partial failure removes only files created by the current run. Packaging,
signature, archive, and integrity failures use the registered `HR-RELEASE-004` diagnostic without
printing private-key contents or paths.

The preceding component builder is also source-pinned and default-inert. It creates a relocatable
Hermes Server using an allowlisted upstream source tree plus bundled Python runtime/site-packages, and
a Connector using production-only compiled JavaScript plus bundled Node and its runtime dependencies.
Neither launcher relies on launchd `PATH`, and neither component archive carries `HERMES_HOME`, `.env`,
account sessions, Connector credentials, or Git metadata.

This closes the offline tooling gap only. No real signing identity, artifact upload, release endpoint,
packaged enablement, Gateway capability, LaunchAgent, or running Connector is changed by E4-E.

The local E5 UI contract is also default-off. When advertised by a development Gateway, Account &
Devices separates owned and shared Macs and exposes whole-device invite/accept/cancel/revoke/leave
controls. E7 replaces the invitation's browser flow with an email code for a scoped `device.share`
grant and applies the same pattern with `account.installation.revoke` when removing another phone;
ambiguous-request recovery material stays in the account-session Keychain record while each code
stays only in UI memory. No live mail
provider, production account, installed Connector, LaunchAgent, or Hermes process is touched by the
local automated tests. Secure Web cookie sessions and CSRF remain a separate gate.

### I3-A local verification — 2026-09-02

- All 38 Desktop core tests passed, including a real ephemeral IPv4-loopback callback, PKCE/state/
  nonce binding, disabled capability behavior, HTTP bounds/redaction, Ed25519 proof generation,
  two-phone state, and lost-refresh-response idempotency recovery.
- The canonical app-icon check, release Swift build, bundle assembly, strict ad-hoc codesign, plist
  validation, and build-script syntax checks passed.
- The packaged app launched and its macOS accessibility tree exposed Account & Devices, layered
  account status, structured failure copy, and the compatibility-mode label.
- The current public account surface was unavailable, so the app failed closed with
  `HR-ACCOUNT-002` and left the legacy entry available. No live Google proof or credential was used.
- This development host had no legacy Connector before or after the run, and Desktop did not create
  one. Target Mac mini PID/launch-count preservation remains pending.
- Existing Node Protocol, Connector, Gateway, release-server, and script suites stayed green; the
  four environment-gated Gateway PostgreSQL/network tests were already exercised by the I2 gate.

## Release boundary

Ad-hoc artifacts are for local validation only. A distributable DMG requires:

- Developer ID Application certificate and private key;
- Apple Team ID and notarization authentication;
- approved final bundle identifier;
- notarization and stapling verification;
- a clean-machine install, launch, upgrade, and rollback run.

## Compatibility rule

Phase 0 must never disrupt the current service. Managed takeover is a later, separately reviewed state
transition with validation and automatic rollback; see `DESKTOP_TEST_PLAN.md`.

## Verification record — 2026-09-02

- All 8 Desktop core tests passed.
- The SwiftUI executable and release target compiled successfully on arm64 macOS.
- The canonical app-icon equality gate passed.
- The `.app` passed strict ad-hoc codesign verification.
- `Hermes-Go-Desktop-0.1.0-dev.dmg` was created and passed `hdiutil verify`.
- The real app launched and its light-mode overview was visually inspected; the screenshot is stored
  at `docs/design/desktop/implementation-phase0.png`.
- The existing Node protocol, Connector, Gateway, release-server, and script suites remained green.
- The development host has no legacy Connector installation, so the absence path was verified there.

### Target Mac mini compatibility run

- Installed the verified ad-hoc DMG on the Apple Silicon Mac mini running macOS 14.8.9. The installed
  application is `/Applications/Hermes Go Desktop.app`, version `0.1.0`.
- The existing `com.hermesremote.connector` LaunchAgent stayed on the same PID and launch count before
  installation, while Desktop was open, after its window closed, after Desktop quit, and after Desktop
  reopened. Exactly one legacy Connector process remained present.
- Public Relay health stayed at one connected Connector and one online device throughout the run.
- The live overview identified the existing `mac-mini` configuration and reported the Connector,
  Gateway, and local Hermes probes as healthy. Observed latency was 15–18 ms for the Gateway and
  117–128 ms for Hermes during this run.
- The diagnostics page exposed the bounded, redacted legacy log preview and explicitly marked the
  App-Token end-to-end check as not executed. It did not claim access to Hermes internal health.
- Closing the main window left both the menu-bar process and legacy Connector running; reopening the
  application restored the main window.
- macOS screen-recording privacy prevented a remote window screenshot without granting broader
  permission. The window title and visible UI values were verified through macOS accessibility APIs;
  no screen-recording permission was added.
- The temporary DMG and test screenshot were removed after installation. The application remains
  installed and running for local inspection.

Physical two-phone use, managed-Agent takeover, and rollback remain pending. Phase 0 still makes no
Hermes, Gateway, Android, token, or Connector configuration changes.

The next E4 acceptance build may offer the same two-stage signed migration to a recognized, running
legacy Connector only when that Connector's configured Hermes status URL is healthy and the complete
Desktop/Gateway managed-install contract matches. Preparation remains inert; the second confirmation
is still required before any service switch. A stopped Connector, an unhealthy configured Hermes,
or a missing/mismatched signed-release gate remains read-only and preserves the legacy service.

### Phase 0.5 local verification

- All 21 Desktop core tests passed, including payload compatibility, native QR round-trip decoding,
  payload-size limits, URL security, structured bilingual errors, redaction, HTTP status mapping, and
  historical-log classification.
- A deliberately invalid test App Token was saved to Keychain, survived an application restart, and
  produced `HR-AUTH-001` without appearing in visible text.
- Saving a validly shaped profile enabled the real QR renderer; the pairing page kept the QR hidden by
  default and displayed the long-lived-token warning when enabled.
- The temporary test Keychain item and all attempted screenshots were removed after the run. macOS
  screen-recording privacy again returned wallpaper instead of application pixels, so no misleading
  phase-0.5 screenshot was added to the design assets.
- The verified `0.2.0-dev` DMG (bundle build 3) upgraded the target Mac from `0.1.0` with a temporary rollback copy.
  Desktop launched on macOS 14.8.9 while the legacy Connector retained the same PID and launch count.
- On the target Mac, a disposable invalid Token persisted through an application restart, mapped to
  `HR-AUTH-001`, stayed absent from visible UI text, and enabled the real QR reveal flow. The test
  Keychain item was then deleted and Desktop was relaunched in its unconfigured state.
- The target update retained exactly one Connector and Relay stayed at one connected Connector and
  one online device. After all checks passed, the temporary `0.1.0` rollback copy and transferred DMG
  were removed; `/Applications/Hermes Go Desktop.app` remains installed and running at `0.2.0`.
- A successful production-token end-to-end probe, Android scanning, and the physical two-phone run
  remain pending. No production App Token was read, moved, printed, or stored during this run.

Final local artifact: `desktop/build/Hermes-Go-Desktop-0.2.0-dev.dmg`, SHA-256
`1ca9d6f5b4f10f49080d6fe1312a05b1ab03ef06ca6c9232d2799e61ba668aa8`.
