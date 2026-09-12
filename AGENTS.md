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
5. Any Android UI change must first read and follow `docs/DESIGN.md` (visual/interaction contract:
   colours, dark-mode rules, icon style, type scales, list/card conventions). When a design decision
   changes, update `docs/DESIGN.md` in the same change — the document leads, the code follows.
6. If concurrent agents need overlapping files, stop and coordinate through the orchestrating agent
   instead of resolving the overlap by replacing someone else's changes.

When multiple agents work at the same time, prefer a dedicated branch and worktree per task. Use the
`codex/` branch prefix for Codex-created branches. Never force-push, rewrite shared history, run a hard
reset, or delete another worktree. The integration agent owns the final merge, version bump, build,
and release artifact so those operations happen exactly once.

The integration worktree — the one holding `main` — belongs to the integration agent alone. No other
agent may edit files in it; every other task works on its own branch and worktree. An integration
agent that finds uncommitted changes there reports them and never commits or discards them. Merging
and pushing may continue when the change being integrated does not touch those files, but nothing is
built or published from that worktree: cut a fresh worktree at the release commit instead, so that
no one else's half-finished work can reach the artifact.

The integration agent must treat successful PR checks as a manual merge gate: inspect every check
reported for the PR, merge only after all have completed successfully, and verify the resulting
`main` checks before handoff. Do not use auto-merge as a substitute for this gate.

This is a manual gate by circumstance, not by limitation. `main` does carry branch protection, and
this repository is public, so required status checks and a merge queue are both available. But
`enforce_admins` is off and every agent merges as the repository owner, so protection does not bind
these merges: `required_approving_review_count` has been 1 the whole time, and PRs merge through
`gh pr merge` with zero approvals. Required checks configured today would behave the same way —
recorded in settings, binding on nobody. Turning `enforce_admins` on would make them bind, and would
equally make that review requirement bind every PR. So the gate stays human, and the reason it stays
human is a deliberate trade, not a missing feature: measured over 150 merged pull requests, two were
merged with a failing check and none was merged before its checks finished.

`docs/INTEGRATION.md` defines the cross-subproject rules: which paths belong to Android, Desktop or
Cloud; the contract surfaces whose change requires the other sides to be addressed in the same
branch; and the separation of the merge, version and publish gates. Read it before merging into
`main`, before bumping any version, and before treating a remote-ahead `main` as safe to merge.

## Repository map and boundaries

- `android/`: Kotlin and Jetpack Compose Android client.
- `gateway/`: public HTTPS/WSS relay on the Hong Kong server.
- `connector/`: outbound-only macOS bridge to the local Hermes service.
- `desktop/`: native macOS menu-bar GUI and future managed Connector Agent.
- `protocol/`: shared TypeScript wire protocol used by Gateway and Connector.
- `ops/`: deployment orchestration for the Gateway — config schemas and the candidate/switch/
  rollback state machine driven by `scripts/hermesctl.mjs`.
- `release-server/`: the service backing the public Android release index.
- `deploy/`: deployment and service templates; never store live credentials here.
- `docs/`: architecture, environment shape, deployment record, smoke-test instructions, the
  cross-subproject integration rules (`docs/INTEGRATION.md`), the Android UI design contract
  (`docs/DESIGN.md`), the upstream Hermes contract inventory (`docs/HERMES_CONTRACT.md`), the
  read-only incident runbook for session-state problems (`docs/DIAGNOSTICS.md`), and the hands-on
  guide for operating a physical Android device (`docs/DEVICE_TESTING.md`).

`docs/HERMES_CONTRACT.md` inventories what this repository consumes from **upstream Hermes** — wire
field names, RPC methods, text grammars, and one hand-copied constant — none of which we own or can
version-negotiate. Read it and run its upgrade checklist before adopting a new Hermes, and verify
claims against the Hermes source rather than against project notes.

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

1. The integration agent allocates the next version with

   ```bash
   node scripts/bump-android-release.mjs --notes-file <notes> --summary "<README entry>"
   ```

   which increments `appVersionName` by one patch version and `appVersionCode` by one, writes the
   `android/README.md` entry and the staged-APK filename, and adds `android/releases/<version>.json`.
   It refuses to allocate from a dirty tree or from anything but the current `origin/main`, and
   refuses a number that a release file or an `origin` tag already carries — the way 0.1.95 and
   0.1.97 were lost. Editing the three files by hand instead is still allowed, and still has to
   produce exactly the same result.
