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
- Background health coordination keeps legacy inspection and network probes off the main actor, with
  deterministic health and account presentation shared by the window, menu bar, and settings.
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

## I3-B account-control client — local transport only

The Desktop Core now implements the documented `/v2` operations for scoped reauthentication,
first-binding creation/confirmation, replacement creation/confirmation, and unbind. All mutation
retry keys are written to the separate account-session Keychain record before transmission and are
reused after a lost response. Binding inputs expose only the existing Ed25519 public key; private key
material remains in its dedicated Keychain item.

I3-B is not yet a Connector takeover: the operations are not exposed as live UI actions in this
transport slice, no candidate Connector is started, and the old Connector remains read-only observed.
The D0.4 UI exposes those actions only when the Gateway advertises its independent binding capability,
gates activation on key and health proof, and requires explicit confirmations. I5 still owns candidate
proof, process migration, activation ordering, and rollback.

The D0.5 local-deployment gate adds an opt-in Keychain storage namespace and test-only package
identity overrides. This allows an account-enabled loopback build to be installed beside the current
Desktop without reading or overwriting its connection profile, account session, or Connector machine
key. Default and production packages keep the original identifiers and storage services.

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
Hermes, production Gateway, Android, token, or Connector configuration changes.

### D0.5 isolated deployment verification — 2026-09-03

- Cloud Gateway R2 and Desktop D0.1–D0.4 were combined on `codex/desktop-r0`.
- A disposable PostgreSQL 18.6 cluster reached exact schema version 7; migration replay and all 53
  database/network/compatibility Gateway tests passed with no skips.
- The account/binding-enabled Gateway was deployed on `127.0.0.1:58787`; liveness, readiness,
  capabilities, and protected release metadata matched the R2 contract.
- The separately identified and namespaced `0.3.0` build 4 app was installed as
  `/Applications/Hermes Go Desktop Test.app`, passed strict ad-hoc code-sign verification, launched,
  and displayed the capability-enabled signed-out account state through the accessibility tree.
- The final full repository gate passed 122 tests across Protocol, Connector, Gateway, Release
  Server, and scripts with no failures or environment skips.
- The existing Desktop remained `0.2.0` build 3. The legacy Connector remained running at PID
  `11610` with launch count `19`, and the empty test database contained no account, installation, or
  binding created by the Desktop check.
- No live OAuth proof, production service, physical phone, Connector replacement, or Hermes mutation
  was used. Those remain explicit later gates.

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
