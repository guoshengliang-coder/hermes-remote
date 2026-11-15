# HG-255 · Three-round audit and repair record

Scope: the unpushed Web/Android voice-retry implementation on `codex/hg-255-voice-retry`, based on
main `0c0026f371cbbeac66a060615abe908ad4ca79cf`. The same source was checked against remote main at
audit entry. Existing unrelated work in the original workspace was preserved. No version allocation,
Git push/merge, APK distribution or production change is authorized by this audit.

The three rounds used separate fault models: protocol completion, local recording ownership/integrity,
and asynchronous conversation isolation/transaction failure. Each finding below was reproduced with
an added test against the pre-repair implementation, then repaired and checked again. The exercise
is source review plus controlled fault injection; it is not live provider or physical-device acceptance.

## Round 1 — Protocol and completion ordering

**Finding (P1):** both clients used the fact that capture had stopped / the attempt was finishing as
permission to accept a provider final. A saved-audio replay is already in that state at startup, so a
final received during initialization or in the middle of replay could become an incomplete result.

**Reproduction:** two Web cases and two Android cases send an early final before any saved audio,
or after only the first audio packet. All four failed before repair by accepting the premature final.

**Repair:** track sending the final audio marker independently from stopping capture. Web snapshots
that fact at the socket event's arrival, before its asynchronous parse queue. Android rejects the
premature frame before recognizing initialization and preserves recognition-failure classification.
Neither client may emit Final until the last audio packet has been submitted. Early text remains a
reviewable candidate; the original audio is retained. Focused protocol/component tests passed.

Key code: `web/src/chat/voiceSession.ts`, Android `DoubaoVoiceSession.kt`.

## Round 2 — Local ownership, recovery and integrity

| Finding | Before-repair evidence | Repair |
|---|---|---|
| P1: another view could consume or hand off an actively written recording | Web/Android store regressions could read/replace a live prefix, causing the original writer to fail | Web keeps a 15s renewable writer lease; Android tracks active writers in its app process. Active recovery/discard actions are disabled. A recovered expired Web writer is atomically frozen before reading, and late appends are rejected. |
| P1: a shortened Android file retained its complete flag | Seal six bytes, truncate to four, then load/list: complete stayed true | Derive completeness from the sealed and actual lengths. A mismatch becomes an interrupted prefix and cannot take the auto-send path. Oversized/unaligned replay data is rejected. |
| P2: an empty stopped journal could occupy its conversation for 24h | Android store list hid the empty record while begin still refused another recording | Remove empty stopped/dead records. An active zero-byte writer is retained; Web waits for its short lease before removing a crashed empty journal. |

Additional regression cases verify stopped writers cannot append again, a second view cannot consume
or discard live audio, and a crashed Web prefix is claimed atomically after lease expiry. Original
write/replay bytes, capacity bounds, expiry and sign-out isolation checks remain in place. Focused
Web and Android checks passed after repair.

Key code: both `VoiceRecordingStore` implementations, Web Composer, Android VoiceRecoveryCard and
ChatScreen. The theme/layout contract remains the existing recovery card; only its active-state
explanation and disabled actions were added.

## Round 3 — Late callbacks and storage transaction failures

**Finding (P2):** an old Web conversation's delayed final-text handoff cleared the component's busy
flag before checking conversation ownership. It could unlock recovery controls in a newly opened
conversation whose own handoff was still pending.

**Reproduction/repair:** hold both handoffs, switch A → B, then complete only A. B's controls became
enabled before repair. The completion now checks the captured account/draft epoch and conversation
scope before any state mutation. The regression keeps B locked and confirms only B's text appears.

**Finding (P2):** an aborted IndexedDB request could reject its transaction-completion promise as
well as the operation. If the operation exited first, that second promise was left unobserved.

**Reproduction/repair:** abort a real fake-indexeddb transaction before its request completes. The
operation rejected as expected but Vitest also reported an unhandled rejection before repair. The
transaction helper now observes its completion immediately while returning the original promise,
so normal callers still receive failures. The regression subsequently writes and replays the same
record, confirming the fix does not swallow the actual error or corrupt the store.

The final focused Web protocol/store/component group passed with zero unhandled errors. Android
voice/store/recovery-card checks passed. No fourth audit round was started; final baselines below
are verification of these repairs.

## Final verification

Final verification on the repaired working tree passed:

| Check | Result |
|---|---|
| Web typecheck, full Vitest and production build | 103 files / 1,050 tests passed; zero unhandled errors |
| Android JVM baseline and debug assembly | 369 suites / 3,016 tests passed; zero failures, errors or skips |
| Recovery-card Roborazzi verification | Passed, including the original light/dark large-font baselines and active-state action assertions |
| Local debug APK public certificate | Matches the canonical SHA-256 in SIGNING.md; no APK distributed |
| Source diff / whitespace / main baseline | Passed; remote main still 0c0026f371cbbeac66a060615abe908ad4ca79cf |

Six categories were repaired across these three rounds. L1 JVM/Roborazzi ran; L2 remains unavailable
(no attached physical device). L3 Pixel_API_37 is available but was not run; no targetSdk-gated
behavior was introduced. No code commit, push, merge or deployment was performed. Physical/provider
limits remain: no attached Android device on this host; iPhone Safari/Home Screen Web App, Android
Chrome, real microphones and the configured Volcengine service still need the scenarios in
SMOKE_TEST.md. An available SDK-37 emulator does not replace those checks. All findings in these
three source/fault-injection rounds have repairs and regression tests; this is not a claim that every
possible device or provider behavior has been exercised.
