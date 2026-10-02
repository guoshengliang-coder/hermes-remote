# HG-192 verification — 2026-10-02

Approved scope: local recognition and manual column selection, local client preferences/history,
and a reviewed fresh-data draft. No AI inference request or cross-device synchronization.

## Automated baseline

- Root `npm run build && npm test`: pass. Protocol 16, Connector 125, Gateway 201,
  release-server 37 and scripts 405 passed; Gateway 29 and scripts 1 opt-in/environment tests
  skipped by the existing suites. macOS LibreSSL cannot run two existing CMS recovery tests;
  rerunning with Homebrew OpenSSL 3 in PATH passes. No remote production tests were enabled.
- Web `npm run typecheck && npm test && npm run build`: 81 files / 765 tests passed.
  New coverage includes three standard data shapes, IDs/units/gaps/totals/duplicates,
  serialized models, runtime origin/source validation, account/session/content cache isolation, iframe sender/nonce validation,
  renderer failure/retry, streaming completion, conversation changes, presentation save/cancel,
  and moving/restoring the fullscreen card and ancestor scroll positions.
- Android `:app:testDebugUnitTest :app:assembleDebug`: 2,388 tests, zero failures/errors/skips.
  New L1 screenshots cover warm light Chinese, app dark Chinese, and English fontScale 1.3.
  Debug signing verification passes. Version 0.1.161 was used only for local development testing;
  no distributable release was allocated or published.
- CI path classification tests ensure all four shared assets select both Android and Web.

## Browser and platform checks

Chrome 154.0.8037.93, macOS Mac16,10 (24 GB / 10 CPU), 412×915 viewport:
the built fixed template was hosted through the actual Gateway WebAppHost in an opaque
`allow-scripts` iframe. Series/date filters, horizontal-bar switching, exact original value
15 人, and emitting a requested-range draft message passed. Cookie access, parent-document
access and network fetch were blocked. Expected CSP-denial console messages are deliberate
security probes; no uncaught page errors were observed. Child-to-parent messages target the
chart URL origin explicitly, and incoming messages require that origin plus the parent window.
The parent-to-child call uses a narrowly documented Semgrep exception: an opaque sandbox can
only be addressed with targetOrigin "*". It targets the exact fixed iframe WindowProxy; no
credentials enter it, fixed code never navigates, CSP blocks network, and replies require source/protocol/nonce.
See [MDN sandbox communication](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/sandbox) for the opaque-origin constraint.

| Source rows | Parent analysis + iframe reload/controls + two animation frames |
|---:|---:|
| 100 | 52 ms |
| 1,000 | 67 ms |
| 10,000 | 212 ms |

These are single local samples with the parent-provided cached model, not statistical
benchmarks, production latency, Android timing, or a promised generation SLA. All rows remain
available; no sampling or inferred values are introduced.

L3: Pixel_API_37 / emulator-5554 / Android 17 API 37, real packaged WebView and offline gallery
fixture (dates × platforms, four data rows plus one total). Verified inline rendering,
classification filtering, fullscreen rendering and filter restoration. A focused select followed
by fullscreen initially triggered a Compose focus-search crash; the fix clears native focus
before changing renderer ownership/view and has a boundary regression plus emulator retest.

## Remaining manual coverage

L2 was unavailable: no physical Android phone attached. Device/ROM look-and-feel, TalkBack,
real Android large-table timings, rotation across vendor devices, and end-to-end historical
messages in a real account still need the smoke cases in `ANDROID_SMOKE.md`. L1 toggles and
L3 fixture checks do not replace those checks. Web account-level interaction and mobile Safari
need live smoke verification; the automated state/DOM cases and Chrome template harness do
not prove every browser or real-data workflow.

Release order when separately authorized: Gateway CSP support, Web static bundle, then a newly
allocated Android APK through its package/publish gates. Desktop/Connector/protocol are unchanged.
MissionGo Hermes GO currently declares web/gateway/connector but no Android artifact; an
administrator must register the Android mapping before development-complete state handoff.
