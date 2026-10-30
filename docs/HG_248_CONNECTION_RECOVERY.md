# HG-248 — Android connection recovery and explicit recheck

## Evidence and scope

The Android 0.1.189 report contains two fully read diagnostic logs (500 and 4432 records) and one
screenshot. The screenshot shows HR-CONN-010 with a Re-check action that has no progress/result
feedback. During the incident, Android repeatedly reached the Gateway and received 503/HR-CONN-005.
The pre/post-restart requests target the same Mac. The Android and Gateway clocks differ by roughly
1.8 seconds; correlate by connection IDs rather than treating timestamps as precise hop timings.

Read-only Gateway/M4 evidence shows a heartbeat eviction at 2026-10-30T01:12:27.702Z. M4 observed the
close at 01:12:47.653Z with `stopping=false`, scheduled a 3526ms retry, and became available at
01:12:58.654Z (Gateway registration: 01:12:58.680Z). Its Connector process remained alive throughout.
Android subsequently established its cold-start connection. This proves upstream automatic recovery;
it does not prove what the old Android process would have done after the upstream recovered.
The transport delay's underlying cause remains unproven. No production restart, fault injection,
heartbeat-budget change, version allocation, or publication belongs to this source change.

A separate loopback diagnostic using the unchanged 0.1.189 network sources reproduced ordinary
503 → successful automatic recovery in one client, and 4403 → classification handshake 503 → a
stopped client. Explicit reconnect recovered the latter without replacing the HTTP client.
The report itself has no classification-stop marker, so this second gap is not claimed as its cause.

## Test-impact review

- A deterministic backoff/manual race fails before the generation check at socket opening: the old
  callback opens a third socket after the manual connection is already ready. Validate the expected
  generation under the same lifecycle lock that installs the new socket.
- Health-monitor regression: a manual request arriving during a probe must get its own fresh result;
  repeated taps coalesce, finish/cancellation release the busy state, and failure retains current data.
  A later automatic recovery retires an old completed failure rather than showing it beside healthy state.
- Error-boundary regression: registered Connector/Hermes/auth/account errors retain their identities,
  bilingual summaries and retryability; generic diagnoses never override a definitive server code.
- Recovery regression: only foreground, valid-account ownership may resume a stopped connection;
  recovery never replaces a healthy socket or replays a prompt. Real account/device repair gates stay.
- HTTP boundary: cancelling a probe must cancel its underlying call, including a delayed response body.
- UI: busy state, unchanged failure, successful recovery and compatibility notices; light/dark, English,
  large text, wrapped result and error-code visibility. Screenshots are synthetic, not incident proof.
- Diagnostics: fixed trigger/outcome names plus process/cycle/socket IDs; no URL, credential, account,
  device name, response body or raw exception text in the added recovery trace.

## Verification record

Validation on 2026-10-30, using Android Studio's bundled JDK:

- `./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug --console=plain` passed:
  2903 tests across 354 suites, zero failures, errors or skipped tests. The build's canonical debug
  signing-key check passed. This local build is not a distributable release.
- Red/green checks established both the dropped manual probe and definitive error-code regressions
  before the fix. A deterministic old-backoff/manual-recovery race also failed before the additional
  generation check and passed afterwards. The full suite includes ordinary 503 recovery, stopped
  authorization-classification recovery, owner/account gates and real delayed-body cancellation.
- Four Robolectric/Roborazzi snapshots were recorded and directly inspected: checking in light/Chinese,
  checking in dark/English at 1.3x text, unchanged failure in dark/Chinese at 1.3x, and restored in
  light/English. The click test verifies progress immediately, then an explicit failure and another check.
- `git diff --check` passed before handoff.

Host capability probing found L1 available, no attached physical device (L2), and no installed emulator
package (L3). Physical HONOR background survival, actual radio handover and the M4 transport delay
still require device evidence; loopback/JVM tests cannot establish those properties. The pending
device cases are recorded in `ANDROID_SMOKE.md`; no production service was changed or restarted.
