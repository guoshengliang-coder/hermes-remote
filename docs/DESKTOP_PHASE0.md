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
- account Connector credentials and the installation-local Hermes session token written separately
  at mode `0600`; LaunchAgents carry only their paths, never a legacy Token, Hermes password, or the
  local session-token value;
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
`hermes serve` arguments, `HERMES_HOME` boundary, Desktop-owned loopback session-token handshake,
readiness line, and port-conflict line are frozen in
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
the packaged app declares only `NSAllowsLocalNetworking` so macOS 14+ permits the signed candidate's
`http://127.0.0.1:9119/api/status` readiness probe without allowing arbitrary public HTTP traffic.
That candidate-only probe also disables inherited HTTP/PAC proxies: its process-specific readiness proof
must terminate on this Mac even when the user's public Relay traffic intentionally uses a system proxy.
Both the local Hermes readiness gate and the following Connector binding gate allow up to 75 one-second
polls; the local gate still requires a new exact process marker and a healthy loopback response together.
unknown/mismatched state fails closed. After rollback reaches `legacy_active` or `clean_uninstalled`, a
new explicit confirmation starts a fresh run and atomically replaces that terminal journal; no other
journal state can be replaced by a different run ID. If the failed run's temporary cloud binding expires
before retry, the coordinator passes only the terminal journal's exact generation to the account client;
that generation may create a fresh pending binding, while unrelated revoked state still fails closed with
`HR-BIND-006`. If Cloud instead already reports the terminal journal's exact binding ID and generation
as active, the retry may restore that same binding only when its public-key fingerprint matches this
Mac's retained machine key. This recovery waits for the original binding to become healthy and performs
neither first-binding confirmation nor replacement; every mismatch remains blocked. Temporary cleanup
failure retains a cleanup-only retry and uses
`HR-MIGRATE-005`. The production/default plist and Gateway flag remain off, so this source connection
does not authorize a real download, installation, process change, or rollout.

Observation and interrupted-run recovery are deliberately independent of the new-install rollout
configuration. Turning off downloads after a machine is installed therefore does not orphan its
managed services. Active state must also match the current account's exact binding ID and generation;
signing into another account cannot claim or overwrite the first account's managed Mac.

Committed installations that predate the private session-token file contract are also reconciled by
that always-available recovery runtime. An exact `account_active` journal, both loaded managed labels,
the signed-in journal binding ID/generation, owner-only exact managed plist paths, and one shared valid
token are required before mutation. The active managed release must also be 0.3.1 or newer: 0.3.1 is
the first immutable package whose Hermes wrapper and Connector both consume the file contract, while
0.3.0's Connector accepts only the inline environment value. Older releases return without acquiring
the migration lease, refreshing the account, rewriting a file, or restarting a service. Desktop
preserves the token value, atomically moves it out of both
LaunchAgent environments into the `0600` managed secret file, restarts Hermes before Connector, and
records an owner-only completion marker only after local readiness and bound account health pass.
For an already-bound Connector, Desktop checkpoints the server-provided `endToEnd.checkedAt`
immediately before startup and requires the exact binding/generation to report a strictly newer
healthy timestamp. The rollback restart uses the same freshness proof; a cached healthy snapshot is
never enough. A missing marker makes a same-token half migration resumable after power loss. Failure
restores the
original plist/token bytes and proves the restored services healthy; a mismatch fails closed.

Overview health now reduces the effective background mode rather than treating the stopped legacy
label as the only Agent. An `account_active` journal with both exact managed LaunchAgents therefore
reports the managed Connector as running. If Migration Assistant transfers those managed files and
services while the this-device-only account Keychain record is absent, Desktop reports a degraded
running-but-unverified state and asks the user to sign in; it does not rebind, replace, or start a
second Connector automatically. A signed-in binding mismatch still fails closed.

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

The componentized path in `DESKTOP_COMPONENTIZED_INSTALL_PLAN.md` remains local and default-inert. Its
C1/C2 core distinguishes exact-content reuse from an explicitly signed compatibility contract,
refuses version-only reuse of mutable Python environments, separates bootstrap bytes from optional
on-demand bytes, and verifies shared-store content before reuse. C3 adds a separate schema-v2
publisher, public-key-only verifier, Desktop verifier, and resumable component downloader. Both
offline tools safely extract every archive and recompute the same normalized content identity used by
the Desktop store before accepting it. The downloader exposes a component only after full signed size
and SHA-256 verification. None of these paths changes the shipping schema-v1 manifest, current
installation transaction, or production capability.

