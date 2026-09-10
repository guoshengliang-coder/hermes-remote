# Account platform E8 Android test record

Date: 2026-09-08 (updated 2026-09-10)

Scope: local Android email-code account foundation and challenge lifecycle, owned/shared device selection, and conversation affinity

Production changes: none

## Implemented boundary

- Account capability discovery and email challenge/exchange use the `/v2` contract with a stable,
  random Android installation ID.
- Account access/refresh credentials, selected opaque device ID, refresh replay key, and pending
  exchange replay key are encrypted beside—but independently clearable from—the legacy Relay/App
  Token configuration.
- Refresh rotates within a mutex and persists the idempotency key before network I/O. Invalid or
  revoked refresh families clear only the account record and atomically persist a reauthentication
  gate. The gate survives process death and blocks REST, account lifecycle, WebSocket, startup,
  share, and new-chat fallback to retained legacy credentials. A successful account exchange,
  explicit phone sign-out, or explicit Legacy entry clears it.
- The last account Gateway origin survives credential invalidation without a token. Email capability
  discovery and challenge delivery prefer that origin over any unrelated retained legacy Relay.
- An encrypted explicit-transport choice separates compatibility from automatic fallback. A logged-in
  account with no selected Mac blocks device REST, WebSocket creation, startup, and retained App Token
  use until the user selects a Mac or explicitly enters Legacy connection. That Legacy choice survives
  process death; account login or device selection restores account transport. The phone lifecycle
  inbox remains account-routed while only the Mac selection is missing.
- Account/device repair is layered over the existing navigation stack. A successful repair removes
  the setup screens and restores the previous chat/page; a genuinely fresh or missing stack enters
  Chats instead.
- Terminal account WebSocket handshakes stop retrying after classification. `401` clears only the
  account session and requires sign-in; `404` clears the selected device only when that exact Mac was
  rejected. A revoked historical/shared Mac restores transport to the still-valid default instead of
  deleting the default selection. Gateway-initiated 4403 is acknowledged and permits exactly one
  classification handshake; an unavailable classification also stops. `HR-AUTH-006` remains a narrow
  recent-authentication requirement.
- Account-routed Hermes REST responses share a single invalidation boundary. An unexpired access
  token rejected with `401` or `HR-AUTH-003/004/005` still persists the sign-in gate; only an explicit
  `HR-BIND-011` repairs the request's tagged Mac route. An ordinary `404` changes nothing, and loss of
  a historical/shared Mac restores the valid default without clearing it.
- Authenticated Account API calls from the account/device screen now enter that same recovery model.
  Session-family rejection persists sign-in repair and stops the active transport; rejection of the
  current Mac clears only its selection and stops the stale route. `HR-AUTH-006` preserves the account,
  selection, and connection while displaying the registered bilingual recent-authentication copy.
- Startup treats only expired/revoked/reused account session families as a sign-in repair. Refresh
  rate limiting, disabled accounts, temporary account service failure, and an offline Connector keep
  their registered `HR-AUTH-007`, `HR-ACCOUNT-001`, `HR-ACCOUNT-002`, and `HR-CONN-005` recovery states.
- Default onboarding uses email + six-digit code. Legacy URL/Token/QR remains explicitly reachable.
- Email challenge expiry and resend cooldown derive from the server's absolute timestamps. The UI
  blocks early resend and locally known-expired submission, replaces the old challenge after a
  successful resend, and runs its countdown only while visible. Process recovery restores only a
  valid encrypted challenge and never restores the six-digit input; expired or malformed timing
  clears the challenge and presents `HR-AUTH-009`.
- A retryable error repeats the failed account operation instead of merely refreshing the page:
  initial email delivery requests a new challenge, code exchange keeps its pending replay key, and
  Mac probing keeps its original target. Non-retryable errors remain inert.
- A sole available Mac is selected and probed automatically; two or more owned/shared Macs require
  an explicit choice.
- A signed-in account with no Mac keeps discovering every five seconds only while the Remote devices
  page is visible. Leaving the page cancels the poll; when the first sole Mac appears, the existing
  cloud-default mutation and explicit end-to-end probe automatically connect it.
- If refresh shows the selected Mac was revoked/removed, one sole remaining Mac is selected and
  probed without an intermediate duplicate reconnect. Zero or multiple remaining Macs clear the
  stale route and wait for discovery/choice. A failed replacement never becomes a local selection;
  a failed ordinary switch preserves the still-valid current Mac.
