# HG-205 verification — 2026-10-11

Chat keeps the source table. Fullscreen recommends reliable charts without required configuration;
Adjust is collapsed until requested. Unsuitable data stays a table. This supersedes the presentation
behavior in the historical HG-192 verification record; the existing sandbox/security boundary stays.

## Automated baseline

- Web `npm run typecheck && npm test && npm run build`: 89 files / 860 tests passed.
  Coverage includes automatic recommendations and unsuitable data, legacy defaults ignored,
  collapsed controls, filtering/reset and restored filters, complete 205-point plot traversal,
  exact values, long labels, dark/font-size/rotation, hidden renderer resize, source-only inline
  cards, fullscreen teardown/reopen, exact DOM/ancestor scroll restore, settings removal,
  streaming/session isolation and existing source/origin/nonce trust checks.
- Android `:app:testDebugUnitTest :app:assembleDebug`: 2,452 tests, zero failures/errors/skips.
  L1 Roborazzi covers source-only inline cards after a chart choice and Source table/Chart controls
  in Chinese light/dark and English fontScale 1.3. Canonical debug signing verification passed.
- Root build was run for the local mock dev stack. No Gateway/Connector/Protocol source changed;
  their full suites were not rerun. No version source, HR error-code contract or transport changed.
- `git diff --check` passed.

## Browser and Android checks

Chrome 154.0.8037.98 on macOS, local fixed renderer in an opaque script-only iframe, 412×915 and
915×412: initial collapsed controls and all valid rows, date filter and restore all, full long
category labels, 205 points traversed to 201–205, dark theme and responsive redraw passed with
zero uncaught page errors. This local harness is not a fresh production Gateway CSP audit or
live-account test. Existing trust regressions remain automated.

L3: Pixel_API_37 / emulator-5554 / Android 17 API 37. Installed the local developer build with
`device-install.py`, preserving emulator data and checking the canonical certificate. Gallery
fixture has four date/platform rows plus a total. Verified source-only card, fullscreen rendering,
collapsed Adjust, manual category selection with visible filter scope and Show all, original table,
focused selector → source → system Back without a crash, reopening and saved source view after
process/install restart. A hidden WebView viewport exposed clipped chart axes; using one live
viewport for canvas and scroll-container height fixes it and has a shared renderer regression.
Retested portrait source → chart with the complete plot visible. Landscape layout was inspected
by reopening the gallery fullscreen after rotation. The debug gallery itself recreates its local
modal on rotation; production chat uses separate saved fullscreen identity, so live-chat rotation
and long-history anchor behavior still require the smoke cases.

L2 unavailable: attached phone A5DN9X4708H02629 is unauthorized for ADB. No physical-device,
TalkBack, vendor-ROM, Safari/Home Screen or live-account verification is claimed. See
`ANDROID_SMOKE.md` HG-205 and `SMOKE_TEST.md` for those checks.

## Delivery boundary

No release/version was allocated, no APK was handed to a user and no production service changed.
The existing 0.1.170 number labels only this local developer build. Publishable changes require
both product artifacts `web` and `androidApp` (declared in MissionGo); Gateway, Connector and Desktop
need no new artifact because their contracts and CSP are unchanged. Publication stays a separate
owner-authorized gate. Code completion is not release or acceptance.

## Touch follow-up

Real Chrome CDP touch input exposed a canvas gesture constraint on dense plots: a 3,200px plot
inside a 388px viewport stayed at scrollLeft 0. Allowing native touch gestures restores horizontal
panning. `web/scripts/check-chart-touch.mjs` serves only the packaged fixture/assets on loopback
and applies a real Chromium touch gesture; it fails on the old CSS and passes after the change.
The test uses an external Playwright install and Chromium binary; it does not add shipped
dependencies. Run `node web/scripts/check-chart-touch.mjs <absolute-playwright-index.mjs>
<chromium-binary>`. Physical Android/Safari gesture behavior remains in the smoke plan.

The follow-up repeats Web typecheck/test/build (91 files / 886 tests on the merged main base)
and Android unit/build baselines (2,452 tests). L1/L3 feature
checks above were performed before this one-line gesture adjustment; the additional gesture test
uses real headless Chrome input, not a physical phone or Android emulator touch check.