2. Confirm the version note in `android/README.md` reads the way you want it to.
3. Run the mandatory release gate from the repository root:

   ```bash
   ./scripts/package-debug-apk.sh
   ```

   This gate runs `git diff --check`, Android unit tests, the debug build, staged-artifact checks,
   APK package/version validation, signature verification, feedback-configuration verification, and
   SHA-256 generation. A successful `assembleDebug` by itself is not sufficient for distribution.

   Feedback-configuration verification exists because 0.1.120 shipped without the in-app
   "反馈与建议" entry and every other check stayed green: the entry is drawn only when the build
   carried `MISSIONGO_ENDPOINT` and `MISSIONGO_SDK_TOKEN`, which reach a local build through the
   gitignored `android/missiongo.properties`. A build host without that file therefore cannot
   produce a distributable APK, and should not try to: let `android-release.yml` build and publish
   from the repository secrets rather than copying credentials into a release worktree.
4. Deliver only the exact `ARTIFACT=` path printed after `APK_RELEASE_OK`:
   `android/app/build/outputs/apk/distribution/debug/Hermes-Remote-<version>-debug.apk`.
5. Never hand off, upload, or serve the canonical unversioned `app-debug.apk`.
6. For public hosting, use the versioned filename in the URL, download the full remote file after
   deployment, verify its byte size and SHA-256 against the release-gate output, and ensure any old
   unversioned URL does not serve an APK.

App updates and release publication must follow `docs/APP_UPDATE.md`. In particular, never edit a
release index by hand, serve an unregistered APK, reuse Gateway-authenticated HTTP clients for update
traffic, publish without the package gate, or accept an APK whose package/channel/version/size/hash/
certificate does not match the signed manifest. Only the integration agent bumps the version and adds
the current release description before invoking the publisher.

Publication must run from an isolated worktree with no concurrent writers. The publisher checks the
clean worktree and `HEAD == origin/main` both before the package gate and immediately before upload;
never bypass either check or publish from a shared active development worktree.

The current test channel temporarily uses one shared debug certificate, documented in
`docs/SIGNING.md`. `assembleDebug` runs `verifyDebugSigningKey` and must fail when the canonical key is
missing or different. Never bypass that check, generate a replacement debug keystore, or provision the
private key to another host without the project owner's explicit authorization.

Do not bump the Android version for documentation-only or server-only work when no new APK is being
distributed. With concurrent agents, only the integration agent performs the bump after all Android
changes for that package have been integrated.

## Verification

Every iteration must include a test-impact review before implementation. A bug fix needs a regression
test that fails for the old behavior whenever the boundary is testable; a protocol or API change needs
parser/consumer tests; and behavior removed or intentionally changed requires its stale tests to be
updated or deleted. Run the focused suite while iterating, then the component baseline below before
handoff. If a meaningful case cannot be automated, add it to the relevant smoke-test document and
state explicitly that it still needs device or production verification.

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

UI, notification and background-behaviour changes need more than that baseline. Verification is
layered, and the layers are **not** interchangeable — each covers something the others cannot:

- **L1 — JVM + Roborazzi screenshots.** Always available, and the only layer CI can run. This is
  the default gate for UI regressions: theme combinations, `fontScale`, density, layout.
- **L2 — attached physical device.** Real look-and-feel, gestures, and vendor ROM behaviour
  (notification delivery, background survival, battery optimisation). Run it whenever a device is
  attached. `docs/DESIGN.md` settles look-and-feel disputes on a device, never on an emulator.
  Several phones may be attached at once, and they do **not** substitute for one another: name the
  device a result came from instead of writing "verified on device", because ROM behaviour is
  exactly what differs between vendors. `ANDROID_SERIAL=<serial>` picks which one the tooling
  targets by default. How to operate a phone — which APK to install, each vendor's install
  confirmation page, the dev stack, and restoring the phone afterwards — is in
  `docs/DEVICE_TESTING.md`; write new device gotchas back there. Install with
  `scripts/dev/device-install.py`: it confirms install pages it has a recipe for, and on an unknown
  page stops, reports `NEEDS_ATTENTION` and waits for an AI session or a person to resolve it; the
  doc defines what may be confirmed and when a resolved page becomes a recipe. How many phones a
  change needs:

  | Change | Devices needed | Why |
  |---|---|---|
  | Visual, layout, copy | **one**, any | L1 already covers theme/`fontScale`/density combinations; the phone is here to settle final look-and-feel, not to be a matrix |
  | Notifications, background survival, battery optimisation, permission prompts | **every vendor ROM you have** | This is precisely where vendors diverge — a HONOR result does not carry to a Xiaomi. This is the reason to own more than one phone |
  | `targetSdk`-gated platform behaviour | **one device at or above `targetSdk`** (or L3) | Platform behaviour, not vendor behaviour; one device that reaches it closes the gap for everyone |

  To act on every attached device, read the serials from the probe rather than hard-coding them,
  and loop with `for` over a command substitution:

  ```bash
  for s in $(./scripts/dev/android-capabilities.sh --serials); do
    adb -s "$s" install -r <apk>
  done
  ```

  Both obvious alternatives silently run the body **once** on these machines. `for s in
  $HR_DEVICE_SERIALS` fails because zsh does not word-split an unquoted variable, so every serial
  arrives glued together. `... --serials | while read -r s` fails because `adb shell` reads stdin
  and swallows the serials still waiting in the pipe. (zsh does split a command substitution, so
  the form above works in zsh and bash alike.)