C4's first offline slice adds a separate four-archive builder for `python_runtime`, `hermes_core`,
`node_runtime`, and `connector`. Runtime versions and architectures are checked before staging; each
result reports compressed size/hash, normalized extracted-content identity, entrypoint, and dependency
edges for schema v2. The application launchers accept only absolute activation-time component roots,
so the shared store stays immutable. The Python runtime's managed `.pth` reads the active Hermes core
root from the launch environment, preserving slash-worker imports after upstream removes the repo root
from `PYTHONPATH`. Optional Python dependency extraction remains a later tested C4 slice.

C4's second local slice resolves those four archives only from their rehashed managed-store content
identities, enforces the exact Python-to-Hermes and Node/Hermes-to-Connector bootstrap topology,
reruns a bounded health probe, and rejects missing, unsafe, or non-executable entries before producing
a launch plan. The corresponding LaunchAgents carry only the exact Python and Node content roots;
schema-v1 writers reject those schema-v2 runtime fields, and schema-v2 writers reject roots outside
the active managed layout or content changed after planning. This remains an unwired local primitive
and does not start either service.

C4's third local slice composes the signed v2 manifest token, resumable downloader, safe tar
extractor, immutable component-store writer, release references, and activation planner into one
ordered preparation transaction. Only the strict Ed25519 verifier can create the installation token.
A transport interruption preserves an owner-only UUID workspace bound to the exact normalized
manifest identity; the same run may resume it, while a different manifest cannot claim it. Other
failures clean that workspace, and no reference becomes visible until all four components and the
activation plan pass. An explicit cancel can remove only the exact UUID workspace carrying a valid
installer marker. The result still does not mutate credentials, `current`, launchd, or processes.

C5's first read-only slice composes that verified manifest with managed-store identity/health checks
and the fixed-path external environment scanner. It produces one trusted decision list with exact
bootstrap and deferred byte totals. Mutable Python and Node installations remain observations only;
an external browser is reusable only through the signed compatibility contract and Desktop-owned
probe. An unhealthy managed component fails before external scanning so the future UI cannot promise
an overwrite that the immutable store would reject. This result is not yet wired to the shipping UI.

The next local UI slice maps that trusted result into a native component card with release identity,
reuse count, bootstrap/deferred byte totals, and one status row per component. It distinguishes managed
reuse, compatible system reuse, install-time download, and first-use download, and keeps the read-only
Homebrew/service boundary visible. The bilingual presentation model and production SwiftUI build are
covered locally; the card remains unwired until a real v2 capability and signing configuration exist.

The managed store now records each installed optional component in an immutable,
release-scoped capability reference only after that release's bootstrap reference exists. Garbage
collection validates these references with the same owner, permissions, schema, content, and snapshot
rules as bootstrap references; it retains shared optional content across releases and fails closed on
orphaned or malformed capability references. This storage primitive remains default-inert and does
not yet initiate a first-use download.

The following default-inert transaction now resolves one signed `onDemandTrigger` and its optional
dependency closure. It validates the installed base release through both the garbage-collection
snapshot and activation health probes before scanning the host or creating a download workspace.
Managed content is rehashed, a compatible system browser is path-checked again, and missing content
uses the existing resumable downloader, safe extractor, atomic store commit, and capability
references. Transport interruption preserves only a manifest-and-trigger-bound UUID workspace;
other failures remove it. The result is still an unwired local primitive and does not mutate
credentials, the active release, LaunchAgents, or processes.

C4's sixth offline slice lets the schema-v2 archive builder consume prepared browser, speech, and
document component roots alongside the four bootstrap inputs. It validates the optional kind,
trigger, reuse rule, fixed Python dependency edges, bounded link-free content, and executable health
entrypoint before producing any archive. The output now separates bootstrap and deferred bytes, and
each optional archive carries the same normalized content identity used by the signed manifest and
Desktop store. This makes the real dependency trees publishable without mixing them back into the
bootstrap Python archive.

