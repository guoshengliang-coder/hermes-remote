# Hermes Go Desktop

Current internal test release candidate: **0.2.25** (build 28). One change.

Its pinned schema-v1 manifest moves from 0.3.9 to **0.3.10**
(`https://mrlgs.net/desktop/releases/0.3.10/Hermes-Desktop-0.3.10-arm64.manifest.json`), which carries
Connector **0.1.7** and the HG-90 request-cancellation path. When Android abandons or times out a
tunnel request, the Gateway can now stop its matching local fetch, response read, and chunk-ACK wait
instead of leaving stale work behind the next reconnect. The Hermes Server artifact is reused byte for
byte from 0.3.9; no Android, Gateway, protocol, or Hermes runtime contract changes are part of this
Desktop release.

0.2.24 (build 27) had one change (#365).

This Mac's own Hermes is used **by default** —
`HermesGoLocalHermesRuntimeEnabled` now defaults to on, so a managed Mac with a usable standard
Hermes switches to it on its next refresh, and a fresh install writes the local agent directly.
A fresh Mac with no Hermes is offered upstream's official installer (confirmation required, system
proxy honoured, `HR-MIGRATE-015`–`018` on failure, "改用内置 Hermes" as the way out). Opt a Mac out
with `defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool false`. See
`docs/DESKTOP_PHASE0.md` ("Installing Hermes when the Mac has none").

0.2.23 (build 26) had one change. Its pinned schema-v1 manifest moves from 0.3.8 to **0.3.9**
(`https://mrlgs.net/desktop/releases/0.3.9/Hermes-Desktop-0.3.9-arm64.manifest.json`), which carries
**Connector 0.1.6** with the upstream contract check (#359): the Connector compares the local
Hermes' `openapi.json` with the routes the app depends on and serves the verdict at
`/api/hermes-remote/contract`, so an incompatible `hermes update` reaches the phone as
`HR-COMPAT-001/002/003` instead of later, vaguer failures. Hermes Server is 0.3.8's exact artifact.
Nothing else in the app changes; the rebuild is required because the manifest URL is written into
`Info.plist` at build time.

0.2.22 (build 25) had one fix (#356).

Every managed Hermes restart — switching to or from this Mac's own Hermes, reloading, and the
in-app managed upgrade and its rollback — waited for a proof that the old Hermes had released
127.0.0.1:9119 that could never succeed on macOS: a refused loopback connection stays in
`NWConnection`'s `.waiting(ECONNREFUSED)` and was read as "still listening". On 2026-09-21 this
left the Mac mini's Hermes unloaded for about three minutes after a runtime switch (HG-68's "needs a
manual activation" is the same bug). The probe now treats a refused connection as proof, a failed
restore re-bootstraps its agent and reports `HR-MIGRATE-013` instead of staying silent, an unloaded
bundled job is loaded again unless another process holds the port (`HR-MIGRATE-014`), diagnostics
keep the original and recovery errors, service operations are logged to
`Managed/logs/desktop-runtime.log`, and the migration journal reads timestamps without fractional
seconds.

0.2.21 (build 24) had one change.

It can run this Mac's own Hermes instead of a second, pinned copy (`docs/MANAGED_HERMES_STRATEGY.md`,
one Hermes per Mac). When the owner's standard install (`~/.hermes/hermes-agent`, 0.21.3 or newer,
default profile only) is present and the setting is on, the `com.hermesgo.hermes-server` LaunchAgent
starts that Hermes through a private launcher that hands over the session token, restarts it when
the checkout's commit changes, and keeps the bundled agent as the rollback. In 0.2.21 the setting
was off by default and turned on per Mac with
`defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool true` (on by default
since 2026-09-22, above); turning it off restores the bundled agent byte for byte. Repeated failures pause with `HR-MIGRATE-011` instead of
looping. The pinned manifest stays at **0.3.8**.

0.2.20 (build 23) had two changes.

Startup recovery now keeps a healthy managed upgrade actionable when only the token-storage or
LaunchAgent search-path repair fails. Those advisory failures are reported as retryable
`HR-MIGRATE-007`; only a real Connector ownership mismatch retains the blocking `HR-MIGRATE-002`
(HG-68).

Its pinned schema-v1 manifest moves from 0.3.7 to **0.3.8**. That managed release carries the
read-only `session.access` projection and bounded inline-image history required by Android's
cross-client ownership, authoritative run-state recovery, and oversized-history fixes (HG-66,
HG-67, HG-69). The patches do not change Hermes writes, the database schema, migrations, or session
ownership, and are applied to the actual packaged source allowlist before the archive is signed.