- Settings contains account/this-phone session management only. The card's existing Remote device
  cell opens the only owned/shared Mac selector and compatibility/diagnostics entry.
- A device becomes locally active only after the default-device mutation and explicit-device
  `/api/status` probe succeed. Account REST and WebSocket then use the selected `/v2/devices/{id}`
  paths and `Authorization: Bearer`. A dedicated client without the legacy dashboard cookie jar or
  authenticator is mandatory, so account traffic never also sends an App Token or dashboard cookie.
- Android now follows the binding capability instead of assuming the multi-device contract. When
  `supportsDeviceSelection` is absent/false, it reads `/v2/connector-binding`, maps the sole bound Mac
  into the existing presentation model, probes Bearer `/api/status`, and then uses `/api/*` plus
  `/api/ws`; it never calls the disabled `/v2/devices*` endpoints. Explicit device routes remain in
  place for a future capability-enabled multi-device rollout.
- A working Legacy installation that opts into email login persists the account as
  `ACCOUNT_PENDING` and keeps Legacy REST/WebSocket/startup authoritative until the account Mac passes
  its Bearer probe. Device selection and route mode then commit together before one reconnect. The
  pending state survives process restart; a missing Mac or failed probe does not disrupt Legacy, and
  an invalid pending session returns to Legacy. Invalidation after account transport is active keeps
  the existing fail-closed reauthentication behavior.
- “Sign out on this phone” calls the current-installation revocation endpoint and retains legacy
  credentials.
- The durable lifecycle inbox is account/phone scoped rather than Mac scoped. Page and delivery-ACK
  requests go directly to the Relay-owned `/api/mobile/events` surface with the phone Bearer, work
  before any Mac is selected, and cannot inherit a device prefix or legacy token. Local cursors use
  distinct hashed Gateway/account/installation scopes while the legacy cursor keeps its existing
  storage key; a mid-sync account change stops before consume, ACK, or cursor commit.
- Sessions returned by an account device are tagged with that opaque device ID and persist an
  encrypted mapping scoped by account, profile, and stored session ID. Navigation, startup recovery,
  search hits, share-created sessions, runtime state, read/pin keys, and notification actions carry
  the same device identity.
- The selected/default Mac remains the route for lists, search, and new conversations. Opening an
  existing conversation temporarily routes the foreground WebSocket plus explicit REST history and
  metadata to its originating Mac; returning to Chats restores the selected route without changing
  the cloud default.
- Equal profile/session IDs on different Macs are separate runtime and notification identities.
  Rename/archive/delete use the row's explicit device; a successful delete removes its stored
  affinity. Revoked historical access presents `HR-BIND-011` and never falls through to an equal ID
  on the default Mac.

## Automated evidence

| Check | Result | Coverage |
|---|---|---|
| `:app:compileDebugKotlin` | Pass | Hilt graph, Compose and Android account sources compile |
| Focused account/API/transport/startup tests | Pass, 109 tests | Capabilities, singular and explicit binding lookup/routing, pending Legacy migration, email exchange binding, structured errors, encrypted-state model, refresh rotation/revocation, Bearer-only dedicated REST/WebSocket clients, installation-scoped lifecycle inbox/cursor, automatic discovery, revoked-device handoff, legacy preservation, bilingual presentation |
| `:app:compileDebugAndroidTestKotlin` | Pass | Encrypted-store persistence regression compiles for device execution |
| `:app:testDebugUnitTest :app:assembleDebug` | Pass, 1,382 tests | Full Android JVM baseline, Hilt graph, debug signing check, and debug build |
| Account screen Robolectric UI/recording | Pass | Email-first light screen, OTP expiry/resend-cooldown light screen, no-device auto-discovery light screen, plus owned/shared dark screen at 1.3 font scale; locally inspected |

The first E8-A focused run exposed an invalid “healthy device” fixture whose Connector defaulted offline;
the fixture was corrected to model Connector and Hermes as online, then passed. The first full run
also exposed eager AndroidKeyStore access while Robolectric created the Hilt application; account
storage initialization was moved behind first account observation, the affected UI tests passed,
and the complete 906-test/build baseline then passed. These were test-isolation corrections, not
runtime behavior relaxations. The first E8-B full run exposed passive list initialization opening
AndroidKeyStore under Robolectric and one stale device-less lifecycle assertion; route inspection was
made non-eager, the assertion was corrected, representative UI tests passed, and the full baseline
then passed. A later metadata-route/revocation regression was added and the final baseline rerun. The
first E8-E resend test exposed stale countdown state after the server returned a replacement challenge;
the new absolute deadlines are now applied immediately, independent of page visibility, and the focused
suite plus 931-test baseline pass. The first action-specific retry run retained one stale non-retryable
probe expectation and one unsupported mock of the suspend exchange default bridge; the former was moved
to the retryable probe case and the latter removed in favor of operation-dispatch coverage without
relaxing runtime behavior. The installation-scoped lifecycle routing/cursor slice passed its focused
suite on the first run. The first combined startup-copy UI test shared a manual animation clock across
two sequential error states and produced a false visibility failure; the states were isolated into
independent render tests. The reauthentication-gate slice first exposed one strict startup mock that
did not declare the new gate query; the healthy-account fixture now explicitly returns false. The
focused suite then passed, and that baseline reached 946 tests.

