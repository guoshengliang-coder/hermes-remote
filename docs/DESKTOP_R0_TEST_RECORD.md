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

## D0.3 scope

- Added exact Desktop clients for scoped Google reauthentication, first-binding creation and
  confirmation, replacement request and confirmation, and explicit unbind.
- Reused the existing Keychain session record to persist every mutation idempotency key before
  transmission. A lost response reuses the same operation key after restart instead of creating a
  second binding, replacement, confirmation, or unbind transaction.
- Kept the Ed25519 private key local; binding requests contain only the existing public key and the
  authenticated Desktop installation identifier.
- Added strict request input and response-state validation, bounded HTTP behavior, capability
  fail-closed handling, and structured `HR-AUTH-*` / `HR-BIND-*` error propagation.
- Did not expose the operations in the UI yet, contact a live Gateway, start a candidate Connector,
  mutate the legacy Connector, or touch Hermes.

## D0.3 executed results

| Gate | Result |
| --- | --- |
| `swift test --package-path desktop` | Pass: 56 tests |
| `npm run desktop:assets:test` | Pass |
| `npm run desktop:app` | Pass: release Swift build, app assembly, and ad-hoc signing |
| `npm test` | Pass: 94 tests; 4 environment-dependent Gateway tests skipped |
| `git diff --check` during iteration | Pass |

The focused coverage includes exact paths/headers/bodies, invalid path and response rejection,
cancelled reauthentication, disabled binding capability, binding conflict, expired confirmation,
and persisted-key reuse after lost responses for create, replace, both confirmation types, and
unbind.

## D0.4 scope

- Added a deterministic presentation/action reducer for no-binding, pending, bound,
  replacement-pending, revoked, and unknown future states.
- Final first-bind and replacement confirmation appear only after both key proof and health
  verification are true in the Gateway snapshot.
- Added separate confirmation language for candidate creation, activation, replacement, and
  destructive unbind. Replacement preserves the original connection until final commit; unbind
  explicitly preserves account sessions and Hermes.
- Added registered recovery actions for retry, sign-in, continue-legacy, and verify/replace errors,
  plus progress and completion announcements.
- Advanced the local Desktop test package to `0.3.0` (bundle build `4`).

## D0.4 executed results

| Gate | Result |
| --- | --- |
| `swift test --package-path desktop` | Pass: 60 tests |
| `npm run desktop:assets:test` | Pass |
| `npm run desktop:app` | Pass: `0.3.0` release Swift build, app assembly, and ad-hoc signing |
| Real app launch and account-page navigation | Pass on macOS 14.8.9 |
| Native accessibility API inspection | Pass: five named sidebar actions with page hints; Account & Devices page, refresh action, and legacy entry discoverable |
| Default-off safety state | Pass: no binding/replacement/unbind action exposed by the current public capability response |
| Legacy Connector non-mutation | Pass: PID `11610` and launch count `19` before, during, and after the run |
| `git diff --check` during iteration | Pass |

Screen recording remained unavailable to the test process and the temporary capture contained only
the desktop wallpaper, so it was deleted and is not treated as visual evidence. Light/dark rendering,
long account/device names, and each capability-enabled confirmation dialog still require the isolated
D0.5 fixture deployment; no live account or binding mutation was attempted in this run.
