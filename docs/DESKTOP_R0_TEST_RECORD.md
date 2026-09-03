# Hermes Go Desktop R0 local test record

Date: 2026-09-02

Branch: `codex/desktop-r0`

Baseline: `fb253f4` (Account mode and Desktop checkpoint)

## D0.1 scope

- Split Logs, Account & Devices, and Settings into independent SwiftUI source files without changing
  layout, wording, actions, or navigation.
- Added an internal typed representation for every published binding state while retaining the raw
  JSON wire value and a safe unknown-state fallback.
- Added focused state-mapping tests.
- Added the non-overlapping Desktop R0/R1 integration plan.
- Did not modify Gateway, Connector, Protocol, account-wide contracts, root build commands, or Cloud
  planning files.

## Executed results

| Gate | Result |
| --- | --- |
| Pre-change `npm run desktop:assets:test` | Pass |
| Pre-change `npm run desktop:test` | Pass: 38 tests |
| Post-change `npm run desktop:test` | Pass: 40 tests |
| Post-change `npm run desktop:assets:test` | Pass |
| Post-change `npm run desktop:app` | Pass: release Swift build, app assembly, and ad-hoc signing |
| Post-change `npm test` | Pass: 94 tests; 4 environment-dependent Gateway tests skipped |
| `git diff --check` | Pass |

SwiftPM required a task-local module cache and execution outside the outer filesystem sandbox because
its own nested `sandbox-exec` could not initialize there. The same source and test commands then
completed successfully.

## Not executed

- Live Google OAuth and an account-enabled Gateway.
- Disposable PostgreSQL account integration tests.
- Physical-phone or Connector takeover tests.
- DMG creation, Developer ID signing, notarization, or stapling.
- Staging or production deployment.

Those gates remain assigned to later Desktop integration, I5 migration, and release iterations.

## D0.2 scope

- Moved legacy inspection and the three health probes onto a dedicated non-main-actor coordinator.
- Moved deterministic component-health assembly into a pure Core boundary with focused coverage for
  latency, structured issue propagation, missing legacy installation, and observer-disabled states.
- Centralized overall-health and account-state presentation so the main window, menu bar, and settings
  surfaces no longer maintain separate state switches.
- Kept URLs, request headers, Keychain formats, refresh cadence, visible wording, and legacy Connector
  behavior unchanged.

## D0.2 executed results

| Gate | Result |
| --- | --- |
| Focused `swift test --package-path desktop` | Pass: 46 tests |
| `npm run desktop:assets:test` | Pass |
| `npm run desktop:app` | Pass: release Swift build, app assembly, and ad-hoc signing |
| `git diff --check` during iteration | Pass |

The repository-wide gate is deferred until the Desktop I3-B checkpoint because D0.2 did not modify
Node, Gateway, Connector, Protocol, or shared build behavior.