C4's seventh default-inert slice projects installed speech and document `site-packages` roots into a
content-addressed, read-only `.pth` directory after probing the exact managed interpreter's Python
X.Y and extension ABI. The Hermes LaunchAgent model can inject that directory through upstream's
`HERMES_LAZY_INSTALL_TARGET` and can inject a revalidated managed or system browser through
`AGENT_BROWSER_EXECUTABLE_PATH`. The writer rejects symlinks, unsafe ownership or permissions,
duplicate component kinds, external Python content, ABI-probe failure, and any mutation of an
existing projection. It does not persist a LaunchAgent or restart a process; first-use activation,
controlled restart, and one-shot capability retry remain the next local integration gate.

C4's eighth default-inert slice performs that controlled activation under the existing migration
operation lease. It accepts only an `account_active` journal for the exact base release with the
managed Connector and Hermes services loaded, projects the caller's complete active optional set,
atomically replaces the Hermes LaunchAgent, restarts only Hermes, and requires both a fresh readiness
line and healthy loopback status. Any activation failure restores the exact previous plist and, when
Hermes was stopped, proves the restored service healthy before returning the original failure. After
successful activation the original capability closure is invoked exactly once; a retry failure does
not roll back an otherwise healthy installed runtime. That slice still accepted a caller-assembled
active set; the following resolver closes that boundary before shipping request wiring is added.

C4's ninth default-inert slice now derives that complete set without trusting caller-assembled paths.
It fail-closes on an invalid shared-store snapshot, reads only the exact base release's managed
capability references, matches every identity to the signed manifest, and rehashes, probes, and checks
the executable entrypoint again. This preserves an earlier managed optional component when a later
trigger installs another one. A system browser has no managed reference, so it is retained from the
current owner-only Hermes LaunchAgent only after the exact executable passes the signed compatibility
contract and a fresh fixed-path scan. The activation transaction now owns this resolver and runs it
only after acquiring the migration operation lease and confirming the managed service topology, so
callers cannot submit an assembled path set. The production missing-capability signal remains
unwired; the next local slice composes installation with the activation transaction behind a fixed
capability type while leaving upstream signal adoption separate.

C4's tenth default-inert slice now composes that transaction behind a closed browser/speech/document
capability enum. It selects the exact trigger from the verifier-backed manifest, performs the
first-use install, regenerates the complete bootstrap activation plan, enters the locked resolver and
Hermes activation path, and runs the original operation once. Unsupported capabilities and install
failures stop before service mutation, while concurrent requests cannot start a second install. The
adapted Hermes 0.21.0 source has no structured missing-capability event; tool dependency failures are
ordinary error text or silent feature fallback. Shipping request wiring therefore remains blocked on
an explicit upstream wire contract and must never infer a capability by parsing prose.

C5's third default-inert slice now supplies the trusted input path for the component preflight card.
It fetches one bounded HTTPS manifest, requires the strict schema-v2 Ed25519 verifier to produce the
non-forgeable manifest token, and only then starts the read-only managed-store and external-environment
scan. Download or signature failure cannot reach the scanner, and one runtime rejects overlapping
refreshes. No production manifest URL, trust configuration, feature flag, install button, workspace,
credential, LaunchAgent, or service behavior is changed by this slice.

C5's fourth slice adds a separate packaged configuration gate for that runtime. The gate is false by
default and has its own empty schema-v2 manifest URL; it reuses the existing pinned release origin,
channel, architecture, key identifier, and Ed25519 public key only after all fields validate. Missing,
ambiguous, non-HTTPS, credential-bearing, or non-canonical values fail closed. This slice still does
not construct the runtime, fetch a manifest, scan the machine, or render the card.

C5's fifth slice composes that default-off gate, trusted runtime, read-only managed-store scan, and
native presentation. Only a complete enabled configuration constructs the runtime. After account
bootstrap, and again when the user refreshes Account & Devices, Desktop downloads and verifies the
schema-v2 manifest before inspecting the component store and showing the independent preflight card.
Managed reuse requires the full content identity plus an owned, non-writable, non-symlink executable
at the signed entrypoint. The probe deliberately does not launch Hermes or Connector during a read-only
scan; process readiness remains part of installation and activation. Disabled, invalid, download,
verification, and scan paths show no card and cannot reach installation or service mutation.

C5's sixth slice establishes the component installer confirmation boundary before wiring any UI
action. Preparation downloads, verifies, and extracts missing bootstrap components only inside the
owner-private cache; it cannot write the managed component store or release reference. The returned
capability token is bound to the issuing installer. Commit rehashes every staged tree and health-checks
its signed entrypoint before writing immutable components, then publishes the release reference and
activation plan only after all four components validate. Cancellation removes the exact workspace.
A transport retry preserves only safe signed-name partials, discarding completed archives and extracted
trees so they are revalidated. The existing one-call API composes prepare and commit for compatibility.
No Desktop button, credential, LaunchAgent, process, binding, production configuration, or release is
changed by this slice.