- **L3 — emulator.** Platform behaviour gated on `targetSdk`, clean-install state, and the
  size/density matrix. A device running below `targetSdk` cannot exercise those paths at all.

Host capability differs per machine, so never hard-code emulator memory, AVD names or build/boot
sequencing into files shared through Git — `gradle.properties` included, since CI runners read it
too. Ask the host at runtime instead:

```bash
./scripts/dev/android-capabilities.sh     # layer availability, device SDK vs targetSdk gap
./scripts/dev/emulator.sh start           # L3, self-tuned to the host tier
HR_FORCE_TIER=low ./scripts/dev/emulator.sh start   # exercise the small-host path anywhere
```

`HR_FORCE_TIER` (`high`/`mid`/`low`) overrides the measured tier. Use it when changing the tiering
logic: the low/mid branches are otherwise only ever executed on whichever machine happens to be
small, so they rot unnoticed on the machine you develop on.

When the probe reports a layer missing, `docs/DEVICE_TESTING.md` §7 says how to add it on that machine.

To give one machine a larger Gradle heap, set it in `~/.gradle/gradle.properties` (per-user, outside
the repository), not in the committed one — but do not expect it to speed builds up: measured, it
did not (`docs/DEVICE_TESTING.md` §8). Knowledge about hosts and devices that holds for anyone on
this project goes into that document, not into one machine's private notes.

Report which layers ran and which did not. Do not claim device verification when only JVM tests were
run. When a layer is unavailable on the host, or the attached device's SDK is below `targetSdk`, say
so explicitly in the commit message and the handoff notes rather than leaving the gap implied.

### Desktop

Desktop behavior and visual changes must update `docs/DESKTOP_PHASE0.md`,
`docs/DESKTOP_DESIGN.md`, and `docs/DESKTOP_TEST_PLAN.md` as applicable. Run on macOS:

```bash
npm run desktop:assets:test
npm run desktop:test
npm run desktop:app
```

An ad-hoc local app is not a distributable release. Do not claim Developer ID signing or notarization
unless the exact artifact has passed codesign verification, notary submission, stapling, and a clean
machine launch check.


### Before handoff

- Run `git diff --check` and inspect the final diff.
- Confirm `git status` contains no accidental secrets, generated files, or other agents' changes.
- For Android artifacts, confirm the signing certificate matches `docs/SIGNING.md`.
- Report tests run, tests not run, the versioned APK path when applicable, and any deployment performed.
- For Android UI or behaviour work, state which verification layers ran (L1/L2/L3) and which were
  skipped or unavailable on this host, naming the device or emulator each L2/L3 result came from.
  `./scripts/dev/android-capabilities.sh` prints that summary.
- Commit and push only when the user or orchestrating workflow authorizes it. Never include
  `environment.md` in a commit.

## User-visible error contract

All new or changed user-visible failures must follow `docs/ERROR_HANDLING.md`. This applies to the
Android UI and notifications, Gateway and Connector responses, deployment/update tooling, and any
new component added later.

- Every user-visible error must include a stable `HR-<AREA>-<NNN>` error code and a short,
  localized explanation. Never show a raw exception, HTTP body, or English-only fallback as the
  primary message.
- Register every new code in `docs/ERROR_HANDLING.md` before using it. Released codes are immutable
  and must never be reused for a different condition.
- Map low-level failures to the shared structured error model at component boundaries. Preserve the
  technical cause for diagnostics, but keep it behind a details/copy-diagnostics action and redact
  credentials and personal data.
- Provide a recovery action when one is meaningful (for example Retry, Reconnect, Open settings, or
  View details), and declare whether the failure is retryable.
- Add tests for the code, Chinese and English summaries, retryability, serialization when applicable,
  and diagnostic redaction. When touching legacy raw-string error handling, migrate the affected path
  instead of adding another unstructured message.
- Treat error-code review as part of every iteration's test-impact review and definition of done.

## Documentation consistency

Update the relevant documentation when behavior, configuration, deployment, endpoints, or operator
steps change. `AGENTS.md` is the canonical agent policy; `CLAUDE.md` is only its Claude Code entry point
and must not carry a divergent copy of these rules.
