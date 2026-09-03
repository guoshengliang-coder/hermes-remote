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
