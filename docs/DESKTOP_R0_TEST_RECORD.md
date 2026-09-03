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
long account/device names, and each capability-enabled confirmation dialog remained pending after
D0.4; no live account or binding mutation was attempted in this run.

## D0.5 scope

- Integrated the Cloud Gateway R2 history at merge commit `bc37eac`, including release metadata,
  liveness/readiness probes, schema-version enforcement, and the PostgreSQL 18 support contract.
- Added an explicit Desktop storage namespace and package-time bundle/display-name overrides for
  isolated installations. The namespace covers the connection profile, account session, and
  Connector machine key; invalid non-empty namespaces fail into a non-production namespace, and the
  packaging script rejects malformed identifiers before building.
- Deployed a disposable PostgreSQL 18.6 cluster and an account/binding-enabled Gateway bound only to
  `127.0.0.1`. No production host, token, database, Connector, or traffic path was used.
- Installed the test app separately as `/Applications/Hermes Go Desktop Test.app`; the existing
  `/Applications/Hermes Go Desktop.app` remained at `0.2.0` build `3`.

## D0.5 executed results

| Gate | Result |
| --- | --- |
| PostgreSQL runtime | Pass: Homebrew PostgreSQL `18.6`, disposable cluster on loopback |
| `account:migrate` twice | Pass: exact schema version `7`; repeated execution remained idempotent |
| `ACCOUNT_TEST_DATABASE_URL=<disposable> RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | Pass: 53 tests, 0 failures, 0 skipped |
| Deployed `/healthz` | Pass: `alive` |
| Deployed `/readyz` | Pass: config/database/migrations `ok`, PostgreSQL `supported` |
| Deployed `/v2/capabilities` | Pass: account auth, installation sessions, binding/replacement, lifecycle inbox, and legacy auth advertised together |
| Protected `/internal/version` | Pass: unauthenticated request `401`; authorized test request reported server `0.2.0`, schema `7`, PostgreSQL major `18`, and source `bc37eac` |
| Desktop storage-isolation regression | Pass: 61 tests; valid namespace suffixes all services, malformed namespace cannot fall back into production storage |
| Invalid package namespace | Pass: packaging stopped before build with a bounded validation message |
| Isolated `npm run desktop:app` | Pass: `0.3.0` build `4`, bundle `com.hermesgo.desktop.local-r2-20260903`, strict ad-hoc codesign |
| Separate installation and launch | Pass: `/Applications/Hermes Go Desktop Test.app`; final rebuilt instance observed at PID `91908` |
| Capability-enabled Account & Devices UI | Pass via macOS accessibility API: the installed app reached the signed-out account page through the named sidebar action and exposed the account safety copy |
| Database non-mutation after Desktop refresh | Pass: schema `7`; accounts `0`, installations `0`, bindings `0` |
| Legacy Connector non-mutation | Pass: state `running`, PID `11610`, launch count `19` before and after installation/launch |
| Existing Desktop non-overwrite | Pass: installed production-path app remained `0.2.0` build `3` |
| `ACCOUNT_TEST_DATABASE_URL=<disposable> RUN_NETWORK_TESTS=1 npm test` | Pass: 122 tests across Protocol, Connector, Gateway, Release Server, and scripts; 0 failures, 0 skipped |

The local Gateway used impossible test credentials and placeholder OAuth audiences, so live Google
sign-in was deliberately not attempted. Signed-in long-name fixtures, light/dark visual capture,
physical phones, candidate Connector proof, takeover/rollback, Developer ID signing/notarization,
and any production deployment remain unverified. The isolated Gateway and Desktop test app are not
release artifacts and must not be represented as such.

## D0.6 scope

- Added optional protected Google Desktop client-JSON packaging with client-ID matching and no
  secret output to source control, logs, or diagnostics.
- Added bounded Google token-endpoint rejection diagnostics and kept response descriptions,
  authorization codes, access tokens, and ID tokens out of errors.
- Prevented periodic signed-out refreshes from immediately erasing an actionable interactive OAuth
  error; bootstrap and successful account-state transitions still clear stale errors.
- Rebuilt and installed the isolated app as `/Applications/Hermes GO OAuth Test.app` with bundle ID
  `com.hermesgo.desktop.oauth-test` and Keychain namespace `oauth-20260903`.

## D0.6 executed results

| Gate | Result |
| --- | --- |
| Desktop regression suite | Pass: 68 tests, 0 failures |
| Build-script syntax and source whitespace | Pass |
| Protected OAuth JSON packaging | Pass: downloaded client ID matched the configured Desktop client; the source JSON and secret were not printed or added to Git |
| Isolated app signature/configuration | Pass: strict ad-hoc codesign; loopback Gateway, test bundle ID, client ID, and storage namespace matched without displaying the client secret |
| Live Google browser OAuth | Pass: callback, Google token exchange, Gateway exchange, and signed-in Account & Devices UI completed on the target Mac |
| Account persistence | Pass: disposable PostgreSQL contained one account, external identity, installation, account session, and refresh token |
| Keychain persistence after app restart | Partial: isolated account-session and machine-identity items remained present; online refresh was unavailable because the disposable Gateway process had exited |
| Legacy Connector non-mutation | Pass: state `running`, PID `11610`, launch count `19` |
| Existing Desktop non-overwrite | Pass: `/Applications/Hermes Go Desktop.app` remained installed and running separately |

The D0.6 app and loopback database are local test assets, not release artifacts. No production
Gateway deployment, Connector takeover, binding operation, phone mutation, Developer ID signing, or
notarization was performed. Restart-time online refresh remains a later persistent-service gate; its
Keychain prerequisites were verified in the isolated namespace.
