# HG-254 · Android / Web chat action

The approved design uses a 48dp / 3rem circular action, a 24dp / 1.5rem up arrow with a
1.7dp / px round stroke, and a solid Stop square. Light fill is `#181C24`, dark fill is
`#1E232B`, content is white, and dark has a 1dp / px `#3A4049` hairline. These tokens are
independent of the current list FAB (`#181C24` in both themes).

## Scope

Android expanded, compact and voice modes use `ComposerActionButton`; Web retains its existing
shared Composer action and 3rem dimensions. Empty drafts keep the bare disabled arrow in expanded
mode and the existing attachment action in compact mode. Send/Stop ownership, preparation, voice
gestures, keyboard/IME rules, drafts and attachments are unchanged. No new error or protocol code,
version allocation or public distribution is involved.

## Evidence

- Base: `c8219090`, isolated `codex/hg-254-send-button` worktree.
- Before: Android design/colour/voice tests passed; Web composer/voice/chip baseline: 82 tests.
- The new Web icon/action regression fails with the old icon implementation, then passes with the
  reviewed arrow and solid Stop. After audit, the focused Web suite has 84 passing tests.
- Android full `testDebugUnitTest` and `assembleDebug` passed. Focused button/voice/design checks
  and Roborazzi verify passed: 19 tests after audit, including explicit app-dark/system-light, 1.3x text and
  narrow/density variants. The changed voice golden and four new state goldens were inspected.
- Web `typecheck`, full tests (1,027 tests / 101 files after audit), and production build passed.
- L3: `ComposerActionDeviceTest` passed on Pixel_API_37 / Android 17 (emulator-5554).
  It checks actual light/dark paint, 48dp touch bounds, transparent disabled background and real
  injected touches: Send and Stop trigger only their own callback, disabled does not send. PNGs
  were saved through MediaStore, pulled and inspected. The owned emulator was closed afterwards.
  This is a production-control fixture, not a full account/backend conversation test.
- The browser check renders the production Composer, icons and CSS, using only a synthetic context.
  Chromium and WebKit: light/dark × mouse/touch, eight combinations passed. It checks actual CSS
  fill/content/outline, 48px/24px geometry, send/stop/disabled/preparing, keyboard/IME rules,
  visible focus, long model labels and 1.3x text. It does not contact a conversation backend.
- Browser reproduction from repository root:
  `node web/scripts/check-composer-action.mjs <absolute-playwright-module> <output-directory> [webkit-executable]`.
  Browser versions and measurements are recorded in the output `results.json`; temporary source
  fixtures are removed on completion. An external browser install is intentional, not a dependency
  added to the product lockfile.

## Acceptance boundaries

L2 is unavailable: no physical Android device was attached. Desktop WebKit is not physical
iPhone Safari/Home Screen WebApp verification. OEM display, actual fingers and real mobile
keyboard/safe-area acceptance remain in `ANDROID_SMOKE.md` and `SMOKE_TEST.md`.

Public Web source assets were read before implementation; they contained the existing up arrow,
brand-colour send/stop and 3rem dimensions. Their resource fingerprints were recorded in the fixed
approved plan; a deployment source commit was not available publicly. The existing Android public
release was 0.1.196 / code 197. Local source/build results do not mean either product is published.

## Three audit passes (2026-11-14)

1. **Implementation and contract review:** independently traced expanded/compact/voice call sites,
   send/stop/disabled conditions, shared icon consumers, theme selection, fixed sizes and the
   approved scope. No production-logic defect or unintended product change was found.
2. **Coverage review:** found three verification gaps. The Web runner collected border width but
   did not assert it; voice-mode Stop had no direct DOM/browser check; and the whole Android
   screenshot's tolerance could miss a small hairline regression. Added a 1px width assertion,
   voice-mode Stop/draft/action checks in DOM and Chromium/WebKit, and exact rendered-pixel
   checks for the Android dark hairline, white ink, fill and disabled transparency. The browser
   voice test uses a synthetic MediaDevices object and never opens a microphone. On JVM,
   pixel sampling uses native Roborazzi output, not device-only PixelCopy.
3. **Negative checks and final review:** temporarily removed each platform's hairline. The Web
   check failed specifically on `0px` versus `1px`; the Android check failed specifically on fill
   pixels where outline pixels were expected. Both sources were restored byte-for-byte in
   `finally` before final baseline verification. No mutation was retained and no new production
   logic change was needed. Existing L3 evidence remains applicable because production code is
   unchanged by this audit; no second device run is claimed.

The audit strengthens verification within the original approved design. It does not authorize
commit/push, integration, a version bump, release or physical-device acceptance.