C5's seventh slice preserves the verifier-issued schema-v2 token beside the result of that same
trusted preflight. The UI can continue to consume only the ordinary presentation result, while later
component preparation receives the non-forgeable token directly instead of reconstructing authority
from a version, URL, or display row. The compatibility `load` API still returns only the result. This
slice creates no workspace, download action, component-store write, reference, LaunchAgent, credential,
process, binding, production configuration, or release.

C5's eighth default-unwired slice adds the single state machine that consumes that trusted session.
Preparation accepts only the matching scanned result and verifier token and may write only its private
cache. The exact release confirmation is required before it revalidates and commits all four bootstrap
components, derives LaunchAgents from the returned content-addressed Python/Node plan, and calls one
persistent migration boundary. Foreign preparations and mismatched trusted input fail before component
or service mutation; cancellation and terminal failures remove the private workspace. An interrupted
download before handle issuance remains removable only through the installer's UUID-and-private-marker
checked path. A migration failure may leave only the inactive immutable component/reference cache for
safe retry or GC. Successful migration remains successful when temporary cleanup needs its bounded
retry handle. The concrete v2
migration adapter is still deliberately absent because interrupted recovery must first distinguish v2
component activation from the v1 `current` symlink contract in the durable journal. No install action,
production configuration, service behavior, or release changes in this slice.

C5's ninth slice makes interrupted recovery layout-aware before adding the concrete component
migration adapter. New migration journals use schema 2 and bind each run to either the historical
`bundled_release` layout or the v2 `component_store`; every transition preserves that immutable field,
and a same-run layout mismatch fails closed. Existing schema-1 journals decode strictly as bundled
releases and atomically upgrade on their next valid transition. A schema-1 file carrying the new field,
a schema-2 file missing it, an unknown value, or an unknown schema is rejected. Existing v1 recovery
behavior remains unchanged, and no install action, service, production configuration, or release is
changed by this slice.

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

### Internal corrective release — 2026-09-10

- Desktop 0.2.1 (bundle build 4) was built from clean merged commit
  `95acaa3e2b8fecbe9f55ba91fe7378d77b7f2340` with the internal 0.3.1 manifest URL and existing pinned
  internal Ed25519 public key.
- The DMG passed `hdiutil verify`; the mounted and installed app both passed strict ad-hoc codesign
  verification. Its SHA-256 is `c191c10200dd1ebf98c80fcb652e83a47e02d0df7cbef3c768163d9b6c59a4d9`.
- The app replaced 0.2.0 at `/Applications/Hermes Go Desktop.app` and launched successfully. The
  managed Hermes and Connector processes retained their original PIDs, authenticated local Hermes
  status remained healthy, and Connector kept an established Gateway connection.
- Signed managed release 0.3.1 was published at `https://mrlgs.net/desktop/releases/0.3.1/` and verified
  by full public re-download. Release 0.3.0 remains online and unchanged for rollback.
- This is an internal ad-hoc build. Developer ID signing, Apple notarization, stapling, and clean-Mac
  acceptance are still required before public distribution.

### Account-mode legacy-probe retirement

Desktop 0.2.3 removes the legacy App-Token end-to-end row from Overview and
Diagnostics whenever a Hermes GO account is signed in. Aggregate status uses the same filtered
snapshot, so a missing or stale legacy Token cannot degrade a healthy account-mode presentation. The
underlying legacy profile and its explicit save-time probe remain available in the collapsed legacy
editor for rollback during the staged retirement period; no protocol or stored credential is deleted
by this UI-only step.

### Effective-Agent correction release — 2026-09-10

- Desktop 0.2.2 (bundle build 5) was built from clean merged commit
  `f4c3ec612b4afb348ff6663a85cb056409fe2fd3` with the same pinned internal 0.3.1 release configuration.
- The 2,041,257-byte DMG passed `hdiutil verify` and strict ad-hoc codesign verification; its SHA-256
  is `f3568a3aa1a481640386a5737b7c66129af0b74e314c9f68f98d51658410e1d2`.
- It replaced Desktop 0.2.1 on the target Mac mini without restarting the managed Hermes or Connector
  processes. Both retained their pre-install PIDs, the legacy label stayed unloaded, and the journal
  stayed `account_active`.