0.2.19 (build 22) had two changes.

It notices when this Mac's `state.db` has grown columns the managed Hermes was not built to read, and
says so as `HR-MIGRATE-006` instead of letting a read fail later with a traceback that names only the
web framework (HG-71). The comparison is by column, not by `schema_version`: upstream added
`display_identity` and `display_order` while leaving that number at 30 on both sides, so a version
gate would have missed the very incident it exists for.

Its pinned schema-v1 manifest moves from 0.3.6 to **0.3.7**, which carries the first managed-Hermes
patch. The rebuild is required, not cosmetic: the manifest URL is written into `Info.plist` at build
time, so a Mac cannot be pointed at a new release by publishing one.

0.2.18 (build 21) was 0.2.17 with its manifest moved to **0.3.6**, which carries Connector **0.1.5** —
the release that stops an oversized Hermes answer from destroying the tunnel (HG-65).

0.2.17 (build 20) added the managed in-app upgrade
transaction for an already active installation. When the pinned signed release is strictly newer,
Desktop now offers “下载并验证更新 → 升级并重连”, preserves the existing account credential, binding,
session token and Hermes data, waits for the old loopback listener to stop before starting the new
Hermes, and commits only after fresh local and Cloud health. Failure or app restart restores the exact
previous LaunchAgents, bundled pointer, services and `account_active` journal. Same-version and
downgrade targets remain read-only.

0.2.17 kept the schema-v1 manifest pinned to **0.3.5** and component preflight off, so a Mac already
on 0.3.5 correctly stayed connected with no upgrade action — which is exactly why the Connector fix
needed 0.2.18 as well as 0.3.6.

0.2.16 (build 19) carried the same single change 0.2.15 carried — the pinned managed-release manifest
points at **0.3.5**, which carries Connector **0.1.4** — and was republished because the 0.2.15 DMG did
not contain that configuration.

`build-dmg.sh` runs `build-app.sh` itself, so a DMG built in a separate shell invocation from the
configured `desktop:app` run silently rebuilt the app with the repository defaults: empty release URL,
managed bootstrap off. The published 0.2.15 DMG wrapped that build and would have replaced a working
0.2.14 with one that could not manage anything. It was withdrawn rather than replaced in place, because
these artifacts are served with a one-year `immutable` cache behind a CDN and rewriting bytes under a
version string cannot be relied on to reach anyone. **Build the DMG and the app in one invocation, with
the configuration in the same environment, and read the packaged `Info.plist` back out of the mounted
DMG before publishing.**

That one pinned value is the whole reason this version exists. The Connector fix from PR #313 — an
oversized frame from the local Hermes now closes the tunnel with 1009 and a reason naming the limit,
instead of the anonymous 1006 the relay refuses to forward, which cost one conversation the entire
transport for twenty-four minutes (HG-65, HG-64) — reaches a Mac only through a managed release, and the
release URL is baked into `Info.plist` at build time. A Mac running 0.2.14 asks for 0.3.4 and nothing
else, which is why publishing 0.3.5 alone changed nothing on the machine that filed those reports.

Component release **0.4.1** carries the same Connector on the schema-v2 channel and is also published.
This build still leaves `HermesGoDesktopComponentManifestURL` empty and component preflight off, exactly
as 0.2.14 did, so which channel a Mac uses does not change here.

0.2.14 (build 17) gave the managed Hermes server a `PATH`. launchd starts an agent with
`/usr/bin:/bin:/usr/sbin:/sbin` and nothing else, so anything the user had installed was invisible to
it: a PDF attachment from the phone was refused with `pdf.attach 5028 "pdftoppm not installed"` on a
Mac where `pdftoppm` had been installed four and a half hours earlier, in `/opt/homebrew/bin` (HG-58).
The written agent now carries both Homebrew prefixes ahead of launchd's four, an agent written before
this validates without one and is repaired by the next optional-component activation, and a malformed
`PATH` on disk is refused.

0.2.13 would have fixed nothing on a Mac that had already migrated, because the only writers are a
migration and an optional-component activation and installing a newer Desktop is neither. 0.2.14 adds
the startup repair that closes that: `reconcileCommittedHermesSearchPath()` runs on the same startup
reconciliation as the account and token-file ones, adds the key to an agent it recognises, leaves an
existing well-formed `PATH` alone, refuses a malformed one, restarts **only** Hermes, and restores the
exact previous file if that restart cannot prove a healthy server. It happens at most once per machine
— the second launch finds the key present and spends nothing. **The restart is not announced and
cannot be declined, and nothing can tell whether a turn is in flight.**

