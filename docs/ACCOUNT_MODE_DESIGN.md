# Hermes GO account-mode client design

Status: product and interaction proposal for review. This document does not authorize deployment or
change the current Hermes, Connector, Gateway, or Android runtime.

I0 engineering contracts: `ACCOUNT_MODE_API.md`, `ACCOUNT_MODE_SECURITY.md`,
`ACCOUNT_MODE_MIGRATION.md`, and `ACCOUNT_MODE_TEST_PLAN.md`.

## 1. Decision

The primary onboarding and connection model becomes **account-first**:

```text
Google identity -> Hermes GO account -> one active Desktop Connector -> one local Hermes
                                  \-> phone installation A
                                  \-> phone installation B
```

Google authenticates the person; the Hermes GO account service owns the binding. A matching email by
itself does not connect devices. The service must verify the provider token and map the verified
provider subject to an internal `account_id`.

V1 rules:

- One account has at most one active Desktop Connector binding.
- One Desktop Connector reaches one local Hermes instance.
- Any number of authorized phone installations may share that account and Hermes within bounded
  connection/rate limits.
- Each phone remains an independent installation: it has its own session, revocation, push token,
  delivery cursor, and local read state.
- A second Mac never silently replaces the first. Replacement requires recent reauthentication and
  explicit confirmation.
- Hermes source, credentials, configuration, and update process remain untouched.

The account model is deliberately provider-neutral internally. V1 may expose only Google, but stored
identity is `(provider, issuer, subject)` linked to an internal `account_id`; product data must not use
an email address as the primary key.

## 2. Vocabulary boundary

The UI must keep these concepts separate:

- **Hermes GO account**: the Google-backed owner of the Desktop binding and phone installations.
- **Desktop Connector**: the Mac-side background bridge registered to the account.
- **Hermes identity/profile**: an identity configured inside Hermes and selectable in the Android
  client. Account mode does not replace or rename this existing Hermes concept.
- **Phone installation**: one app installation authorized under the account. Two phones using the
  same Google account are still two independently revocable installations.

Avoid the ambiguous standalone label “身份” for the Google account. Use “Hermes GO 账号” and
“Hermes 身份” explicitly when both appear on the same screen.

## 3. Desktop client changes

### 3.1 Navigation

Keep the native menu-bar utility and current sidebar. Replace **Phone Pairing** with **Account &
Devices**:

1. Overview
2. Diagnostics
3. Logs
4. Account & Devices
5. Settings

The canonical Android app icon remains the app, sidebar, menu-bar, About, and package icon. Google
branding appears only on the official sign-in action.

### 3.2 First launch

The first screen has one primary action: **Continue with Google**. Supporting text explains:

- this Mac will become the account's one Desktop Connector;
- phones using the same account will discover it automatically;
- Google email, contacts, and Drive content are not requested;
- the local Hermes credential never leaves the Mac.

Selecting the action opens the system default browser. If that browser already has one or more
Google sessions, Google presents those accounts for direct selection and authorization instead of
asking the user to type the account and password again. Normal first sign-in explicitly permits
account selection so a browser's incidental default account cannot silently bind the wrong Hermes GO
account. A returning/re-authentication flow may supply Google's `login_hint` only as a convenience;
the Gateway still authorizes solely by the verified issuer and subject.

After authentication, Desktop runs a read-only preflight before binding:

1. Detect the existing Connector and whether it is running.
2. Probe the public Gateway.
3. Probe local Hermes without changing it.
4. Show the exact proposed binding name, for example “Living-room Mac mini”.
5. If this is a legacy Connector, present **Upgrade connection** as a separately confirmed migration
   with rollback. Do not start a second Connector.

### 3.3 Account & Devices

The normal state contains only information needed to understand ownership and access:

- signed-in account name and masked/normal email;
- current Desktop binding and last-seen state;
- Connector state;
- local Hermes reachability and observed version when safely available;
- authorized phone installations with device name, platform, last seen, and **Remove** action;
- **Unbind this Mac** as a destructive, confirmed action.

Removing a phone revokes only that installation. Unbinding the Mac revokes its Connector machine
credential and stops remote access, but must not delete, edit, stop, or restart Hermes.

### 3.4 Overview and menu bar

Keep the existing data-path topology:

```text
Desktop Agent -> Gateway -> local Hermes -> end-to-end check
```

Do not add “Google” as a permanent topology node: it is an authentication dependency, not part of
every Hermes request. Show account state near the page header and in layered diagnostics instead.

The menu bar continues to answer “is it working?” at a glance and adds the current account plus a
shortcut to Account & Devices. It must distinguish:

- signed in and connected;
- account session needs reauthentication while the machine credential remains valid;
- Connector offline;
- local Hermes unreachable;
- binding revoked or replaced.

### 3.5 Second-Mac conflict

If another Desktop signs into an already-bound account:

- show the existing Mac display name and last seen;
- leave the original binding active;
- require recent Google reauthentication;
- explain that replacement revokes the old Connector but does not delete Hermes data;
- require an explicit **Verify and replace** action;
- notify existing phone installations after successful replacement.

There is no automatic “latest login wins” behavior.

### 3.6 Legacy compatibility

The current App Token/QR implementation becomes an **Advanced > Legacy connection** entry during the
migration window. It is hidden from normal onboarding but remains available for rollback and old
clients. Desktop must label which mode is active and must never run account and legacy Connector
instances in parallel.

## 4. Android client changes

### 4.1 First launch

Replace Relay URL, App Token, and QR as the default setup with one **Continue with Google** action.

Credential Manager first requests Google accounts already authorized for Hermes GO. With exactly
one eligible credential and no pending consent, Android may use Google's auto-select behavior; with
multiple eligible accounts it shows the native account chooser. If no previously authorized account
exists, retry with the filter disabled so the user can choose any Google account already present on
the phone or add another account. Never select by comparing email strings.

After sign-in:

- if an active Desktop binding exists, connect automatically and open Sessions;
- if no Desktop is bound, show “No Desktop connected yet”, the signed-in account, and a passive
  retry/listening state plus instructions to open Hermes Go Desktop;
- if the binding exists but Connector is offline, preserve the account session and show the specific
  offline state instead of returning to login;
- if the account session is invalid, ask for reauthentication without deleting local preferences or
  conversation navigation state.

### 4.2 Existing app shell

Do not redesign chat, sessions, projects, models, cron, updates, or the composer for account mode.
Their server data still comes from the bound Hermes.

The card page keeps the existing **current Hermes identity** card and changes the existing
**Remote device** stat cell into the single entry point for the bound Hermes. Its value is the Mac
display name and its subline summarizes Hermes connectivity. Tapping the cell opens **Remote device**
details with the Mac, Connector, Hermes, Gateway, end-to-end state, diagnostics, and legacy connection
tools. There is no separate “Connection & devices” row or Settings entry.

Do **not** add the Google account, email, or an account card to the card page: sign-in is normally a
one-time action and does not deserve permanent space in the frequently used navigation surface. This
also prevents the Hermes GO account from being confused with Hermes profiles.

Settings changes:

- add a normal Settings account row, similar to the low-frequency account placement in ChatGPT;
- open an account-only detail from that row to show the signed-in account and this phone installation;
- add **Sign out on this phone**, which revokes only this installation;
- remove the old **Server & token** row from normal Settings;
- retain Diagnostics and use the same layered status language as Desktop.

Remote-device details changes:

- show the single bound Mac/Hermes rather than a device picker or device list;
- show Connector, Hermes, Gateway, and end-to-end status as one readable path;
- link to Diagnostics for actionable failures;
- put manual URL/Token configuration under **Legacy connection** during migration;
- when no Desktop is bound, the same destination shows the no-binding state and how to open Desktop.

The account appears outside Settings only when it requires action: first sign-in, expired/revoked
authorization, or an explicit account/binding warning. A normal healthy account does not add a badge,
avatar, email, or persistent border/card to Sessions or the card page.

### 4.3 Multiple-phone behavior

Phones share Hermes server data but not phone-local state:

- signing out or revoking phone A must not sign out phone B;
- push registration and notification permissions are per installation;
- delivery cursors and notification acknowledgements are per installation;
- phone-local read/unread presentation is not cleared by another phone;
- an active command from one phone is visible to the other through the existing read-only lifecycle
  observation where Hermes supports it;
- V1 does not promise simultaneous collaborative editing or ownership transfer of an interactive
  approval created by another transport.

### 4.4 Error presentation

The Android health strip and recovery sheet must state the failed layer, not collapse everything into
“cannot connect”:

- **Account needs sign-in** -> Continue with Google.
- **Gateway unavailable** -> retry/check network.
- **Desktop Connector offline** -> open Desktop on the Mac; keep account signed in.
- **Hermes unavailable on Mac** -> inspect Desktop diagnostics; do not request a new login.
- **Binding replaced/revoked** -> explain the new binding or ask the user to authorize this phone.

Detailed logs stay on Desktop. Android exposes a small, shareable redacted diagnostic summary.

## 5. Shared state model

Both clients render the same product states, using platform-native controls:

| State | Desktop | Android |
| --- | --- | --- |
| Signed out | Google sign-in | Google sign-in |
| Account, no binding | Offer to bind this Mac | Wait for Desktop; retry automatically |
| Bound, healthy | Account/devices and green path status | Open Sessions; show bound Mac as connected |
| Connector offline | Account healthy, Connector failed | Keep account; show Connector offline |
| Hermes unreachable | Connector may be healthy; Hermes failed | Keep account; direct user to Desktop diagnostics |
| Account reauth needed | Reauthenticate management session; machine credential may continue | Reauthenticate this phone session |
| Second Mac requests binding | Explicit replacement flow | Existing binding remains until confirmed |
| Phone revoked | Remove it from device list | Return that phone to sign-in only |
| Desktop replaced | Old machine shows revoked | Phones switch only after server commits the new binding |

Status color is supplemental: every status includes a text label and recovery action. Historical log
warnings never override current reachability.

## 6. Authentication and token design visible to clients

- macOS uses the system browser, PKCE S256, state, and a loopback callback on an ephemeral
  `127.0.0.1` port for the installed-app flow.
- macOS reuses browser-side Google sessions only through Google's own account chooser/consent page;
  Hermes GO never reads browser cookies, passwords, or a local Chrome profile.
- Android uses Credential Manager Sign in with Google, preferring previously authorized accounts
  and allowing auto-select only when Google reports exactly one action-free credential.
- The backend verifies provider signature, issuer, audience, and expiry, then issues Hermes GO
  credentials.
- Desktop stores an account management session and an independently revocable, device-bound
  Connector credential/key pair in Keychain.
- Android stores its own refresh/session material in encrypted platform storage.
- Google access tokens are never used as Gateway App Tokens or Connector credentials.
- Interactive Google sign-in bootstraps or reauthenticates access; it is not required for every
  background reconnect.

References: [Google OAuth for installed apps](https://developers.google.com/identity/protocols/oauth2/native-app),
[Google backend ID-token verification](https://developers.google.com/identity/sign-in/android/backend-auth),
and [Android Credential Manager Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg).

## 7. Migration design

Account mode is introduced without a flag day:

1. Gateway accepts both existing App Token sessions and new account sessions.
2. Desktop and Android ship account UI while legacy mode remains available.
3. The user signs into both clients and confirms the existing Mac binding.
4. Desktop upgrades the Connector credential through a validated, rollback-capable transition.
5. Existing App Token clients continue for the documented grace period.
6. After measured adoption and a tested rollback path, legacy onboarding can be removed in a later
   product decision. Legacy server acceptance is not removed in the same release as the UI change.

Hermes sessions and files do not migrate because they remain on the same Hermes instance.

## 8. Acceptance and test matrix

Design and implementation are not complete until these cases are automated where possible and run on
real devices where required:

- same Google account on Desktop and phone connects without URL, Token, or QR input;
- phone signed in before Desktop transitions from “no Desktop” to connected without reinstalling;
- two physical phones connect to the same Hermes and can be revoked independently;
- logging out phone A leaves phone B and the Desktop Connector working;
- per-installation notification cursor and local read state do not leak between phones;
- a second Mac cannot steal the binding without reauthentication and explicit confirmation;
- failed Mac replacement leaves the original Connector active;
- successful replacement revokes the old machine credential and does not modify Hermes;
- provider/backend temporary outage does not unnecessarily stop an already-authorized Connector;
- account, Gateway, Connector, Hermes, and end-to-end failures render as distinct states;
- tokens, auth codes, cookies, and provider responses are absent from UI, logs, diagnostics, and crash
  reports;
- legacy Android configuration keeps working throughout the compatibility window;
- legacy-to-account Connector upgrade validates health and automatically rolls back on failure;
- app restart, Mac restart, phone process death, token rotation, and clock skew recover safely;
- accessibility: 48dp mobile targets, native macOS keyboard navigation, readable status without color,
  bilingual copy, dark mode, large text, and offline screen-reader announcements.

## 9. Recommended implementation slices

The executable backlog, estimates, dependencies, exit gates, release sequence, and go/no-go
checkpoints are maintained in `ACCOUNT_MODE_IMPLEMENTATION_PLAN.md`.

1. Account protocol, threat model, provider-neutral data model, and new error codes.
2. Backend Google verification plus Hermes GO session/refresh credentials.
3. macOS sign-in and Account & Devices UI without Connector takeover.
4. Android sign-in and no-binding/healthy/offline state shell.
5. One-account/one-Connector binding, phone installation registration, revoke, and replacement.
6. Legacy-to-account migration with Connector validation and rollback.
7. Two-phone, second-Mac, restart, failure, security, and accessibility release gates.

This order keeps the existing Connector and Hermes path intact until the account control plane is
independently testable.
