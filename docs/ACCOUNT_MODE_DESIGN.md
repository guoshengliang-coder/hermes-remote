# Hermes GO account-mode client design

Status: accepted current interaction contract. Its implementation remains capability-gated; this
document does not authorize deployment or production enablement.

Rollout update (2026-09-08): the accepted first release is email-code-only and supports explicit
selection among up to three owned Macs plus shared Macs. Google/Apple remain future providers behind
independent capabilities; historical Google-first and one-Mac decisions live only in the implementation
history, not in this shipping interaction contract.

I0 engineering contracts: `ACCOUNT_MODE_API.md`, `ACCOUNT_MODE_SECURITY.md`,
`ACCOUNT_MODE_MIGRATION.md`, and `ACCOUNT_MODE_TEST_PLAN.md`.

## 1. Decision

The primary onboarding and connection model becomes **account-first**:

```text
verified email OTP -> Hermes GO account -> owned Mac A Connector -> local Hermes A
                                      \-> owned Mac B Connector -> local Hermes B
                                      \-> phone installation A/B
                                      \-> Macs shared by another account
```

The mail challenge verifies control of the normalized mailbox; the Hermes GO account service owns
every binding and share. Equal email text from a future OAuth profile does not connect or merge
accounts. Authorization always resolves a verified external identity to an opaque internal
`account_id`.

First-release rules:

- One account has at most three independently revocable owned Desktop Connector bindings.
- One Desktop Connector reaches one local Hermes instance.
- Authorized phone installations explicitly select one accessible owned/shared Mac when more than
  one is available; no display name participates in authorization.
- Whole-device sharing grants fixed `operator` access to one named Mac. A grantee can use that
  Hermes service but cannot re-share, rotate, unbind, rename, or manage the owner's account.
- Each phone remains an independent installation: it has its own session, revocation, push token,
  delivery cursor, and local read state.
- A new Mac consumes a separate owned-device slot and never replaces another Mac merely by signing
  in. Replacing or rotating an existing binding remains scoped and explicitly confirmed.
- Hermes source, credentials, configuration, and update process remain untouched.

The account model is provider-neutral internally. Stored identity is `(provider, issuer, subject)`
linked to an internal `account_id`; the verified email provider uses its canonical mailbox subject,
while later OAuth providers use their issuer subject. Product relationships never use display email
as a primary key.

## 2. Vocabulary boundary

The UI must keep these concepts separate:

- **Hermes GO account**: the provider-neutral owner of Desktop bindings, phone/browser installations,
  and sharing relationships.
- **Desktop Connector**: the Mac-side background bridge registered to the account.
- **Hermes identity/profile**: an identity configured inside Hermes and selectable in the Android
  client. Account mode does not replace or rename this existing Hermes concept.
- **Phone installation**: one app installation authorized under the account. Two phones using the
  same verified email account are still independently revocable installations.

Avoid the ambiguous standalone label “身份” for the login account. Use “Hermes GO 账号” and
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

The canonical Android app icon remains the app, sidebar, menu-bar, About, and package icon. Future
provider branding may appear only on that provider's official capability-gated action.

### 3.2 First launch

The first screen has email input followed by one primary action: **Send sign-in code**. After a
challenge is accepted, the same surface requests the six-digit code and offers **Verify and sign in**.
Supporting text explains:

- this Mac can become one of the account's owned Hermes devices;
- phones using the same verified account will discover it without a Relay URL or App Token;
- the code is single-use and Hermes GO never asks for the mailbox password;
- the local Hermes credential never leaves the Mac.

The challenge metadata and exchange idempotency key may persist in the account-session Keychain so a
lost response is safely retried; the six-digit code remains only in view memory. Google/Apple controls
and browser launch are absent from the first-release surface.

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
- all owned Mac bindings, their last-seen state, and an explicit marker for this Mac;
- all Macs shared with this account, with owner/operator access labels;
- Connector state;
- local Hermes reachability and observed version when safely available;
- authorized phone installations with device name, platform, last seen, and **Remove** action;
- per-owned-device invite/cancel/revoke controls with the whole-device disclosure and 5/10 limits;
- **Leave shared device** for operator grants accepted by this account;
- **Unbind this Mac** as a destructive, confirmed action.