0.2.12 (build 15) carried the production-enabled schema-v2 component bootstrap path, kept the app
visible in the Dock, and made Overview follow the Mac selected in Account & Devices. Desktop verifies
the signed component manifest, reuses exact compatible local runtimes, downloads only missing bootstrap
components into a private cache, and requires a second explicit confirmation before it changes the
managed component store, account binding, LaunchAgents, or services.

Desktop 0.2.10 (build 13) remains installed with managed release 0.3.4 on the historical test Mac. It
packages the post-restart Cloud health freshness correction. Immediately before starting an
already-bound Connector, Desktop records the exact binding's server-provided `endToEnd.checkedAt`;
acceptance requires the same binding ID and generation to become healthy with a strictly newer
timestamp. The same rule protects token-file migration rollback, preventing a cached healthy snapshot
from masking a failed Connector restart.

The ordinary 0.2.9-to-0.2.10 app replacement preserved both managed service PIDs. A subsequent
Connector-only restart kept the exact account binding and generation, advanced the Cloud health
timestamp, and restored its Gateway TLS connection while Hermes kept running. The 0.3.4 artifacts
remain published and unchanged. Physical Android account traffic and a full Mac reboot remain deferred.

Physical upgrade on the historical Mac found that managed release 0.3.3 corrected only the packaged
Hermes reader; its Connector reader still rejected the existing valid 64-character lowercase-hex
token. Connector exited before account connection, and Desktop restored both exact inline
LaunchAgents, removed the uncommitted token file, and restarted healthy 0.3.3 services.

Desktop 0.2.8 (build 11) was the temporary recovery release used with managed 0.3.3 in restored
inline-token mode before the corrected 0.2.9/0.3.4 pair was installed.

Desktop 0.2.6 (build 9) skips the startup token-file migration for managed releases older than 0.3.1,
preserving their inline session token and running services.
Desktop 0.2.5/build 8 remains withdrawn after a physical target found that it moved the active
managed 0.3.0 Connector to a token-file contract that release did not support. Its Gateway control
connection remained online while Android WebSocket tunnels failed local authentication with
`HR-CONN-002`. Desktop 0.2.6 has since passed installation and pre-reboot physical Android REST and
WebSocket checks on that target. Reboot recovery remains deliberately deferred while the Mac is in
use and is still required before full acceptance.

Desktop 0.2.5 carried the managed-migration recovery and
private loopback session-token handoff required by Hermes Server 0.21.0, and reports the effective
managed Agent instead of treating the intentionally stopped legacy Connector as a failure. A managed
installation whose this-device-only account session is absent remains visible as running but awaiting
account verification. A committed account migration now persistently disables the legacy LaunchAgent,
re-enables it on pre-commit rollback, and reasserts that single-Connector state after Migration
Assistant restores both labels. While the account is signed in, legacy App-Token health is also
removed from Overview, Diagnostics, and aggregate status. Public distribution still requires
Developer ID signing, notarization, stapling, and clean-Mac acceptance.

The withdrawn release also attempted to repair committed managed installations created before the private
session-token file contract. On startup it preserves the existing high-entropy local token, removes
that value from both owner-only LaunchAgent plists, writes it to the owner-only managed secrets file,
then restarts Hermes before Connector and requires both local readiness and the exact bound account
health before recording completion. A partial write or failed health proof restores the exact prior
plists/token state and restarts that configuration; mismatched plist values or account bindings fail
closed with the existing migration diagnostic. The corrective implementation first requires managed
release 0.3.1 or newer, the immutable release boundary at which both packaged components support the
file contract; an older committed release remains untouched.