- Physical UI inspection confirmed the Overview now reports the effective managed Agent as healthy
  instead of treating the intentionally stopped legacy Connector as a failure. Temporary rollback,
  transfer, and clean-build copies were removed after verification.
- Developer ID signing, notarization, stapling, and clean-Mac launch acceptance remain pending.

### Migration Assistant physical transfer — 2026-09-10

- Migration Assistant moved the active 0.3.0 managed installation to a new Mac mini. The journal and
  binding remained `account_active` at generation 7, but launchd restored both the legacy and managed
  Connector labels. The transferred pre-session-token installation also needed its local Hermes
  credential rotated to a new private value shared only by the two managed services.
- The old Connector was stopped and persistently disabled, the managed Hermes/Connector pair was
  restarted with one shared private credential, and authenticated loopback plus the Connector's TLS
  connection to the Gateway passed. Desktop 0.2.3 then launched and the duplicate label remained
  unloaded. No Cloud binding or Hermes data was replaced.
- The deterministic correction now disables the legacy label during the original managed takeover,
  re-enables it during pre-commit rollback, and on later Desktop startup suppresses a transferred
  duplicate only when an exact `account_active` journal and both managed services are present. It is
  inert for intermediate, mismatched, or incomplete installations.
- Desktop 0.2.4/build 7 packaged this correction. Installation and a full Mac reboot preserved the
  `account_active` generation-7 journal, automatically restored only managed Hermes and Connector,
  kept the legacy label persistently disabled/unloaded, returned authenticated local Hermes HTTP 200,
  and re-established Connector TLS. The account-capable Android client then refreshed status and
  opened `/api/ws`; Connector telemetry recorded 1,572 frames to the phone and 28 frames from it with
  no tunnel error. This closes the physical Migration Assistant gate.

### Account-mode presentation release — 2026-09-10

- Desktop 0.2.3 (bundle build 6) was built from clean merged commit
  `8972317b375d3dfde4d09fd2e49069576fa4da7d` with the pinned internal 0.3.1 release configuration.
- The complete 172-test Desktop suite passed. The 2,041,359-byte DMG passed `hdiutil verify` and
  strict ad-hoc codesign verification; its SHA-256 is
  `fc17da801db26141a2e3a9b8c7178a9ea82454389bb84e79a7e4146290978ce5`.
- It replaced Desktop 0.2.2 on the target Mac mini. After the newly signed app received one-time
  Keychain access approval, live UI inspection confirmed account loading completed and the legacy
  App-Token/end-to-end presentation was absent in account mode.
- Authenticated local Hermes health returned HTTP 200 and Connector retained an established upstream
  connection. A session token exposed during local diagnostics was rotated immediately and the old
  value invalidated.
- The later physical Migration Assistant run is recorded above. A packaged rerun of its deterministic
  launch-state correction, Developer ID signing, notarization, stapling, and clean-Mac launch
  acceptance remain pending.

### Migration integrity release — 2026-09-11

- Desktop 0.2.4 (bundle build 7) was built from clean merged commit
  `48a6c2ed610efd83fdb8d55610245f4ac0b26345` with the pinned internal 0.3.1 release configuration.
  The complete 178-test Desktop suite, asset comparison, configured release build, strict ad-hoc
  codesign verification, and `hdiutil verify` passed.
- The 2,045,620-byte DMG SHA-256 is
  `8ba346e4409fa9a72b0999878cc230e23af08268a02b623418311443895a057a`. Target-side size, hash,
  image, installed-app version, and codesign checks reproduced the local result.
- Replacing Desktop 0.2.3 did not restart managed Hermes or Connector. A subsequent full Mac reboot
  started only those two managed labels, left the legacy Connector disabled/unloaded, preserved the
  exact account journal/binding generation, returned authenticated Hermes HTTP 200/version 0.21.0,
  and established Connector TLS. Opening Desktop 0.2.4 after login preserved that state.
- The attached HONOR test phone carried Android 0.1.89 and was not used for account-mode acceptance.
  The operator used a separate account-capable Android client to refresh REST status and open a real
  WebSocket session. Connector telemetry recorded one new `/api/ws` tunnel carrying 1,572 frames to
  the phone and 28 frames from it without a tunnel error, closing the post-reboot phone check.
- Developer ID signing, notarization, stapling, and clean-Mac acceptance remain pending.
