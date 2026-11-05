# GitHub flow audit · 2026-11-05

Read-only baseline: remote `main` at `324d32e77fd8d60f3d3aa4c52b7f35166929eb47`,
the latest 100 Actions runs (2026-10-29 through 2026-11-02), 40 recently updated closed PRs,
and selected job logs. This is a bounded recent sample, not a long-term performance benchmark.
No deployment or secret-value inspection was performed.

## Measured bottlenecks

| Evidence | Observation |
|---|---|
| [0.1.194 PR CI](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41320736013) | 6m20s, followed by a separate main run |
| [0.1.194 main CI](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41321319796) | 7m15s; Android 39 actionable tasks, 26 executed / 13 from cache |
| [0.1.194 release](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41322059956) | 8m48s: Node gate 71s, Gradle setup 16s, package gate 394s, publication 31s |
| [Release PR #728](https://github.com/guoshengliang-coder/hermes-remote/pull/728) | Created 15:20:30Z; release finished 15:45:00Z: 24m30s including handoffs, excluding local preparation and the feature PR |
| [0.1.187](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/40822282321) | 421s package step succeeded; publication then refused because main advanced |
| [0.1.192](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/41218922761) | Package succeeded; remote publisher rejected a version conflict |
| [Signed components](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/40876219296) | Most recent two attempts reached signing after builds/tests, then reported missing signing input; all three recorded attempts failed |
| [Official Desktop candidate](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/36737235023) | The only recorded run failed for missing certificate input before notarization; current secret provisioning was not inspected |

Selected jobs typically waited 1–8s between creation and runner start. This does not include
environment approval: 0.1.191's release was created at 12:58:01Z, but its job was only created at
13:10:21Z. SAST was roughly one minute, Gateway OCI roughly one minute; neither was the longest
path in the sampled Android PRs. Do not infer these observations apply to every future run.

Android configuration-cache restoration succeeded, but both 0.1.194 PR/main logs rejected reuse
because `app/build.gradle.kts` changed. Kotlin/KSP/main/test compilation and unit tests executed again.
The release also has different BuildConfig inputs from ordinary CI. Tag runs reported a read-only
Gradle cache and did not save their state. Dependency-cache hits alone are not compilation reuse.

The live protection read returned `enforce_admins.enabled=false`, `required_status_checks=null`,
`required_pull_request_reviews=null`; repository rulesets were empty. This corrects the old policy
document's factual snapshot, not its authorization rules. The merge script remains the operative gate.

## First implementation

- Check required release inputs by presence flags before dependency installation/building; keep raw
  credentials confined to their existing consumers. Reject early with `HR-RELEASE-008`.
- Check the Android public catalog and local metadata before building. Duplicate version name/code,
  non-increasing code, full/invalid/unavailable catalog fail closed. The public read is bounded and
  read-only; server conflict/locking and all package verification remain authoritative.
- Use the existing `npm run test:ci` gate for Connector and signed-component candidates. It builds
  each workspace once and retains all test suites, rather than invoking nested workspace builds.
- Emit `GATE_TIMING` for PR/main/publish waits and bound Android publication to 30 minutes.

`HR-RELEASE-008` is an operator-only workflow error. Android/Desktop/Web runtime models and wire
contracts do not consume it and need no code changes. No version allocation, key provisioning,
branch-protection change or production publication is included. No actual speedup on GitHub is
claimed until these changes run there.

## Follow-up proposals requiring separate scope/decisions

1. **Version-only validation.** Preparation, PR CI, main CI and distribution all run Android checks.
   A restricted metadata-only path could avoid some repeated full checks, but must prove the exact
   source diff, validate release metadata and retain trusted packaging. Update AGENTS/INTEGRATION
   and test classifications together before changing required coverage. No bypass is added here.
2. **Immutable release source.** Pin reviewed release identity so unrelated main changes need not
   invalidate a long build. This changes the current-main admission policy; define ancestry,
   component/contract impacts and concurrent-publication rules before implementation.
3. **Retry the exact artifact.** Separating packaging from upload could save a rebuild after transfer
   failure. Define authenticated artifact/gate binding, retention, current-main revalidation and
   same-version idempotence first. A public-index match alone must not bless an arbitrary artifact.
4. **Compilation/test profiling.** Measure per-task durations and output-cache misses, then consider
   isolating changing version/feedback configuration and splitting pure JVM from rendering tests.
   Sharing release output caches must not expose compiled credentials to untrusted PRs. Larger
   runners or an action-version upgrade need measured benefit, not just a cache-hit message.

## Verification

Focused fixtures cover all prerequisite flags, catalog collisions/capacity/schema, bounded streaming,
network-failure/timeout handling, local notes, safe bilingual serialization, workflow ordering and
unchanged merge verdicts with timing. Run the repository Node baseline as well. Actual protected
workflow/environment resolution, Apple signing/notarization and published artifacts require an
authorized future candidate/release; do not trigger them simply to exercise this optimization.

Local results for the first implementation: `npm run test:ci` passed with **932 passed, 33 skipped,
0 failed** (Protocol 27, Connector 176, Gateway 235 plus 32 conditional skips, release-server 41,
script tests 453 plus 1 conditional skip). Skips require disposable PostgreSQL and/or the opt-in
network-integration flag; these were not exercised. The focused workflow/preflight/merge/official
candidate selection passed **40/40**. Actionlint **1.7.7** accepted all four modified workflows;
YAML parsing and `git diff --check` passed. The baseline needed unsandboxed loopback listeners and
OpenSSL 3 selected from the existing Homebrew installation; the initial sandbox attempt failed on
`listen EPERM`. No Android/Swift runtime code changed, so no device/app builds were performed.
Protected GitHub release runs and production verification were not performed.

## Local command comparison · 2026-11-05

Measured the candidate worktree's identical source/dependencies on macOS 26.6.2 arm64, Node 22.23.2,
with the same OpenSSL 3 selection and conditional PostgreSQL/network tests disabled in both groups,
as in the macOS candidate workflows. Before each run only this session's untracked generated
`protocol/dist`, `connector/dist`, and `gateway/dist` were removed. Dependency installation, queueing,
environment approval, signing, archive creation and uploads were outside the measurement.
The machine/OS filesystem caches were not reset. Both commands run against the candidate source;
this isolates command deduplication, rather than comparing different source/test generations.

Old: `npm run build && npm test`; new: `npm run test:ci`. Order: old, new, new, old, old, new.

| Command | Three elapsed times | Median |
|---|---|---|
| Old | 70.261s, 72.707s, 73.187s | 72.707s |
| New | 62.864s, 64.970s, 71.372s | 64.970s |

Median difference: **7.737s (10.64%)**. Each run returned zero with
932 passing / 33 skipped tests and identical per-suite counts. Compile command invocations decreased
from eight to three. Only three observations per group were collected and the ranges overlap;
these are local measurements, not a statistically established GitHub-runner speedup.
The missing-component-signing-input preflight separately took 0.0272 / 0.0255 / 0.0247 seconds locally
(expected `HR-RELEASE-008`). That failure check was synthetic and did not inspect a protected secret.

This change affects Connector/component candidate build gates. Android already used `test:ci`, so
these numbers must not be applied to Android's successful release path. Its new public-catalog read
also adds a small request cost while enabling early conflict rejection. The 24m30s Android end-to-end
case remains a separate gate/critical-path optimization.

For actual GitHub measurements, use a temporary, branch-only `push` benchmark workflow with read-only
permissions and no protected environment, signing/upload actions or release tags. Run old/new in
alternating order on the same `macos-15` runner with fixed Node/dependency/source versions; retain
raw timings and test counts. No main merge is needed for a branch-push-triggered benchmark. A new
`workflow_dispatch` workflow alone must first exist on the default branch, so it is not the right
pre-merge bootstrap. Do not relax existing main-only release conditions to benchmark them.
The local comparison preceded the authorized GitHub experiment described below.

## GitHub experiment setup

The owner authorized a test-branch push and GitHub comparison on 2026-11-05. The isolated branch
`codex/github-flow-preflight` launched a branch-only `Node gate benchmark` workflow at commit
`46345973aae39cac9ff6872610422de40f0bee1b`, using
`scripts/ci/benchmark-node-gate.py`. It fixes Node to 22.23.2 and uses one `macos-15` job for all six
samples. Dependencies are installed once outside the measurement. Each sample removes only
untracked generated workspace outputs, executes a fixed command, retains raw logs and requires five
complete TAP summaries with equal test counts. Failed/incomplete samples are not reported as speedup.
The output includes exact source commit, host architecture and individual times, with a GitHub job
summary. Logs/results are retained as CI artifacts for seven days; they are not release packages.

The workflow has read-only repository permissions, no protected environment or release credentials,
and disabled persisted checkout credentials. It cannot be triggered by main pushes or release tags.
`HR-CI-001` is an operator-only benchmark failure; no client/server wire-error model changes.
Focused validation now includes 43 passing tests covering count equivalence, negative speedups,
safe output cleanup, structured errors and workflow isolation. Pre-push `npm run test:ci` passed: 935 passed, 33 conditional skips, zero failures.
Actionlint 1.7.7 passed for all five affected workflows. No main merge or publication is authorized by this experiment.

The temporary push-triggered workflow was removed after launching the immutable benchmark commit;
it will not enter main. Its isolation test was removed with it, while the reusable benchmark helper
and evidence/count/cleanup tests remain. The already-started run keeps its original checkout and
source identity. Ordinary PR CI for the measured commit passed with 973 passing / 5 platform skips,
including disposable PostgreSQL and opt-in Gateway network coverage; SAST and Website also passed.

Post-cleanup validation: 42 focused tests passed; full local Node baseline 934 passed / 33 conditional skips / zero failures.
