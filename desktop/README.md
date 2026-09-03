# Hermes Go Desktop

Hermes Go Desktop is the native macOS companion for the existing Hermes Remote Connector. The local
I3-B alpha still runs in **compatibility observation mode**: it reads the current user-level launchd status,
non-secret Connector settings, public Relay health, local Hermes reachability, and sanitized logs.
It can additionally save an App Token in Keychain, generate the existing Android v1 pairing QR, and
run an authenticated end-to-end status check. When the Gateway advertises account support and the
build contains a public macOS Google OAuth client ID, it can also sign in through the default browser,
persist/refresh the Hermes GO management session, show the binding and phone installations, remove
one phone, and sign out only this Desktop management session. When the independent binding capability
is enabled, it also presents confirmed first-bind, replacement, and unbind control-plane operations.
It does not start, stop, or replace the current Connector process; candidate proof and managed takeover
remain I5 work.

## Current boundaries

- Hermes source, configuration, data, and processes are not modified.
- The existing Gateway protocol and Android configuration remain unchanged.
- The App Token is accepted only through explicit user configuration, stored in this-device-only
  Keychain storage, masked in the UI, and never written to logs.
- The v1 QR contains the long-lived App Token and is therefore hidden by default.
- The GUI never starts a second Connector with the same device ID.
- Candidate Connector proof and managed Agent takeover remain disabled until their migration and
  rollback gates exist.
- Account-session bearer material and the future Connector Ed25519 machine identity use separate
  this-device-only Keychain items.
- Refresh, removal, sign-out, reauthentication, binding, replacement, confirmation, and unbind
  idempotency keys are persisted before their request so a lost response can be retried without
  minting or revoking the wrong credential.

## Build and test

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
npm run desktop:dmg
```

Default builds leave account mode unavailable. To create a development build wired to an approved
Google **Desktop app** OAuth client, provide its client ID and protected downloaded JSON only while
packaging:

```bash
HERMES_GO_ACCOUNT_GATEWAY_URL=https://relay.example \
HERMES_GO_GOOGLE_MACOS_CLIENT_ID=example.apps.googleusercontent.com \
HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE=/protected/path/oauth-client.json \
npm run desktop:app
```

The optional secret-file input is read only while packaging; the JSON file itself is not copied, but
its client-secret value is embedded in the built app's `Info.plist` for the OAuth token exchange. It
is never written into source control, build logs, or diagnostics. Installed apps cannot keep this
value confidential, but the downloaded client JSON must still be treated as protected local build
input and never committed.

The app opens Google's account chooser in the system browser with PKCE S256, state, nonce, and a
temporary `127.0.0.1` callback. It never reads Chrome/Safari profiles or stores a Google access token.

For an isolated local deployment, the packaging script also accepts
`HERMES_GO_STORAGE_NAMESPACE`, `HERMES_GO_BUNDLE_IDENTIFIER`, and `HERMES_GO_DISPLAY_NAME`.
The storage namespace suffixes all three Desktop Keychain services, so a test installation cannot
read or overwrite the installed app's connection profile, account session, or Connector machine key.
Default packages leave the namespace empty and retain the existing identifiers and Keychain services.

The current local test package is version `0.3.0` (bundle build `4`). Binding confirmation remains
unavailable until a separately managed candidate has passed both key proof and health verification.

`desktop:app` and `desktop:dmg` use ad-hoc signing when `SIGNING_IDENTITY` is unset. A public build
requires a Developer ID Application identity and Apple notarization credentials; see
`docs/DESKTOP_PHASE0.md`.

The desktop icon is copied from the Android app's canonical
`android/app/src/main/ic_launcher-playstore.png`. Run `desktop/scripts/sync-app-icon.sh` after that
source changes. Packaging fails if the two files differ.

## Structure

- `Sources/HermesGoDesktopCore/` — status, compatibility inspection, OAuth/account transport,
  Keychain session/machine identity, probing, and redaction.
- `Sources/HermesGoDesktop/` — SwiftUI window, menu bar, account/devices, and compatibility screens.
- `Tests/` — compatibility, state-reduction, and security tests.
- `Packaging/` — static bundle metadata and the synchronized app icon.
- `scripts/` — local `.app` and `.dmg` packaging.

The non-overlapping Desktop refactor and its Cloud R0/R1 integration gates are tracked in
`docs/DESKTOP_R0_REFACTOR_PLAN.md`; local verification evidence is recorded in
`docs/DESKTOP_R0_TEST_RECORD.md`.