The reauthentication-closure slice found one normalization-only fixture mismatch (the production
store correctly retains the canonical URL without a trailing slash); the expectation was corrected,
the 48 focused tests passed, and the complete baseline advanced to 948 tests.

The runtime-revocation slice added terminal account WebSocket handshake classification, verified no
backoff retry after a `401`, separated default-device and historical-device `404` recovery, and kept
`HR-AUTH-006` from clearing a valid account session. Its first close-cycle test exposed that the
client did not acknowledge a server-initiated graceful close, so `onClosed` never ran; the client now
completes that handshake and the test proves a failed classification stops after two total requests.
The focused tests and the 952-test baseline pass.

The REST-revocation slice then centralized response classification for every account-routed Hermes
request. Its tests prove `HR-AUTH-006` and ordinary `404` preserve state, an unexpired bearer rejected
with `401` persists reauthentication, historical-device `HR-BIND-011` restores the default route, and
default-device `HR-BIND-011` clears only that selection. The complete baseline now passes 954 tests.

The explicit-transport slice closes the remaining compatibility ambiguity: a valid account without a
Mac cannot silently use retained legacy credentials, while a deliberate Legacy entry persists and can
be reversed by selecting an account Mac. Startup, REST, WebSocket endpoint selection, encrypted-store
compilation, process-recreated manager behavior, and terminal local WebSocket repair gates are covered.
The baseline advances to 958 tests.

The Account API recovery slice applies the same session-family and device-revocation classifier to
the account/device control surface and immediately retires an active route when repair becomes
mandatory. Its regressions also prove that operation-scoped `HR-AUTH-006` preserves the current
transport and renders its canonical Chinese and English explanation. The baseline advances to 961 tests.

The 2026-09-10 singular-binding alignment adds capability-directed `/v2/connector-binding` discovery,
unprefixed Bearer REST/WebSocket routing, and a durable pending-activation handoff from Legacy. Its
focused 109-test set passes. The first compilation exposed a KDoc token parsed as a nested comment;
the wording was corrected. The first full login-path regression also exposed that local JVM/device
environments can provide null Android manufacturer/model values before the exchange call; the display
name now safely falls back to `Android phone`. The final full baseline passes 1,382 tests, the debug
build/signing check passes, and the Android instrumentation source set compiles.

## 2026-09-10 Android 0.1.113 single-phone physical acceptance

Android 0.1.113 (version code 114) was published from merged commit
`cabee2b2a258517062811a082e0514db9678dda3`. Its Android release workflow completed successfully, and
the public internal-channel index registered the 31,214,112-byte APK with SHA-256
`ec332db45c53e8430656cb1b4fd8ee46cd6f5e2df383f5f209e01993221577ae` as the latest version.

The project owner installed 0.1.113 on a physical Android phone and confirmed that the email-account
path could use the Mac already bound through Hermes Go Desktop normally. This closes the primary
single-phone install, account sign-in, binding discovery, and ordinary-use acceptance path. The
confirmation does not by itself cover activation interrupted by process death, accessibility,
multi-phone or multi-account isolation, sharing, revocation, or fault-injection scenarios.

## Still open before E8 release

- Emulator inspection could not run because this host's configured SDK has no Emulator package.
  The lifecycle script now rejects a missing emulator binary and targets only an `emulator-*`
  serial, preventing a connected physical phone from being mistaken for the emulator. Physical
  TalkBack testing remains open. The encrypted-store gate test compiled but was not run on a device
  in this iteration.
- App-process restart while account activation is still pending remains an explicit recovery test;
  the completed ordinary-use run must not be treated as evidence for that interruption point.
- Two accounts, two phones, owned + shared Macs, revocation, network loss, refresh expiry, and Mac
  restart end-to-end acceptance.