Hermes Go Desktop is the native macOS companion for the existing Hermes Remote Connector. The local
I3-A alpha still runs in **compatibility observation mode**: it reads the current user-level launchd status,
non-secret Connector settings, public Relay health, local Hermes reachability, and sanitized logs.
It can additionally save an App Token in Keychain, generate the existing Android v1 pairing QR, and
run an authenticated end-to-end status check. When the Gateway advertises macOS `email_otp` account
support, it can send a six-digit code to the user's email, exchange it for a management session,
persist/refresh that session, show the binding and phone installations, remove one phone only after
re-verifying the current account email, and sign out only this Desktop management session. When the
Gateway additionally advertises
multi-device selection, it lists up to three owned Macs, remembers this Desktop's selection, and can
set the account default. When whole-device sharing is separately advertised, Account & Devices also
separates owned and operator-access Macs, shows masked invitations/grants, requires an explicit
whole-Hermes disclosure, reauthenticates by email code before inviting, and supports cancel, revoke, leave, and
pasted-link acceptance. The local E4-D path now implements the default-off signed download,
artifact/archive validation, account binding, atomic managed install, user LaunchAgent single-instance
control, health-gated commit, rollback, and restart recovery path. The packaged UI exposes a
two-stage “download and verify → exact release confirmation” action only when complete pinned release
configuration and the frozen `hermes-serve-v1` contract match a separate Gateway capability. Default
builds have neither enablement, so the action remains absent and read-only compatibility continues.
The migration core now owns separate exact-label Hermes Server and Connector LaunchAgents. It starts
Hermes first and requires a new process-specific ready marker plus healthy loopback probe before the
Connector may start; pre-commit rollback stops both managed services before restoring legacy state.
Each launchd mutation waits for the exact label to converge, so a successful `bootout` whose removal
finishes asynchronously cannot be mistaken for a failed migration or a completed rollback.

The signed path also handles an already active managed installation when the verified target is a
strictly newer semantic version. Upgrade preserves the account credential, binding ID/generation,
Hermes data, and local session token. Before rewriting either LaunchAgent it stores an owner-only exact
snapshot; it stops Connector before Hermes, waits until the old loopback listener on port 9119 has
actually disappeared, then starts Hermes before Connector and requires fresh local plus Cloud health.
Failure or app restart restores the exact prior LaunchAgents and bundled `current` pointer and proves
the old service pair healthy before returning the journal to `account_active`.

When the Gateway separately advertises `accountDeletion`, Desktop exposes a danger-zone flow that
requires typed `DELETE`, an explicit permanence acknowledgement, and a fresh email code. It revokes
the Cloud account and then clears only the Desktop management session; the Connector machine identity
and all local Hermes data remain on the Mac. This capability and its production route remain
default-off.

Email-first rollout note (2026-09-08): the local E7 Desktop slice now implements email challenge/code
login and scoped email recent reauthentication for device invitations and managed-phone revocation.
Google is not shown on the
first-release account surface; its existing implementation remains dormant compatibility code.
Production account mode remains default-off until the live transactional-mail and packaged-Mac gates
pass, and the legacy connection remains available. Local evidence and remaining release gates are in
`../docs/ACCOUNT_MODE_E7_DESKTOP_TEST_RECORD.md`.

## Current boundaries

- Hermes source, configuration, data, and processes are not modified.
- The existing Gateway protocol and Android configuration remain unchanged.
- The App Token is accepted only through explicit user configuration, stored in this-device-only
  Keychain storage, masked in the UI, and never written to logs.
- The v1 QR contains the long-lived App Token and is therefore hidden by default.
- The GUI never starts a second Connector with the same device ID.
- Existing legacy installs and any unknown service on loopback port 9119 remain read-only. A clean
  Mac can reach managed Bootstrap only in an explicitly configured build against a matching Gateway;
  the default packaged app cannot download or install anything.
- The app declares macOS local-network ATS access so its `URLSession` health probes can reach the
  pinned loopback Hermes endpoint on macOS 14 and later. Public HTTP remains disallowed; the exception
  does not enable arbitrary network or WebView loads.
- Managed-candidate Hermes health uses a dedicated ephemeral URL session with system HTTP/PAC proxies
  disabled. The proof must reach `127.0.0.1:9119` on this Mac; public Relay traffic continues to use
  the normal system networking configuration.
- Candidate Hermes and Connector health each receive up to 75 one-second polls. This covers a measured
  35-second cold start on the physical Mac mini without weakening the exact marker and HTTP proof.
- Managed takeover requires exact user confirmation and never runs the legacy and account Connector
  labels together. The legacy label is persistently disabled before managed startup and re-enabled
  before rollback restore. On Desktop startup, an exact `account_active` journal plus both loaded
  managed services may suppress a transferred legacy label; no intermediate or mismatched state may
  use that repair.
