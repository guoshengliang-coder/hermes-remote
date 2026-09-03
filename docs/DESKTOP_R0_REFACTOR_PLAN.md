# Hermes Go Desktop R0 refactor plan

Status: active on `codex/desktop-r0`, based on the account/Desktop checkpoint `fb253f4`. D0.1 was
completed locally on 2026-09-02; D0.2 and D0.3 were completed locally on 2026-09-03.

Desktop R0 prepares the macOS client for I3-B without changing the Gateway, Connector, Protocol,
Hermes, or any production service. The concurrent Cloud Gateway work owns its own branch and
worktree; this plan keeps the two streams mergeable by assigning non-overlapping files and delaying
live binding integration until the Gateway runtime boundary is stable.

## Fixed boundaries

- Desktop R0 may change `desktop/**` and Desktop-specific documentation only.
- Cloud Gateway owns `gateway/**`, the root `README.md`, and `docs/CLOUD_GATEWAY_*` during R0/R1.
- Neither stream changes `protocol/**`, account-wide contracts, shared error codes, or root build
  commands without explicit coordination through the integration task.
- Public `/v2/*` payloads, capability fields, Keychain formats, legacy QR payloads, and visible
  behavior stay compatible during structural work.
- No iteration starts, stops, replaces, or reconfigures the existing Connector. No iteration modifies
  Hermes, enables account flags, deploys a service, or publishes a DMG.

## Iteration order

| Iteration | Scope | Exit gate |
| --- | --- | --- |
| D0.1 view boundaries | Split independent Logs, Account & Devices, and Settings views; add typed internal binding states while retaining the raw wire value | Desktop tests and app build pass with no visible behavior change |
| D0.2 presentation orchestration | Completed: account/health presentation, legacy observation, probe orchestration, and health snapshot assembly now live outside the root view model | 46 Desktop tests and the packaged app gate pass with behavior-compatible state reduction |
| D0.3 I3-B client operations | Completed: scoped reauthentication, first-bind, replacement, both confirmation paths, and unbind use strict HTTP contracts and crash-safe persisted retry keys | 56 Desktop tests cover success, retry, cancellation, expiry, conflict, fail-closed decoding, and lost responses without contacting a live Gateway |
| D0.4 I3-B interaction states | Add explicit confirmations, conflict/revoked recovery, keyboard/VoiceOver behavior, and redacted diagnostics | Light/dark, long-name, keyboard, accessibility, and bilingual error checks pass |
| D0.5 integration | Rebase or cherry-pick Desktop-only commits after Cloud R0/R1 integration, then run live disposable-Gateway/PostgreSQL tests | Desktop, Gateway, account database, legacy compatibility, and Connector non-mutation gates pass together |

Connector takeover and rollback remain I5 work. They cannot begin until the Cloud R1 runtime/session
boundary is merged and reviewed. A distributable or production-enabled DMG also depends on the Cloud
R2 Server Release and R3 staging/operations gates, plus the existing Developer ID, notarization,
stapling, and clean-machine launch requirements.

## Verification

Every Desktop R0 iteration runs on macOS:

```bash
npm run desktop:assets:test
npm run desktop:test
npm run desktop:app
git diff --check
```

Run `npm test` before handoff to catch accidental shared-contract regressions. Live Google OAuth,
account-enabled Gateway, physical phones, Connector takeover, and production deployment must be
reported as untested until their separately authorized gates are available.

Local evidence is recorded in `DESKTOP_R0_TEST_RECORD.md`.
