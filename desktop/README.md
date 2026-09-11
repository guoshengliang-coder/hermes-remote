# Hermes Go Desktop

Current internal test release: **0.2.4** (build 7). It carries the managed-migration recovery and
private loopback session-token handoff required by Hermes Server 0.21.0, and reports the effective
managed Agent instead of treating the intentionally stopped legacy Connector as a failure. A managed
installation whose this-device-only account session is absent remains visible as running but awaiting
account verification. A committed account migration now persistently disables the legacy LaunchAgent,
re-enables it on pre-commit rollback, and reasserts that single-Connector state after Migration
Assistant restores both labels. While the account is signed in, legacy App-Token health is also
removed from Overview, Diagnostics, and aggregate status. Public distribution still requires
Developer ID signing, notarization, stapling, and clean-Mac acceptance.

The next maintenance source also repairs committed managed installations created before the private
session-token file contract. On startup it preserves the existing high-entropy local token, removes
that value from both owner-only LaunchAgent plists, writes it to the owner-only managed secrets file,
then restarts Hermes before Connector and requires both local readiness and the exact bound account
health before recording completion. A partial write or failed health proof restores the exact prior
plists/token state and restarts that configuration; mismatched plist values or account bindings fail
closed with the existing migration diagnostic.

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
  any file or service mutation.
- Download/verification occurs before the exact version confirmation and cannot mutate installation,
  credentials, LaunchAgents, processes, or bindings. Closing the confirmation removes the private
  workspace. A committed install that cannot clean temporary files exposes only a cleanup retry and
  never offers a second install.
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