- An exact committed installation without the session-token contract marker is reconciled once at
  Desktop startup. Both managed LaunchAgents must be owner-only, point to the current managed
  executables and exact log paths, and either agree on the same valid inline token or already agree on
  the canonical private token file. The current account binding ID and generation must match before
  any file or service mutation. Immediately before an already-bound Connector starts, Desktop records
  the Cloud `endToEnd.checkedAt` value and accepts the restart only after the same binding reports a
  strictly newer healthy value. A cached healthy snapshot cannot commit the candidate or its rollback.
- Download/verification occurs before the exact version confirmation and cannot mutate installation,
  credentials, LaunchAgents, processes, or bindings. Closing the confirmation removes the private
  workspace. A committed install that cannot clean temporary files exposes only a cleanup retry and
  never offers a second install.
- An active managed release offers “升级并重连” only for a strictly newer pinned or signed component
  release. Same-version and downgrade requests remain read-only. Upgrade never calls binding creation
  or confirmation and never rewrites the account credential or local session token.
- On a later Desktop launch, an `account_active` journal plus both exact managed LaunchAgents is shown
  as the active installation. An intermediate journal is recovered before any new install is allowed;
  mismatched journal/service state fails closed with a registered migration issue. A completed rollback
  to `legacy_active` or `clean_uninstalled` admits a newly confirmed migration run and atomically replaces
  the terminal journal; when that run's pending cloud binding has since expired, only its recorded
  generation may be recreated. Active, intermediate, unrelated revoked, and manual-attention states
  remain non-replaceable.
- Existing-install observation/recovery does not depend on the new-install flag. The active journal's
  binding ID/generation must match the signed-in account, so account B cannot claim or replace account
  A's already managed service on the same Mac.
- Account-session bearer material and the future Connector Ed25519 machine identity use separate
  this-device-only Keychain items.
- Refresh/removal/sign-out idempotency keys are persisted before their request so a lost response can
  be retried without minting or revoking the wrong credential.
- Sign-in and reauthentication codes stay in UI memory only and are never written to Keychain or
  diagnostics. Email exchange and reauthentication retries reuse their original idempotency keys.
- A pending `device.share`, `account.installation.revoke`, or `account.delete` grant and its idempotency key are stored
  only in the account-session Keychain record until an ambiguous mutation completes; invite tokens
  remain in memory and
  all `hga_`/`hgr_`/`hgg_`/`hsi_` values are redacted from diagnostics.
- Sharing remains hidden unless the Gateway advertises it. Operators can use/select/leave a shared
  Mac but cannot invite others or manage its binding.

## Build and test

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
npm run desktop:dmg
```

Default builds contain no Google client ID. Email login needs only an `email_otp`-enabled Gateway at
`HERMES_GO_ACCOUNT_GATEWAY_URL`; no provider client secret is embedded in the app. The following
command is retained only for later Google-provider development, using an approved Google **Desktop
app** OAuth client:

```bash
HERMES_GO_ACCOUNT_GATEWAY_URL=https://relay.example \
HERMES_GO_GOOGLE_MACOS_CLIENT_ID=example.apps.googleusercontent.com \
npm run desktop:app
```

The app opens Google's account chooser in the system browser with PKCE S256, state, nonce, and a
temporary `127.0.0.1` callback. It never reads Chrome/Safari profiles or stores a Google access token.

When an account session is signed in, Overview, Diagnostics, and the menu-bar aggregate status omit
the legacy App-Token end-to-end probe. That compatibility probe remains available only through the
collapsed legacy configuration path and cannot degrade a healthy account-mode presentation.

`desktop:app` and `desktop:dmg` use ad-hoc signing when `SIGNING_IDENTITY` is unset. A public build
requires a Developer ID Application identity and Apple notarization credentials; see
`docs/DESKTOP_PHASE0.md`.

The desktop icon is copied from the Android app's canonical
`android/app/src/main/ic_launcher-playstore.png`. Run `desktop/scripts/sync-app-icon.sh` after that
source changes. Packaging fails if the two files differ.

## Structure

- `Sources/HermesGoDesktopCore/` — status, compatibility inspection, OAuth/account transport,
  multi-device selection, signed release acquisition, managed installation/migration, Bootstrap
  planning, Keychain session/machine identity, probing, and redaction.
- `Sources/HermesGoDesktop/` — SwiftUI window, menu bar, account/devices, and compatibility screens.
- `Tests/` — compatibility, state-reduction, and security tests.
- `Packaging/` — static bundle metadata and the synchronized app icon.
- `scripts/` — local `.app` and `.dmg` packaging.
