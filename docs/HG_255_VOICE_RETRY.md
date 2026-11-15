# HG-255 · Network-independent voice capture and original-audio retry

Web and Android preserve the existing five-minute, hold/release/cancel/edit composer. Capture and
recognition now have separate lifetimes. Network failure degrades only recognition while a held
foreground microphone continues recording; release stops capture and reports a recoverable failure.
Recovery is deliberate: network restoration alone never sends. No Gateway credential or wire change,
new provider API, server audio storage, or background microphone service is introduced.

## Ownership and local data

Web journals PCM packets in IndexedDB; Android uses its no-backup private directory. Scope includes
origin/account/Mac/profile/conversation. Each conversation has one pending recording. Capacity reserves
a full five-minute recording (9,600,000 bytes) for every active writer against a 32 MiB local bound;
completed pending recordings consume their actual size. Existing pending audio is never silently
replaced. App/browser closure can leave an interrupted prefix, which is labelled incomplete.

The retention window is 24 hours from creation, including any final-text handoff record. Active views
check expiry, and reopening cleans expired files/chunks. Sign-out/revocation invalidates old writers
before clearing local content. Browser eviction, storage failures and incomplete writes are not a
zero-loss guarantee. A storage failure uses registered bilingual `HR-VOICE-006`, stops capture and
must not claim a complete recording. Audio and transcript content never enter diagnostics.

A successful recognition first journals recoverable final text, then deletes PCM. Android saves an
edit result to its draft store before deleting the handoff record. Web keeps the handoff until a
positive submit acknowledgement (including a retry); failed or uncertain delivery remains recoverable.
Text backups are explicitly labelled to check sent history before restoration. Audio retry and message
retry are different operations. A message failure must not call ASR again.

## Recognition and UI

A retry opens a new authenticated ASR WebSocket and reads the original PCM from its beginning.
Packets contain up to 200ms of PCM and are paced accordingly. A new final replaces its own previous
partial candidate; it is never appended after an already inserted copy of that partial. Only the current
attempt may settle. Retries do not reopen a microphone. The final-result deadline starts only after
the last packet; the UI backstop includes saved audio duration. Long recordings may require roughly
their original duration and another billable provider request; there is no instant-result promise.

`VoiceComposer`/`VoiceRecordingOverlay` keep gesture ownership through connection changes. A persistent
recovery card offers Retry send, Transcribe and Discard; while an attempt is active actions are inert.
An interrupted, limited or reopened recording cannot auto-send: it can only become reviewed draft text.
Existing typed text and attachments remain in the composer. A restored Web recording cannot restore
page-memory attachment Blobs; the card directs the user to review text and re-add missing attachments.
The account/Mac/profile/conversation cannot change underneath an old attempt and receive its result.

The existing composer is retained. Android `VoiceRecoveryCard` uses Material theme tokens, wrapping
FlowRow actions and the existing type scale. Web uses the same `surface-container`, `on-surface` and
text-button/error-action primitives. No theme exemption or global style change is required.

Common components and entry points: Android ChatScreen, DoubaoVoiceSession, VoiceRecordingStore and
VoiceRecoveryCard; Web Composer, BrowserVoiceSession, VoiceRecordingStore/VoiceJournal and ChatPage
send handoff; account invalidation hooks clear stores on both clients. New-chat and existing-chat
composers use their existing scoped draft identity. Desktop and the feedback SDK are unaffected.

## Verification and remaining acceptance

Automated regressions cover mid-capture disconnect with continued audio, exact replay bytes/new
sequences/no second microphone, final deadlines, interrupted/complete recovery, explicit cancellation,
expiry, capacity reservation, logout write invalidation and durable text handoff. Web component tests
exercise the real session/store with a fake capture/socket, including repeated Retry and reopen-to-edit.
Android Roborazzi checks the recovery card in Chinese/light and English/dark/large-font, and inert
retry actions while busy. These tests replace the old immediate-failure-on-disconnect expectations.

Physical checks remain required: iPhone Safari and Home Screen Web App, Android Chrome, and Android
App microphones on real vendor devices with Wi-Fi/mobile/airplane-mode interruptions. Check pre-result,
mid-sentence, released-waiting and final-timeout failures; cancel/edit; original draft plus image/file;
five-minute/last-frame replay; background/lock interruption; account/session changes and storage-full.
The current host probe reports no attached physical device. An available emulator is not a substitute
for real microphone/provider/browser acceptance. No version bump, APK distribution, push, merge or
production deployment is part of this development instruction.

### Local verification record — 2026-11-15

Source baseline: main `0c0026f371cbbeac66a060615abe908ad4ca79cf`, independently rechecked against
remote main at handoff. Development changes remain in the isolated `codex/hg-255-voice-retry` branch;
no push, merge or publication was performed.

| Check | Result | Boundary |
|---|---|---|
| Web typecheck / full Vitest / production build | Passed; 103 files, 1,041 tests | Synthetic capture/provider and DOM; not a live microphone/provider test |
| Android JVM baseline + debug assembly | Passed; 369 suites, 3,010 tests, zero failures/skips | Local validation APK only; not a release/distribution artifact |
| New recovery-card Roborazzi verification | Passed; screenshots visually inspected | Chinese/light and English/dark/fontScale 1.3, 360dp; busy-action assertions |
| Local APK signature | Matches canonical public certificate in SIGNING.md | No key material provisioned, no APK distributed |
| Final whitespace/diff review | Passed | No credentials, generated build files or unrelated work included |

Verification layers: L1 JVM and Roborazzi ran. L2 unavailable: the host has no attached physical
device, so no vendor/microphone/provider acceptance is claimed. L3 is available (`Pixel_API_37`)
but was not run: this change introduces no targetSdk-gated platform path, and emulator results
cannot close the physical Safari/PWA or vendor microphone gap. Those checks remain explicitly pending
in SMOKE_TEST.md. Gateway/Connector and production services were not modified or restarted.


### Three-round audit follow-up — 2026-11-15

Protocol, recording ownership/integrity and asynchronous isolation were rechecked with failing-before,
passing-after regressions. Six categories were repaired: premature provider final during replay;
consuming live audio from another view; truncated audio retaining its complete flag; empty stopped
journals blocking the conversation; a late old-chat handoff unlocking another chat; and an unobserved
IDB transaction rejection. Web uses a renewable 15-second writer lease and atomically freezes an
expired writer before prefix recovery; Android tracks live in-process writers and checks sealed file
length. Active recovery cards disable consume/discard.

Final Web typecheck, 1,050 tests and build passed. Android 3,016 tests and local assembly passed;
recovery-card Roborazzi verification and canonical public signing-certificate checks passed. The
complete audit record is in HG_255_AUDIT.md. Physical/provider acceptance and release authorization
boundaries remain as recorded above; no commit, push, merge or distribution was performed.