Removing a phone first re-verifies the current account identity, then revokes only that installation.
Unbinding the Mac revokes its Connector machine
credential and stops remote access, but must not delete, edit, stop, or restart Hermes.

Permanent Cloud-account deletion is a different capability-gated danger-zone action. It requires
typed `DELETE`, explicit acknowledgement, and fresh `account.delete` reauthentication. The UI must
explain that every Cloud session, binding, and share ends immediately; Cloud personal data is cleaned
after 30 days; the old account is not recoverable; and local Hermes data on each Mac is never deleted.
The action is absent while the independent server capability is off. After commit, clients say that
deletion was “submitted” or is “in progress” until the 30-day cleanup completes; they must not claim
that Cloud data has already been erased.

### 3.4 Overview and menu bar

Keep the existing data-path topology:

```text
Desktop Agent -> Gateway -> local Hermes -> end-to-end check
```

Do not add the email provider or any future OAuth provider as a permanent topology node: authentication
is not part of every Hermes request. Show account state near the page header and in layered diagnostics
instead.

The menu bar continues to answer “is it working?” at a glance and adds the current account plus a
shortcut to Account & Devices. It must distinguish:

- signed in and connected;
- account session needs reauthentication while the machine credential remains valid;
- Connector offline;
- local Hermes unreachable;
- binding revoked or replaced.

### 3.5 Multiple owned Macs

When another Desktop signs into an account with available capacity:

- show every existing owned Mac separately and label the local Mac;
- create/confirm only the local Mac's new binding slot;
- leave every existing binding and its selected phone traffic active;
- require phones to select by opaque device ID when several Macs are accessible;
- reject a fourth owned Mac without revoking or replacing any existing binding.

Replacing or rotating a binding is a separate scoped operation for the selected Mac. It requires
recent authentication, names the exact binding being changed, validates proof/health before commit,
and never deletes Hermes data. There is no automatic “latest login wins” behavior.

### 3.6 Legacy compatibility

The current App Token/QR implementation becomes an **Advanced > Legacy connection** entry during the
migration window. It is hidden from normal onboarding but remains available for rollback and old
clients. Desktop must label which mode is active and must never run account and legacy Connector
instances in parallel.

## 4. Android client changes

### 4.1 First launch

Replace Relay URL, App Token, and QR as the default setup with **email + six-digit verification
code**. The first account release has no Google or Apple controls. Challenge metadata and the
exchange idempotency key survive process death in encrypted storage; the typed code does not enter
logs or diagnostics. Legacy setup remains one explicit compatibility action.

After sign-in:

- if one accessible Desktop is available, select/probe it and open Sessions; if several owned/shared
  Macs are available, require an explicit choice rather than selecting by display name;
- if no Desktop is bound, show “No Desktop connected yet”, the signed-in account, and a passive
  retry/listening state plus instructions to open Hermes Go Desktop;
- if the binding exists but Connector is offline, preserve the account session and show the specific
  offline state instead of returning to login;
- if the account session is invalid, ask for reauthentication without deleting local preferences or
  conversation navigation state.

### 4.2 Existing app shell

Do not redesign chat, sessions, projects, models, cron, updates, or the composer for account mode.
Their server data still comes from the bound Hermes.

The card page keeps the existing **current Hermes identity** card and uses the existing
**Remote device** stat cell as the single device entry. Its value is the selected Mac display name
and its subline summarizes Hermes connectivity. Tapping opens **Remote devices**, which lists owned
and shared Macs with access labels, Connector/Hermes/end-to-end state, diagnostics, and legacy
connection tools. There is no separate “Connection & devices” Settings entry.

Do **not** add a Hermes GO account card, email address, or provider identity to the card page: sign-in is normally a
one-time action and does not deserve permanent space in the frequently used navigation surface. This
also prevents the Hermes GO account from being confused with Hermes profiles.

Settings changes:

- add a normal Settings account row, similar to the low-frequency account placement in ChatGPT;
- open an account-only detail from that row to show the signed-in account and this phone installation;
- add **Sign out on this phone**, which revokes only this installation;
- remove the old **Server & token** row from normal Settings;
- retain Diagnostics and use the same layered status language as Desktop.

Remote-device details changes:

- show the account's owned and shared Macs and require explicit selection when more than one exists;
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

- **Account needs sign-in** -> enter email and request a new six-digit code.
- **Gateway unavailable** -> retry/check network.
- **Desktop Connector offline** -> open Desktop on the Mac; keep account signed in.
- **Hermes unavailable on Mac** -> inspect Desktop diagnostics; do not request a new login.
- **Binding replaced/revoked** -> explain the new binding or ask the user to authorize this phone.

Detailed logs stay on Desktop. Android exposes a small, shareable redacted diagnostic summary.

## 5. Shared state model

Both clients render the same product states, using platform-native controls:

| State | Desktop | Android |
| --- | --- | --- |
| Signed out | Email plus six-digit code | Email plus six-digit code |
| Account, no accessible device | Offer to bind this Mac | Wait for an owned/shared Desktop; retry automatically |
| Bound, healthy | Account/devices and green path status | Open Sessions; show bound Mac as connected |
| Connector offline | Account healthy, Connector failed | Keep account; show Connector offline |
| Hermes unreachable | Connector may be healthy; Hermes failed | Keep account; direct user to Desktop diagnostics |
| Account reauth needed | Reauthenticate management session; machine credential may continue | Reauthenticate this phone session |
| Additional owned Mac | Add a separate slot when below three; never overwrite another Mac | Existing choices remain; new Mac appears after activation |
| Shared Mac added/removed | Owner/grantee controls reflect the exact device | Device appears/disappears without affecting owned Macs |
| Phone revoked | Remove it from device list | Return that phone to sign-in only |
| Desktop replaced | Old machine shows revoked | Phones switch only after server commits the new binding |

Status color is supplemental: every status includes a text label and recovery action. Historical log
warnings never override current reachability.

## 6. Authentication and token design visible to clients

- Desktop and Android request an email challenge bound to the platform and stable client installation,
  then exchange the six-digit code with a caller-stable idempotency key.
- The backend normalizes and verifies the mailbox challenge, enforces expiry/attempt/cooldown/rate
  limits, and only then issues Hermes GO credentials.
- Challenge and retry metadata may use protected platform storage; the typed code never enters durable
  storage, logs, diagnostics, or crash reports.
- Desktop stores an account management session and an independently revocable, device-bound
  Connector credential/key pair in Keychain.
- Android stores its own refresh/session material in encrypted platform storage.
- No mailbox password or provider access token becomes a Gateway App Token or Connector credential.
- The retained future Google implementation uses system-browser PKCE on macOS and Credential Manager
  on Android, but remains absent unless its independent provider capability is explicitly enabled.

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

- the same verified email account on Desktop and phone discovers accessible Macs without URL, Token,
  or QR input;
- phone signed in before Desktop transitions from “no Desktop” to connected without reinstalling;
- two physical phones connect to the same Hermes and can be revoked independently;
- logging out phone A leaves phone B and the Desktop Connector working;
- per-installation notification cursor and local read state do not leak between phones;
- three owned Macs remain independently usable; a concurrent fourth is rejected without affecting
  existing Connectors;
- binding replacement/rotation affects only its selected Mac, and failure leaves the old generation
  active;
- an owner can share one whole Mac with account B, then revoke only B without affecting either
  account's other Macs;
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
2. Backend email OTP verification plus Hermes GO session/refresh credentials; retain Google behind
   its independent future-provider flag.
3. macOS email sign-in and Account & Devices UI without Connector takeover.
4. Android email sign-in and no-device/selection/healthy/offline state shell.
5. Up-to-three owned Connector bindings, phone installation registration, explicit device selection,
   revoke, scoped replacement, and whole-device sharing.
6. Legacy-to-account migration with Connector validation and rollback.
7. Two-phone, multi-Mac/share, restart, failure, security, and accessibility release gates.

This order keeps the existing Connector and Hermes path intact until the account control plane is
independently testable.
