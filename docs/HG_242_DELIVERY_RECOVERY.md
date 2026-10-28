# HG-242 unknown-delivery recovery

Owner-approved in the dispatched conversation on 2026-10-28. Android-only scope: message-specific
check feedback and confirmed removal of the phone's local unknown notice. Never cancel Mac work,
remove server history, identify a submission by matching text, or submit/replay a prompt.

## Test-impact review before implementation

- Check progress appears immediately; repeat taps issue only one read. Success explicitly retains
  unknown; failed/30-second-expired reads retain the bubble and expose a bilingual structured error.
- Remove requires an explanation/confirmation. Cancel preserves everything. Durable deletion must
  succeed before the UI removes the local turn; failure keeps it and is retryable (HR-STORE-003).
- Removal targets only one local unknown record in the original account/Mac/profile/session.
  Other unknown/failed turns and authoritative same-text rows remain. Reopen/process restoration,
  a late history response and an in-flight local restore cannot resurrect the removed notice.
- Retry/edit/withdraw and implicit resubmission remain forbidden. Check/removal issue no submit,
  interrupt or server-delete call. Existing unknown-delivery tests remain valid.
- L1 covers Chinese/English, light/dark, narrow/1.3x typography, progress, failure and confirmation.
  Run Android unit/build baseline; report L2/L3 availability and unverified physical-device steps.

## Integration

HR-STORE-003 is an Android-only persistence boundary; no shared DTO, RPC, server response or upstream
Hermes contract changes. Web, Desktop, Gateway and Connector need no code changes. HR-SESS-022's
meaning is unchanged. Required release artifact: androidApp. This task allocates/publishes no APK.

## Verification

- Android `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin`
  passed: 2,792 JVM tests, zero failures/errors/skips. The focused recovery/store/error suites also
  passed, including real DataStore reconstruction, write failure/retry, stale restore, late history,
  navigation during commit and no submit/interrupt/server-delete calls.
- L1: 18 Chinese light/dark and English screenshots at 360dp / 1.3x were recorded and directly
  inspected: idle, checking, still unknown, failed check, failed removal and confirmation. Disabled
  progress labels retain readable neutral contrast. Cancellation and confirm callbacks are tested.
- Build signing verification and `apksigner verify --print-certs` match the canonical certificate in
  `SIGNING.md`: `06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5`.
- L2 unavailable: no attached physical device. L3 unavailable: emulator package not installed.
  Instrumented test source compiles but was not run. HONOR natural-network and actual multi-phone
  behavior remain pending in `ANDROID_SMOKE.md`; JVM fixtures do not establish the old stalled hop.
- No version was allocated, no APK was distributed, and no production service was changed.
