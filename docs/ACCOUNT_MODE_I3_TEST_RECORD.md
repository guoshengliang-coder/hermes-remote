# Account mode I3 Desktop test record

Date: 2026-09-02
Status: local I3-A account-client slice complete; live Google/backend and I3-B gates pending.

## Scope completed

- Capability-gated Desktop account states; default builds and disabled Gateways remain on legacy.
- System-browser Google authorization with PKCE S256, 256-bit state/nonce, explicit account chooser,
  an ephemeral `127.0.0.1` callback, strict callback path/origin/state validation, cancellation, and
  timeout.
- Google code exchange returns only the ID token to the account proof flow; Google access tokens are
  neither modeled nor persisted.
- Hermes GO account session and Ed25519 Connector machine identity live in separate this-device-only
  Keychain services.
- Account access refresh, `/v2/account`, installation list, connector-binding status, single-phone
  removal, and Desktop-session-only sign-out.
- Refresh/removal/sign-out idempotency keys are written to the account-session record before network
  transmission and reused after a lost response.
- SwiftUI navigation now exposes Account & Devices, account status in Overview/menu bar/Settings,
  binding state, phone list, removal confirmation, and redacted structured errors.
- Existing URL/App Token/QR setup is preserved under Advanced: Legacy connection.

## Safety boundary

I3-A does not create or confirm a binding, replace/unbind a Connector, write Connector configuration,
start or stop a process, open a Mac inbound service beyond the short-lived loopback OAuth callback,
or modify Hermes. Both Gateway account flags remain off and no deployment occurred.

## Automated evidence

| Command/gate | Result | Coverage |
| --- | --- | --- |
| `npm run desktop:test` | Pass | 38 Desktop core tests after I3-A |
| OAuth loopback focused test | Pass | Real ephemeral IPv4-loopback listener and callback |
| `npm run desktop:assets:test` | Pass | Canonical Android/Desktop icon equality |
| `npm run desktop:app` | Pass | Release Swift build, bundle assembly, and strict ad-hoc codesign |
| `npm test` | Pass | Existing Protocol, Connector, Gateway, release-server, and script compatibility |
| `git diff --check` | Pass | Patch formatting |

The ad-hoc packaged app was launched locally after the gate. Its accessibility tree exposed Account &
Devices, the account-layer status, stable `HR-ACCOUNT-002` failure copy against the currently
unavailable account surface, and the compatibility label. The development host had no legacy
Connector before or after the run, and the app created no Connector process. The test app was then
closed with no process left behind.

## Still required

- Approved Google macOS Desktop OAuth client configuration and consent-screen setup.
- Live Google account chooser tests with zero, one, and multiple browser sessions.
- Development Gateway with account/binding flags enabled and disposable PostgreSQL.
- Real Keychain persistence/restart test in the packaged app without exposing credentials.
- Dark/light mode, VoiceOver, keyboard, and long-name inspection of every account state.
- I3-B recent reauthentication, replacement, unbind, and conflict confirmations.
- Target Mac mini installation test proving the legacy Connector PID, launch count, and traffic stay
  unchanged.
