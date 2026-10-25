# HG-237 sending recovery and Android account-only transport

Approved by the owner in the dispatched conversation on 2026-10-25. One branch and PR cover
Android recovery, retirement of Android legacy connections, and bounded Gateway/Connector RPC
diagnostics. Production deployment and Android release allocation are separate gates.

## Evidence and limits

The HONOR report used Android 0.1.180 and account device WebSocket routing. At 19:07:06 CST the
phone attempted `prompt.submit`; its response timed out after 60 seconds. Reconciliation then
repeatedly rejected history with `userTurns 10<11`. On the actual M4, the existing Hermes acceptance
log and a read-only query of that conversation contain the 49-character prompt only at 19:12:07;
Hermes finished that turn at 19:12:54. Gateway and Managed Connector tunnel identifiers agree.
There was no Connector restart/heartbeat timeout observed in that window. The original stalled
hop is unproven: existing logs track create/switch RPCs, not prompt submission.

The Android release source configures ping only on the legacy client; account WebSockets use a
separate client without ping. This explains a liveness detection gap, not the initial interruption.
Submission response timeout is treated as ambiguous, but ordinary post-submission disconnects can
be treated as retryable failures. Pending local turns also block authoritative history coverage.

## Interaction contract

An acknowledgement timeout, disconnect or navigation after submission starts produces a distinct
`UNCONFIRMED` bubble: neutral “投递结果未知 / Delivery unknown”, compact `SESS-022`, and
“重新核对 / Check status”. This action only queries authoritative history/state; it never submits.
Copy and source selection remain available; edit, withdraw and retry are withheld. Unknown is
preserved across screen/process restoration. A missing row or an elapsed timeout cannot prove
non-delivery. Text equality alone cannot identify a prompt: unknown bubbles remain separate from
server rows unless delivery has authoritative identity/acknowledgement evidence.

History updates preserve unconfirmed local turns separately and do not require them in the server
snapshot. Explicit refusals and preparation failures retain the existing unsent recovery actions.
Android supports account connections only. URL/App Token/password/ticket setup and fallback are
retired; old credentials cannot become a transport after logout or failed account recovery.

## Test-impact review (before implementation)

- Network: account client actually has ping; dropped response, abrupt closure and submission
  cancellation never replay; pre-write failure remains retryable; diagnostics omit params/results.
- History: 10 server user turns plus one unknown local turn accepts updated history; repeated text
  never steals a server identity; restoration retains unknown and prevents withdraw/retry.
- Account retirement: first start, stored legacy values, activation-pending upgrade, sign-out,
  expired/revoked account and deletion all stay in account login/device recovery. Remove obsolete
  legacy-only tests and update affected consumer fixtures, preserving account isolation tests.
- UI L1: unknown state and allowed actions, bilingual copy, narrow/large-font and light/dark.
- Cloud: prompt diagnostics at both relay hops, bounded tracking and redaction, isolated lost-reply
  tests. Run `npm run build && npm test` plus Android unit/build and screenshot gates.
- Host probe: L1 available; L2 unavailable (attached HONOR is unauthorized); L3 Pixel_API_37
  available. Record actual verification after implementation. Fault injection uses isolated services,
  never interrupts the user's live Connector/Hermes/network. It cannot prove the historical root cause.

## Integration and release scope

The approved change touches Android, Gateway and Connector. `HR-SESS-022` is a local Android
presentation/persistence code; no wire field, RPC semantics, endpoint or upstream Hermes contract
changes. Web and Desktop behavior therefore need no matching implementation. Desktop already
retired its setup mode independently; server legacy routes remain available to older clients.
The encrypted Android store retains a no-op historical `CredentialStore` adapter for constructor
compatibility, while its real account store remains active; it never loads/saves retired secrets.
No package version is allocated and no service is restarted or published. Required release artifacts
are `androidApp`, `gateway` and `connector`; a changed Connector must reach the managed installation
through its normal component release gate.

## Verification record — 2026-10-25

- Cloud build and full `npm test` pass. This Mac needs OpenSSL 3 in PATH for the pre-existing ops
  AES-GCM CMS fixture; `/usr/bin/openssl` is LibreSSL and cannot decrypt that fixture. No ops
  implementation was changed. The default suite still skips 32 Gateway database/network cases
  and one scripts database case because their opt-in fixtures are unavailable. The real Connector success/lost-reply/abrupt-disconnect tests each observe one submit
  and redacted metadata, and the Gateway test pins the 16-request bound without dropping frames.
- Android unit/build/lint baseline passes (2,761 tests; zero failures, errors or skips), including account credential isolation, restoration,
  not-queued refusal, disconnect/timeout and history regressions. The test fixture cancels its
  application-scope jobs in `finally`, so a failed assertion cannot strand an infinite poller.
- L1: recorded and visually inspected Chinese light/dark and English 360dp/1.3x unknown-delivery
  screenshots. Sign-in footer baselines are refreshed for diagnostics-only access.
- L3: Pixel_API_37 (`emulator-5554`), instrumented
  `unknownOutcomeOffersOnlyStatusQueryAndCopyOnApi37`: OK (1 test). Check status invoked only its
  callback; the menu offered copy/select and no edit/withdraw. A local upgrade from 0.1.173 to
  source 0.1.182 retained app data and opened account sign-in. The emulator's capability request
  returned HR-ACCOUNT-002, so this is UI/gate evidence, not successful account connectivity.
- L2: attached HONOR is unauthorized; no physical-device result is claimed. Upgrade from the
  incident's actual 0.1.180 credentials, vendor networking and normal-workload recurrence remain
  pending in `ANDROID_SMOKE.md`. No user APK was handed off and no production deploy occurred.

## Owner-authorized release allocation — 2026-10-25

The owner requested publication in the same conversation. The release PR allocates Android
0.1.183 (code 184), Gateway 0.4.37 and Connector 0.1.18. Android preparation passed 2,761 unit tests
and lint without release credentials. Cloud schemas, flags, protocols and minimum clients remain
unchanged. A managed pair 0.4.13 is planned; its CI signing environment currently lacks the required
inputs, so no signed-component publication is claimed. Actual publication receipts and public
verification must precede the MissionGo verification handover.
