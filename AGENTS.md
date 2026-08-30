# Hermes Remote Agent Guide

This file is the canonical repository-level instruction set for Hermes, Codex, Claude Code,
and any other coding agent working on Hermes Remote. It applies to the entire repository.

## Start every task safely

1. Read this file, the root `README.md`, and the README or document for the component being changed.
2. Inspect `git status` and recent commits before editing. Existing changes and untracked files belong
   to the user or another agent unless the current task explicitly says otherwise.
3. Do not discard, overwrite, stage, or commit another agent's work. Re-read any file immediately
   before patching it when concurrent work may be active.
4. Keep the change focused on the user's request. Diagnose first when the user asks only for analysis.
5. If concurrent agents need overlapping files, stop and coordinate through the orchestrating agent
   instead of resolving the overlap by replacing someone else's changes.

When multiple agents work at the same time, prefer a dedicated branch and worktree per task. Use the
`codex/` branch prefix for Codex-created branches. Never force-push, rewrite shared history, run a hard
reset, or delete another worktree. The integration agent owns the final merge, version bump, build,
and release artifact so those operations happen exactly once.

## Repository map and boundaries

- `android/`: Kotlin and Jetpack Compose Android client.
- `gateway/`: public HTTPS/WSS relay on the Hong Kong server.
- `connector/`: outbound-only macOS bridge to the local Hermes service.
- `protocol/`: shared TypeScript wire protocol used by Gateway and Connector.
- `deploy/`: deployment and service templates; never store live credentials here.
- `docs/`: architecture, environment shape, deployment record, and smoke-test instructions.

Changes to the shared protocol must update its tests and every affected consumer. Preserve the core
security boundary: the Mac opens the outbound connection, the Mac Hermes credential stays on the Mac,
and the Android app receives only the public Gateway URL and its dedicated app token.

## Secrets and production safety

- Never commit or print passwords, API keys, app tokens, connector tokens, cookies, private keys,
  keystores, or live `.env` contents.
- `environment.md` is private local context. It is intentionally ignored by Git. Use it only when a
  deployment task explicitly requires it, and never copy its secrets into source, logs, commits, or chat.
- Keep real service configuration in the protected files described by `docs/ENVIRONMENT.md`.
- A source-code change does not authorize a production deployment. Deploy or restart remote services
  only when the user explicitly requests it, then follow `docs/DEPLOYMENT.md` and verify health.
- Preserve unrelated services and ports on the HK host, including DERP and Xray.

## Android version and APK release rule

The Android version source of truth is at the top of `android/app/build.gradle.kts`:

- `appVersionName`: user-facing semantic version, currently advanced by patch releases.
- `appVersionCode`: strictly increasing Android update number.

For every APK actually handed to a tester or user:

1. The integration agent increments `appVersionName` by one patch version and `appVersionCode` by one.
2. Update the matching version note in `android/README.md`.
3. Run the Android unit tests and debug build.
4. Deliver only the staged artifact from
   `android/app/build/outputs/apk/distribution/debug/Hermes-Remote-<version>-debug.apk`.
5. Never hand off the canonical unversioned `app-debug.apk`.

Do not bump the Android version for documentation-only or server-only work when no new APK is being
distributed. With concurrent agents, only the integration agent performs the bump after all Android
changes for that package have been integrated.

## Verification

Run checks proportional to the files changed. The normal baselines are:

### Gateway, Connector, or Protocol

```bash
npm run build
npm test
```

Use the smoke tests in `docs/SMOKE_TEST.md` when routing, authentication, REST tunnelling, WebSocket
streaming, or protocol behavior changes.

### Android

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For UI changes, also inspect the result on the configured emulator or a real device when available.
Do not claim device verification when only JVM tests were run.

### Before handoff

- Run `git diff --check` and inspect the final diff.
- Confirm `git status` contains no accidental secrets, generated files, or other agents' changes.
- Report tests run, tests not run, the versioned APK path when applicable, and any deployment performed.
- Commit and push only when the user or orchestrating workflow authorizes it. Never include
  `environment.md` in a commit.

## Documentation consistency

Update the relevant documentation when behavior, configuration, deployment, endpoints, or operator
steps change. `AGENTS.md` is the canonical agent policy; `CLAUDE.md` is only its Claude Code entry point
and must not carry a divergent copy of these rules.
