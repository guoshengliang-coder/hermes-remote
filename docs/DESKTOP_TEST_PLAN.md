# Hermes Go Desktop test plan

The live-handoff increment adds nine Python tests and a separate frozen-source matrix: five
bound recovery prefixes (four entries plus an explicitly selected named home) and thirteen
refusals before copied-package initialization. The trusted launcher passes an anonymous socketpair,
read-only/unlinked private context FD and live exclusive lock descriptions. Run/source/home/entry/
role/domain and eleven staged input hashes are checked before challenge/grant/accepted/commit/
committed. Parent close retains child exclusion; pre-commit refusal retains actual parent exclusion.
Faults include changed bytes before/after hello, missing/fresh same-inode lock FDs, mismatched
challenge and discarded/altered accepted. Tests cover framing/deadlines/EOF/bounds, strict JSON/types,
private FD shape, directory replacement/links, bilingual redaction and a disposable mutant that
skips actual source validation and consequently admits changed source. Linux/macOS run both fixtures
and frozen sources. `liveBindingChecksVerified` is deliberately separate from false production,
durability, full-startup/global/upgrade certification. The context is not OS-sealed or a whole-tree
Git/runtime proof. PM remains injected. Post-commit lost final acknowledgement, re-exec/restart,
actual PM/native/cron/services and device/UI verification remain unverified. See the prototype README.

The shared-bootstrap increment adds eight Python tests and a separate eleven-input frozen-source
experiment: four entry modules × three loading forms, nine misses by a main-only guard, four
admitted writers and four inherited-exclusive recovery prefixes. Actual descriptors/OS locks test
parent-close custody, independent opens of the same inode, malformed/closed/read-only/foreign FDs,
replacement and the self-blocking shared-writer alternative. Removing flock verification or
reacquiring a shared writer makes the corresponding regression/matrix fail. PM/recovery remain
injected; native preloading, cron package boot, authenticated/durable handoff and full startup remain
explicitly false/unverified. No Desktop product behavior or device layer changes.

2026-10-15 CLI import experiment adds seven Python harness tests and a separate frozen-source
matrix: 12 paired invocation/argv cases reject before real bootstrap/scratch writes when guarded
early; placing the guard inside main permits those writes. The admitted prefix keeps a probe-owned
writer lease until controlled exit. Only temporary source copies are transformed. PM/recovery are
injected no-ops and execution stops after scratch setup; profile selection, complete CLI startup,
alternate writers/services and upgrade certification remain unverified. See the prototype README
for exact input hashes, routed entry surfaces and reproduction. No Desktop app behavior changes.

2026-10-15 external material recovery adds 18 dependency-store tests (the standalone Python case
is opt-in). Coverage uses actual private files/FD reads/hashes: exact bytes/owner execute bits,
source mutation/replacement with reset mtime, idempotency and run/source/digest binding, multiple
root labels/shared budgets, internal
relative links, external/absolute/dangling/cyclic/file-traversal link refusal, root aliases/overlap,
permissions/hardlinks/FIFO and count/byte/depth budgets, damaged archive/manifest/permissions/extra
trees, original deletion, new-only export, relocated live-home aliases and late destination races.
Replacing exclusive publication with ordinary rename makes the late-empty-destination regression
fail; the new implementation retains that directory and cleans partial staging. A self-compiled
native fixture runs from its export after deleting its disposable source. No live Hermes is run.

The opt-in case was separately run on this Mac with standalone CPython 3.11.15: read selected installed
bytes, archive/export into a disposable root, archive that copy, delete ONLY the disposable source,
export again, then run ssl/sqlite3/hashlib/json with -I/-S/-B and clean HOME/environment. It passed;
the installed interpreter was neither executed, removed nor modified. It tests this runtime's
material recovery on this OS, not an old Hermes environment or all external ABI/tool dependencies.
Copying Apple's system echo was an earlier failed experiment (SIGKILL), not a passed acceptance or
reason to re-sign/change a live binary. No automatic runtime launch occurs in the store.

```bash
swift test --package-path desktop --filter DesktopHermesUpgradeDependenciesTests
HG199_DEPENDENCY_PYTHON_ROOT=<explicit-standalone-cpython-root> swift test --package-path desktop --filter DesktopHermesUpgradeDependenciesTests.testOptInStandalonePythonSurvivesDeletionOfDisposableOriginal
```

The receipt is not yet wired into Machine/journal custody, and external-root discovery, full
dependency/native loader/OS/ABI coverage and original-reference activation remain manual acceptance
gaps. Upgrade/recovery controls remain disabled. Separately, maintenance-scope CI tests the real old
and candidate retirement modules with two isolated processes and an injected idle ledger: only the
committed process refuses admission; the other still admits work. This is a negative scope result,
never a writer proof. The user authorized an isolated upstream prototype on 2026-10-15; see
HERMES_MAINTENANCE_REQUIREMENTS.md and scripts/compat/upstream-maintenance-prototype/README.md.
Thirteen synthetic-participant tests use actual POSIX subprocess locks, plus a frozen retirement
module experiment; none represents complete upstream entry-point, profile or service integration.

The macOS 15 / Swift 6.1 CI initially crashed in the external-link rejection test. Its LLDB
backtrace identified the readlinkat Array/raw-pointer output boundary; explicit mutable element
storage fixes it, with the same adjustment for sysctl output. That existing test is the observed
failing regression on the old implementation. Temporary CI debugger steps were removed after
diagnosis. Local Desktop assets/test/app were rerun successfully (712, two opt-in skips); macOS 15 CI
also passed at buffer-fix commit 3c852f6f. Final-head CI is checked separately. The expanded Python
compatibility baseline passed 46 tests, including 13 prototype cases; the frozen-module experiment
also passed with both certification flags false.

Increment validation: Desktop assets/test/app passed (712 tests, two opt-in skips, zero failures);
upgrade-focused suites passed (111 at the preceding checkpoint, one opt-in skip), with the later
multiple-root/shared-budget case included in the final full baseline. The CPython opt-in rehearsal
passed separately, as did 33 Python compatibility tests and the actual two-version maintenance-scope
check. App codesign/resource-byte checks passed; ad-hoc only, without distribution/notarization or
clean-Mac UI/boot/service acceptance. No Node/Web/Android source changes or new local L2/L3 runs.

2026-10-14 serve activity increment adds 15 probe/transport tests and 2 readiness-consumer cases.
Injected process/HTTP fixtures cover supported one-process idle, admission/session/delegation/cron/
human-input busy ledgers, unavailable ledgers, old missing identity/boot fields, wrong PID/role/SPA/
protocol/numeric types, reused/dead/unscoped/changed-argv processes, changed listener, wrong boot,
malformed/duplicate/escaped/oversized/contradictory replies, unsafe token/absent private installation,
source/freshness and sanitized serialization. Actual private temporary launcher/token files test
unchanged reads plus token rotation/launcher permissions changing mid-probe. Three tests use actual
disposable HTTP listeners and
URLSession: fixed GET/private header, blocked redirect to another port with zero downstream requests,
receiving an unbounded body, HTTP refusal and a stalled peer reaching its resource deadline. Tokens
are synthetic; no installed Hermes or launchd service is read/stopped/changed by these tests.
Readiness cannot pass complete native observation without matching fresh activity; busy/unknown,
stale/foreign identity or an additional observed writer stays blocked even with a certified test target.
Neither proof flag becomes true. Reinstating the preceding native-only readiness guard makes the
missing-activity regression fail; restoring the new guard passes.

Run `swift test --package-path desktop --filter 'DesktopHermesUpgrade(Activity|Readiness)Tests'`.
On a disposable Mac, separately verify real upstream ledgers for running/queued turns, cron,
delegation and human prompts, compare host PID/birth/boot with the managed job, and confirm a
0.21.3 backend stays unknown. Exercise token rotation, source/service replacement and concurrent
update/recovery. This increment does not certify upstream retirement coverage or future writer
exclusion; full process/profile/CLI/gateway/scheduler closure and real service/boot/business acceptance
remain required before enabling upgrade/recovery actions. No new visual/device layer is introduced.

Increment validation: focused upgrade suites 88 passed; Desktop full baseline 694 tests with one
existing opt-in skip and zero failures, native assets and app build passed. The app is ad-hoc only;
no Developer ID/notarization/clean-Mac launch or distribution. Node/Web/Android were not rerun
locally for this Desktop-only increment; their preceding integrated baseline remains recorded below.
Real upstream ledgers/managed service/UI acceptance and L2/L3 remain unrun in this increment.

HG-199 maintenance automation adds real Connector process/loopback HTTP admission and streamed-body
drain and existing-channel prompt/answer/interrupt tests (old entry fails), Swift/Node shared
private-request fixture, zero-count ACK binding to
run/source/request/PID/instance and freshness, permission/link/duplicate/schema refusal, persistent
pause across restart/deletion, all-profile idle/cron/background/unknown-writer refusal, service
snapshots, all four original load sets, cancel/restore/resume, PID reuse/legacy conflict/unknown print,
wrong boot rejection, originally-unloaded bootstrap refusal, and jobs appearing during restore.
Service commands/boot/writer evidence use injected runners, not real
launchd or full writer discovery. Android L1 tests cover maintenance history/create/unsent send and
structured upload code/log redaction; Web HTTP/RPC/socket mappings share the registered bilingual code.
L2 A5DN9X4708H02629 is currently ADB unauthorized; L3 Pixel_API_37 is available but not exercised
for this error/admission change. No new visual layout or targetSdk behavior is introduced.
Still required on a disposable Mac: admission preparation with the published matching Connector,
active local CLI/cron and all profile writers, actual retirement, exact service load-set restoration,
external dependency closure, cold official update and committed-tail recovery, fresh same-binding
Cloud/business acceptance, and maintenance/recovery copy on Android/Web. The upgrade button stays off.

The Machine Operating composition adds 16 tests using real private journal/mailbox/service snapshot
files and home backup/hash verification. Worker execution, service commands, process/plan/dependency/
boot/receipt and business evidence are synthetic: no live Hermes or launchd is controlled. Coverage
includes full normal flow/worker lease teardown, terminal cursor archive retention, run-root/home
URL alias refusal, uncertified refusal, official-plan races and invalid profiles before effects, drained ACK with unknown writers, unavailable external closure, safe cancel,
wrong cancellation boot, cancellation business failure/re-pause/verification-only retry, original-ID
finish resume, target business failure retaining later history, receipt debt and damaged archives
before service restoration, and changed retained configuration before new worker acquisition.
Three additional private-configuration tests cover exact round-trip, missing/extra/duplicate/schema/
directory mutations, and symlink/public-permission refusal without launching a process. The focused
Machine/OfficialWorker/Maintenance suites contain 47 tests. Run with:

```bash
swift test --package-path desktop --filter 'DesktopHermesUpgrade(Machine|OfficialWorker|Maintenance)Tests'
```

Acceptance on a disposable Mac still requires a production live collector with independently
verified full writer inventory and external closure recovery, then the actual full coordinator flow
and process-death matrix. Confirm original IDs/configuration survive relaunch, the original loaded
service set and boot identity are restored, admission opens only for proof-backed business checks,
and failed checks re-pause without overwriting newly written history. Exercise cancellation failure
and retry with the original installation as well. No product upgrade/recovery control is enabled
by these fixture results.

2026-10-14 native inspection increment: 13 inspection tests, 4 additional Machine negative-guard
cases and 2 readiness-consumer tests. One inspection test starts a disposable Python sleep process
with synthetic argv/environment values, reads its actual kernel PID/birth/command association,
checks report serialization excludes those values and private paths, then terminates ONLY that test
process and confirms disappearance. No Hermes or launchd job is started/stopped. Other tests inject
kernel identities/argv and use actual temporary profile directories/interpreter links: argc bounds,
empty arguments, environment-tail non-decoding, missing/changing/PID-reused snapshots, descendants,
unscoped modules/selectors, exact original-service births, freshness/source binding, ghost shells,
links/invalid names/write permissions/bounded listings and external interpreter observations.
Production native inspection is an extra negative guard, not a writer fence. Machine tests reject
an unowned candidate before any stop, an original-service survivor after stops, a writer around
backup and one before source movement; those process/service effects are injected.
Readiness tests prove no journal creation, no inspection during pending update/recovery, and that
certification cannot hide an unavailable profile scan. Combined focused suites contain 69 tests:

```bash
swift test --package-path desktop --filter 'DesktopHermesUpgrade(Inspection|Readiness|Machine|Maintenance|OfficialWorker)Tests'
```

Still required: live all-profile task/admission closure and retirement, unknown/orphan/custom-home/
other-user writers and open descriptors, PM/external interpreter closure recovery, fresh boot and
same-binding Cloud/business proof. Native PID observation alone cannot certify any of these, and
absence of candidates never proves safe upgrade. UI/card/device acceptance and full disposable-Mac
upgrade/recovery remain pending; no product execution action is exposed.

## HG-199 first-stage verification

Automated coverage: legacy and PM layouts, missing/broken committed generation, stale stamp,
same-commit generation changes, exact input timestamps, PM launcher environment, v2 live locks
older than 20 minutes/delegates/PID reuse, annotated release tag resolution, target decoding,
latest versus exact-commit certification, and bilingual error catalog. The source matrix separately
checks pinned old/new contracts and synthetic DB old→new→old reads/writes plus backup recovery to
another file. It is not an official updater/service recovery or production-data rehearsal.

Local baseline: `desktop:assets:test`, `desktop:test`, `desktop:app`; the app is ad-hoc only and
has no Developer ID/notarization claim. A migration test now uses its fixture shutdown probe rather
than probing the owner's real 9119 when checking the 75-poll cold-start budget.

Still needs physical Mac acceptance: light/dark card layout, independent GO/Hermes checks when the
GO index is absent, network failure clearing old success, unrecognized layouts, very long version
strings and stable release with no certified recommendation. Do not launch a test app against the
owner's services to satisfy this checklist; use an isolated Mac/home with managed fixtures.
HG-199 stays in progress until stage-two upgrade/recovery and stage-three cross-client acceptance.

Stage-two foundation automation covers write-ahead order, immutable targets/channels, tested
source→target gating, source movement between inventory and lock, concurrent clicks, failure at each
effect and reconstruction of a coordinator for resume, safe cancellation, UI task cancellation after
commit, malformed/future/dangling journals, and startup/migration/repair lease guards. Effect failures
are injected, not actual process-kill tests against the full updater. Git fixtures check tracked and
untracked modifications and unchanged index timestamps. SQLite fixtures verify committed WAL data,
private config/cron snapshots, repeatable backup receipts, source changes/tampering, symlink preservation,
separate-directory DB export and preservation of later live writes. Completion tests reject exit-zero
success with followups and require independent service/boot/environment evidence.

The local macOS official PM prepare experiment uses a cleared environment and temporary home;
finish additionally runs in a kernel sandbox denying signals, launchctl/osascript and writes elsewhere.
Completion tests preserve the observed receipt fields and reject missing launcher correlation and
remaining debt after correlation is supplied. The experiment proves PM provisioning and the owed-work
receipt shape; denied service restoration prevents certification. It has no genuine old venv/history.
The bundled Swift/Python worker adds private configuration/environment, exact single command, task
cancellation, malformed IPC, symlink refusal and immutable upstream-ID journal tests. Python boundaries
cover duplicate JSON, private/link ownership, journal/source/target/version/backup binding, hidden index edits,
ignored Python overlays across the snapshot, executable Git filters/rewrites and refusal to overwrite committed context.
IPC lines over 4096 bytes are rejected even when a newline arrives in the same read. Release-tag tests
require a tag that peels to the pinned commit and retain conflicting owner tags without fetching.
Finish-resume automation adds immutable original handoff/ID/plan/PM validation, private write-ahead
preparing→ready→finishing checkpoints, context hash binding, original-context retention, refusal for
prepare failures, existing result, duplicate/linked/unbounded archives, probe-time changes, advanced
or terminal debt, and a single mutation per Swift lock handle. A live source check permits root PM
state/legacy venv and known Node dependency directories while still refusing ignored Python overlays
and an importable venv package. Invalid/oversized currency replies cannot choose an interpreter. Stable recovery-required
mapping is reused; raw refusal details never become a primary message.

`scripts/compat/hermes-finish-resume-rehearsal.py` is opt-in on macOS/Python 3.11+ with a pinned
candidate source and an explicitly marked disposable `/private/tmp/hg199-*` tool/cache seed. It
creates a fresh target checkout and synthetic GO journal/backup receipt, calls actual official PM
prepare through the packaged worker helper, SIGKILLs that isolated preparer at the durable ready
handoff, then sends `resume-finish` to the packaged worker. Kernel policy denies all signals, service
tools and writes outside the new fixture. This tests the actual PM/finish handoff ONLY: it does not
prove an old→new source move, backup validity, genuine history, cold tool provisioning, GO writer
retirement, original service restoration or a certified upgrade. Inspect the reported fixture/logs;
never point it at the owner's data. Terminal service debt must remain and a second finish resume
must refuse without changing the original context/receipt.
`--mode update` additionally starts a synthetic source checkout at 17b5df02 and exercises the normal
fixed-target source move/packaged child entry without killing prepare. It uses an external Python
3.11 test interpreter, warm tools and injected journal/backup, not a genuine installed old venv.
Local 2026-10-11 runs passed both paths: SIGKILL after PM ready→official finish, and ordinary source
update→official prepare/finish; same IDs/target version and original-context/terminal-debt retention
passed. Read-only verification independently rejects the finishing checkpoint before any replay.
Official maintenance attempted to extend shallow history and left partial-clone config; the
previous packaged worker's config guard also refused the repeat. Do not remove those flags/guards
to simulate recovery. The subsequent worker increment accepts only the standard live origin/blob:none
pair (optional old extensions.partialclone=origin), leaving frozen candidates strict. Unit fixtures
cover absent commit/tree/Python and non-Python blobs, missing historical blobs, duplicate/nonstandard
metadata and VCS helpers. Missing objects cannot launch a planted promisor helper; transport denial
also holds when the no-lazy-fetch variable is deliberately ignored. Inherited Git path/config
overrides are cleared; only explicit verified-snapshot fetch admits file transport. The currency
probe also denies Git transports, without imposing that temporary fence on finish's explicit fetch.
Read-only checks of both retained official partial checkouts passed without altering Git config.
The opt-in rehearsal now requires a post-finish offline source check and the actual repeat command
to reach finish_checkpoint_not_ready, not just fail at the old partial-config guard. Full upgrades,
other partial layouts and missing-object repair remain uncertified.
Both fresh follow-up modes passed: official finish left origin/blob:none metadata, post-finish
offline source validation kept the config intact, and the actual repeat command reached
finish_checkpoint_not_ready while preserving context/receipt/debt. Exact IDs are in HERMES_UPGRADE.md.
Both fixtures keep gateway restart owed; neither certifies services, backups, business or GO completion.
A retired external WAL-writer regression reproduces Apple SQLite's missing-sidecar backup failure;
only a source with no pending WAL/rollback pages gets immutable reading. Nonempty WAL coverage remains.

`scripts/compat/hermes-update-lock-matrix.py` runs actual pinned official classes in disposable homes:
GO bridge exclusion, cross-home/linked-worktree exclusion in real Git fixtures, SIGKILL of the isolated official lock owner while
its inherited-custody child survives, reclaim after child exit, and pre-move debt keyed to the live
installation with the frozen helper's original module origin restored. Local macOS passed; CI covers
macOS/Ubuntu. This deliberately does not terminate the owner's services or a whole PM transaction.
`DesktopHermesUpgradeRehearsalTests` is opt-in via an explicitly marked disposable `/tmp/hg199-*` home
and an independently pinned source checkout; ordinary baseline skips it. It is not production approval.
The source snapshot must include the exact official release tag and a self-contained bounded Git graph,
not just a detached commit or partial-clone metadata. Provision the disposable old venv using the old
official installer, restrict writes/signals/service execution with a kernel sandbox, and keep the
marked home and verified source outside the owner's installation. The opt-in test checks original
branch preservation, actual official IDs/version/PM receipt, owed service recovery, old history,
new history and separate backup export. It intentionally leaves GO's journal pending.
Local 2026-10-11 evidence: official old installer produced a genuine 0.21.3 venv; the worker moved
that backed-up installation to 818c13be and official PM/finish completed with version 0.21.6, retaining
the original branch and distinct GO/upstream IDs. The post-check initially omitted official PM
activation; after adding `hermes_bootstrap`, `HG199_REHEARSAL_VERIFY_ONLY=1` continued verification of
the same committed run and sealed backup. Old history, new writes, separate export and preservation
of later live writes passed. This split run used a warm tool cache, not a fresh all-green cold-install
test. Sandbox-denied gateway restart remains owed; the GO journal stays updating. See the exact IDs
and limitations in `HERMES_UPGRADE.md`.
The version-completion regression demonstrably accepted missing/unknown/old version before the fix;
afterwards all three fail with `HR-HERMESUPDATE-004` even when the commit and other evidence match.

Still needed: a fresh uninterrupted cold old→fixed target run and
real stopped-writer inventory/retirement, process-kill boundary matrix, original service restoration,
full history/Q&A/stream/cancel/attachment/approval/clarify/cron acceptance and physical Mac UI review.
The new condition rows also need light/dark review on that isolated Mac. Do not launch this app against
the owner's production services merely to satisfy the visual check.

## HG-212 PM runtime and recovery follow-up

Automated coverage adds: PM plus stale legacy venv (the three detector cases failed on old code),
PM-only install, unsafe PM file/parent refusal, PM agent recognition/PATH/no VIRTUAL_ENV/token
preservation/exact legacy rollback, actual command-loading success/import failure/bounded process
termination, completed-stage verification failure remaining pending and verification-only retry,
public health failing authenticated JSON proof, loopback route/token/redaction/redirect refusal,
account report time surviving a newer refresh, diagnostic button wiring/source labels, exact owned-binding
reconnect eligibility, Connector-only restart with unchanged identity/plists/Hermes, stale Cloud
health refusal, local authentication recheck before mutation, and missing-job refusal. Existing suites retain update lock/settle/backoff and
unknown-job, account, credential, concurrent-lease and interruption rollback gates.

Physical acceptance is still required before distribution:

1. Upgrade a Mac with old Python 3.11 venv and a valid published PM command: GO uses PM, one serve
   holds 9119, the same token/home/state.db remain, and private authenticated reads pass.
2. Run upstream's supported update while GO is active; prove no restart during its lock, correct
   post-update process/command, no duplicate 9119 service and no stale generation path in GO config.
3. Restore local Hermes while retaining an older negative account health report. “重新诊断” must
   fetch account state, show separate local success and report time, and retain Cloud failure until
   its newer report succeeds. Failed account refresh shows its code, not a false new timestamp.
4. Confirm “重新连接这台 Mac”: only the proven GO Connector PID changes; Hermes PID, SSH client,
   database and token remain unchanged. Fresh Cloud health is required; another/shared Mac and
   concurrent installation never offer/execute this action. Test interruption/rollback UI.
5. Keep the notebook SSH session open while iPhone Safari/PWA lists history and sends/receives a
   new message. Customer reported Desktop recovered after Connector restart; this end-to-end gate
   has not been reported or executed by this iteration.

Local verification on Macbook-M5: the full Desktop core suite passed (563 tests), canonical asset
comparison and ad-hoc app build passed. The actual Diagnostics and reconnect-confirmation SwiftUI
views were rendered with static fake health data and inspected in light/dark mode; the harness did
not read Keychain or start real services. This is visual layout evidence, not device/Cloud acceptance.

## HG-212 SSH/PWA startup-failure acceptance

`DesktopHermesReadinessTests` covers fresh Python/generic startup failures, separate port-conflict
evidence, fixed diagnostics without credentials or personal paths, existing bilingual runtime issue,
retryability/recovery, historical stderr, nonfatal warnings, incomplete lines, log rotation, symlinks
before/after checkpoint, and bounded growth. The Python/port regressions failed on the old checker.
The ready-marker + healthy-loopback requirement remains covered. Full Desktop tests also exercise
update waits, restart bounds, occupied-port refusal, exact managed labels and rollback paths.

Customer SSH/PWA simultaneous use, actual upstream/venv repair, packaged Desktop details, GO-only
restart preserving SSH and full Mac reboot remain **unverified**. Execute and record the acceptance
steps in [SSH_MOBILE_RECOVERY.md](SSH_MOBILE_RECOVERY.md) on an explicitly authorized customer/test Mac.

## HG-175 repair acceptance

Automated `DesktopManagedServiceRepairTests` covers the 16 combinations of file presence/job loading,
read-only preparation/cancellation, same-binding credential/token/journal preservation, stale handles,
changed release/reference/snapshot/account, unknown/duplicate jobs, occupied port, unsafe files,
operation lease, local launcher and v2 component reuse, readiness before Connector, fresh cloud health,
rollback and restart recovery. Error tests cover bilingual summaries, retryability, serialization and
private-key redaction. Tests use injected commands/probes; they do not modify this Mac's services.

Still requires an explicitly authorized test Mac and real account:

1. Back up its managed agents and installation. With the same committed binding, remove both agents,
   remove each one separately, and unload a task with its file retained. Verify the repair entry and
   confirmation name the original version/runtime and exact files; cancel and verify no changes.
2. Confirm repair. Check existing files remain identical, Hermes is ready before a new Connector,
   both managed tasks load and the original binding generation returns fresh end-to-end healthy.
   Verify the phone still reaches existing sessions/files and the Hermes database is untouched.
3. Repeat with another account, missing credentials, unsafe release evidence, unknown launchd
   arguments and an unrelated process on 9119. Each must stop before machine changes and explain
   the recovery step. Do not delete the cloud binding to work around the block.
4. Interrupt a confirmed repair and simulate a start/health failure. Verify snapshot retention when
   restoration fails, explicit “恢复上次操作” after reopening, unchanged identity and prior service/file
   state after successful restoration, then a separately confirmed repair. Check blocked recovery
   preserves externally changed files/jobs and the snapshot.
5. Inspect the real repair card/sheet in light/dark mode, long file paths, pending progress and blocked
   recovery. The isolated confirmation preview is a layout check, not this live acceptance.


## Automated baseline

Run on macOS:

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
```

The current automated suite covers:

- overall status reduction and optional-capability degradation;
- legacy Connector config parsing with a strict non-secret allowlist;
- current Gateway WSS to Relay health URL conversion;
- legacy launchd/install/log discovery through injected command execution;
- managed Connector and Desktop log discovery, bounded redaction, and symlink refusal;
- log/header/query/known-secret redaction;
- profile validation that rejects insecure remote HTTP, URL credentials, queries, and fragments;
- that the staged Hermes component is importable by a **child** process with no `PYTHONPATH`
  (`scripts/test/desktop-managed-python-path.test.mjs`) — upstream strips the repo root out of every
  child environment, so a bundle that relies on `PYTHONPATH` alone cannot run slash commands;
- exact Android v1 JSON payload compatibility;
- Core Image QR output decoded back to the exact v1 payload with the native QR detector, including payload-size rejection;
- authenticated `/api/status` request path and header behavior;
- 401/403, offline Connector, and unmapped Relay error classification;
- bilingual error code, retryability, recovery-action, and diagnostic redaction contracts;
- recent-log warning counting independent of current health;
- full SwiftUI target compilation.
- Desktop account capability discovery and default-off behavior;
- PKCE S256, cryptographic state/nonce, provider cancellation, state mismatch rejection, and a real
  ephemeral `127.0.0.1` loopback callback;
- account HTTP paths/headers, bounded responses, stable error mapping, and diagnostic redaction;
- email sign-in challenge/exchange paths, normalized email, stable installation identity and
  exchange idempotency, macOS session validation, and bilingual `HR-AUTH-009` through
  `HR-AUTH-011` recovery contracts;
- email-only gray-rollout capability gating: after a successful exchange, disabled installation and binding routes
  are not requested, the session remains stored, and the account summary reaches the signed-in state;
- separate account-session and Connector-machine identity stores, Ed25519 challenge signing, access
  refresh, and persisted idempotency-key reuse after a lost refresh response;
- signed-in dashboard reduction, two-phone listing, current-account-email reauthentication before
  one-phone removal, rejection of non-phone targets, and Desktop-only sign-out without deleting the
  machine identity;
- bounded phone-revocation request bodies, scoped `account.installation.revoke` grants,
  Keychain-preserved exact retry after a lost response, and bilingual `HR-ACCOUNT-009` mapping;
- capability-gated permanent Cloud deletion, exact `DELETE /v2/account` body/headers,
  `account.delete` email reauthentication, explicit acknowledgement, Keychain-preserved exact retry
  after a lost response, account-session removal on success, and Connector machine-identity
  preservation;
- capability-gated whole-device sharing routes and decoding, owned/operator device separation,
  owner-only share lookups, exact disclosure acknowledgement, invite-link validation, bilingual
  `HR-SHARE-001` through `HR-SHARE-008` mapping, and credential redaction;
- fresh-email-code `device.share` reauthentication plus Keychain-preserved idempotency/grant recovery
  after lost reauthentication or invitation responses, without persisting the six-digit code;
- strict signed Desktop manifest parsing, pinned Ed25519 keys, tamper/expiry/origin/architecture and
  unknown-field rejection;
- schema-v2 component manifests pin compressed and extracted identities, activation/reuse contracts,
  entrypoints and content-addressed dependencies; Swift and the offline verifier reject unknown or
  duplicate components, dependency identity mismatch, cycles, and invalid compatibility fallbacks;
- resumable v2 component downloads require exact 206/Content-Range semantics, retain only an
  owner-only partial file after interruption, re-read the whole completed file for signed size and
  SHA-256, and remove a poisoned full-size partial;
- offline managed-release publication with owner-only Ed25519 key custody, exact two-archive output,
  independent public-key verification, no-overwrite/partial-cleanup behavior, and redacted
  `HR-RELEASE-004` diagnostics;
- component-archive construction from exact clean Git identities, an allowlisted secret-free Hermes
  source/runtime boundary, production-only Connector JavaScript, bundled architecture-matched
  runtimes, relative launchers, bounded trees, and no-overwrite cleanup;
- schema-v2 component archive construction keeps CPython/bootstrap dependencies, Hermes core, Node,
  Connector JavaScript, and any prepared browser/speech/document roots in disjoint archives; validates
  clean source commits, runtime versions, architectures, executable optional health entrypoints, fixed
  optional dependency edges, and rejects Homebrew/unresolved dynamic-library dependencies; reports
  compressed and normalized content hashes plus separate bootstrap/deferred bytes; removes only artifacts created by
  a failed attempt; and preserves HG-28 child imports through the activation-time core-root `.pth`;
- the arm64 core-only composition starts the real Hermes 0.21.0 server, emits its readiness marker,
  serves `/api/status`, imports its core API modules, and imports `tui_gateway` from a child interpreter
  after `PYTHONPATH` is removed; the measured bootstrap set is 98.12 MiB compressed;
- schema-v2 activation rehashes all four bootstrap components and their owner-only receipts, requires
  executable owner-safe entrypoints and successful bounded health probes, and rejects missing
  components, unexpected bootstrap kinds, or any deviation from the exact runtime dependency graph;
- component LaunchAgents use content-addressed Hermes/Connector entrypoints and inject only the exact
  Python/Node managed roots; v1 writers reject v2 runtime injection and v2 writers reject paths from a
  different managed layout or content mutated after planning;
- the v2 component installer accepts only a strict-verifier installation token, orders fresh
  Python/Node/Hermes/Connector acquisition by dependencies, reuses a second exact install without
  network access, and publishes the release reference only after the final activation plan passes;
- v2 component preparation downloads and extracts only into its private workspace, leaves the managed
  store and release reference absent before commit, binds commit/cancel to the issuing installer,
  rehashes staged content after the confirmation boundary, and publishes no reference after tampering;
- extraction or health failure removes only the current UUID workspace and leaves no release
  reference; a transport interruption instead preserves its partial download and owner-only manifest
  identity marker, clears completed archives/extractions before retry, allows only the same signed
  manifest and run ID to resume, and lets the bounded cancel path remove that exact marked workspace;
- the component preflight coordinator reports all bootstrap downloads and deferred optional bytes on
  a clean store, reuses only rehashed healthy managed content, permits a compatible probed external
  browser while keeping observed Python non-reusable, and stops before external scanning for an
  unhealthy managed component or invalid signed bootstrap topology;
- the component preflight presentation maps managed/system reuse and bootstrap/on-demand downloads to
  distinct bilingual rows, reports stable binary byte totals, names every supported component kind,
  and compiles as a native SwiftUI card without exposing an unwired install action;
- offline component-release publication safely extracts every candidate and recomputes the same
  relative-path/file-byte/executable-bit identity used by the Desktop store before signing, then the
  public-key-only verifier repeats compressed and extracted identity checks;
- componentized-install preflight planning: exact content identity is required for mutable runtimes,
  a version-only external Python is rejected, an explicitly compatible healthy system browser may be
  reused, wrong architecture/digest/failed probes are rejected, optional capabilities are deferred,
  and only missing bootstrap components contribute to the initial download total;
- compatibility requirements retain an exact managed fallback identity, allowing a downloaded optional
  component to be reused after external compatibility disappears;
- shared-component inventory rehashes relative paths, bytes, and executable bits before reuse;
  stable trees produce stable identities, byte/mode changes invalidate them, and symbolic links,
  unsafe receipt permissions, receipt mismatches, and tampered trees fail closed;
- shared-component commit stages content plus its receipt under one private UUID, rehashes and probes
  the staged copy, atomically exposes both together, is idempotent when exact content already exists,
  records deterministic release references, and removes only the current workspace on probe failure;
- optional-component references can be recorded only for browser, speech, or document content that
  already exists under a valid base release; exact retries are idempotent and a conflicting identity
  for the same release/kind is rejected;
- first-use component installation requires a verifier-only v2 token and an exact signed trigger,
  validates the base reference and all bootstrap health before scanning or network access, installs
  optional dependencies in topological order, reuses managed content or a revalidated system browser,
  publishes capability references only for managed content, resumes only a manifest/trigger-bound
  transport interruption, and safely cleans extraction failures or explicitly discarded workspaces;
- optional runtime projection probes the exact managed Python ABI, sorts multiple signed Python
  component roots into one content-addressed read-only `.pth` target, is idempotent for identical
  input, injects the target and browser executable through the upstream Hermes environment contract,
  and rejects external Python roots, duplicates, symlinks, unsafe permissions, failed probes, or a
  mutated existing projection;
- first-use runtime activation shares the migration operation lock, requires the exact committed
  account release and healthy managed service topology, replaces only the Hermes LaunchAgent,
  restarts and proves the new Hermes runtime, restores the exact previous plist and re-proves the old
  runtime on failure, and invokes the capability retry exactly once without rolling back a healthy
  activation when that retry itself fails;
- active optional-component resolution preserves previously referenced managed capabilities across a
  later independent trigger, revalidates each identity, receipt, content tree, entrypoint, and health
  probe against the signed release, and retains a current external browser only when its exact
  owner-only LaunchAgent path passes a fresh signed-compatibility scan;
- the default-off capability coordinator maps only browser/speech/document kinds to exact triggers in
  the verifier-backed manifest, orders install → bootstrap-plan regeneration → locked activation →
  one retry, rejects unsupported kinds before work begins, stops after install failure, and admits
  only one concurrent request;
- the default-off preflight runtime accepts only a fixed unambiguous HTTPS manifest URL, orders bounded
  manifest fetch → strict schema-v2 signature verification → read-only environment scan, prevents
  download/signature failures from reaching the scanner, and rejects overlapping refreshes;
- the trusted preflight session retains the exact verifier-only schema-v2 token beside the matching
  scanned presentation result, while the compatibility load path exposes only the ordinary result;
- the component bootstrap state machine rejects mismatched trusted input before cache/network work,
  writes only the private preparation before exact release confirmation, rejects foreign preparation
  handles, and then passes the committed content-addressed activation plan with exact Python/Node roots
  to one migration boundary; cancellation and migration failure remove the UUID workspace, inactive
  immutable references remain reusable/collectable, and post-success cleanup failure returns a bounded
  retry handle without reclassifying the committed migration; a transport interruption before handle
  issuance is removable only by the UUID-and-private-marker checked cleanup path;
- migration-journal schema 2 binds each run to bundled-release or component-store layout across every
  state transition, rejects a same-run layout change and malformed/unknown schema-layout combinations,
  reads historical schema 1 strictly as bundled-release, and atomically upgrades that legacy record on
  its next valid transition;
- the concrete component migration adapter rejects manifest/activation-plan mismatch before binding or
  filesystem mutation, starts the content-addressed Hermes entrypoint before Connector, commits only
  after local and cloud health, persists `component_store`, injects the exact Python/Node roots, and
  neither creates a bundled version directory nor changes `current`; failure and interrupted recovery
  restore legacy service state while preserving an unrelated bundled `current`, and the existing bundled
  recovery test still proves that a v1 candidate deactivates its expected link;
- the composed schema-v2 bootstrap runtime binds the trusted preflight, component installer, migration
  journal/controller and content-addressed commit configuration to the same managed paths and account
  boundary; construction preserves an absent managed root, cache, LaunchAgents directory, and Hermes
  home, proving that merely enabling dependency composition cannot start a download or mutate the Mac;
- the component-preflight package gate is false with an empty v2 URL by default, loads only a complete
  HTTPS URL plus canonical pinned release trust inputs, keeps the schema-v1 URL separate, and rejects
  partial, malformed, credential-bearing, or ambiguous configuration;
- the read-only managed-entrypoint probe requires the signed entrypoint to stay inside the rehashed
  component root as an owned, non-writable, regular executable file, rejecting paths outside the root,
  symlinks, non-executable files, and group-writable files without launching a process;
- fixed-path external-environment scanning reports Python/Node without trusting version-only reuse,
  resolves Homebrew-style runtime symlinks for reporting only, admits a safe architecture-matched
  system browser only after an explicit compatibility probe, and rejects browser symlinks,
  group-writable executables, failed/oversized probes, and architecture mismatch;
- read-only component garbage-collection planning retains every bootstrap and optional capability
  identity, permits identical optional content to be shared across base releases, requires current and
  rollback reference sets supplied by the caller, validates component content again, and fails closed
  on orphaned/unsafe references, unknown kinds, missing components, symlinks, or tampered content;
- redirect-free bounded downloads, exact size and streaming SHA-256 artifact validation;
- tar member preflight, including traversal/link/special-file rejection before extraction, realistic
  dependency trees above the former 4,096-entry limit, and rejection beyond the new 65,536 bound;
- private credential/LaunchAgent writes, immutable release staging, atomic activation and rollback;
- a rolled-back, app-marked inactive release can be atomically replaced by a freshly verified
  same-version retry, while active, unmarked, mismatched, or unsafe directories remain immutable;
- a terminal rollback can resume an already committed Cloud binding only when its recorded ID,
  generation, and retained machine-key fingerprint all match; it performs no replacement or second
  remote confirmation, while every mismatch remains blocked;
- acquisition ordering and lifecycle: private roots exist before verifier pinning, both signed
  components are required, digest/extraction failures remove only the current UUID workspace, and
  explicit discard preserves the parent workspace;
- managed-bootstrap preparation performs acquisition only and returns the exact signed release plus
  confirmation; commit rejects wrong or foreign preparations, while cancel and terminal paths discard
  the private workspace;
- launch configuration is derived from verified manifest entrypoints, the managed layout, frozen
  runtime, and HTTPS account origin rather than caller-supplied executable paths;
- committed cleanup trouble retains a one-purpose retry handle, maps to `HR-MIGRATE-005`, and neither
  claims rollback nor offers a second install;
- exact legacy/account user LaunchAgent labels, duplicate-Connector prevention, health-gated binding
  confirmation, lost-response idempotency, automatic rollback, ambiguous-commit stop, and restart
  recovery;
- persistent legacy-label disablement before managed startup, matching re-enablement before rollback,
  and startup repair that suppresses a Migration Assistant-restored duplicate only when an exact
  `account_active` journal and both managed services prove the committed authority;
- bounded launchd convergence after every managed bootstrap/bootout, including delayed legacy
  removal before managed startup or rollback decisions;
- signed Hermes entrypoint-only plist generation, separate exact Hermes/Connector labels, Hermes-first
  startup, process-specific post-checkpoint ready evidence plus loopback health, bounded/symlink-safe
  log reads, Connector suppression on Hermes timeout, and reverse-order rollback;
- default-off packaged bootstrap configuration, strict HTTPS/key/channel/architecture validation,
  exact `hermes-serve-v1` loopback arguments, Desktop marker, private session-token file path and
  sentinels, absent/mismatched Gateway capability rejection, and readiness only when both gates match;
- private installation-local Hermes token creation/reuse, unsafe file rejection, no token value in
  either LaunchAgent, signed-wrapper file validation, and Connector file loading of both canonical
  43-character base64url and historical 64-character lowercase-hex values with no symlink or
  group/world-readable fallback;
- the managed Connector LaunchAgent sets `CONNECTOR_MODE=account` and `HERMES_MODE=live`; without
  the latter an older Connector falls back to mock, which turns the lifecycle observer off and
  leaves every task-completion event and push unsent (HG-101);
- committed pre-contract token migration preserving the existing 64-character local credential,
  removing both supported inline field names, writing only the canonical `0600` token file reference,
  and recording completion only after ordered service restart plus local health and an exact bound
  Cloud health timestamp strictly newer than the pre-Connector-start checkpoint;
- managed release 0.3.0 compatibility: startup returns before account refresh, file mutation, or
  service restart because its packaged Connector does not consume `HERMES_SESSION_TOKEN_FILE`; 0.3.1
  is the first immutable managed release admitted to the token-file migration;
- power-loss resume from a matching half-migrated plist pair, completed-state idempotency, rejection
  of mismatched inline values or account binding generation before mutation, and injected readiness
  or stale-Cloud-health failure that restores the exact old plist/token bytes and proves the restored
  running service pair with a fresh Cloud timestamp;
- an existing responder on reserved port 9119, including 401/403, blocks a clean install — unless it
  is this Mac's own usable checkout, which is adopted instead (see the adoption bullet below);
- packaged ATS configuration explicitly permits local-network health probes while leaving arbitrary
  public and WebView HTTP loads disabled;
- candidate Hermes readiness uses a dedicated ephemeral session that explicitly disables HTTP, HTTPS,
  SOCKS, FTP, PAC, and automatic proxy discovery, so configured proxies cannot intercept or stall the
  `127.0.0.1:9119` commit gate;
- the production migration coordinator passes a 75-poll window to candidate readiness, covering the
  measured 35-second physical-Mac cold start while tests can still inject shorter deterministic limits;
- an active managed release exposes upgrade only for a strictly newer signed target; preparation
  routes to upgrade for both schema-v1 and schema-v2, while same-version/downgrade inputs fail before
  service mutation;
- managed upgrade never invokes binding creation/confirmation, preserves the exact binding ID and
  generation, stops Connector before Hermes, waits for the old 9119 listener to disappear, then starts
  Hermes before Connector and requires a strictly newer Cloud health timestamp;
- candidate failure restores byte-identical LaunchAgents, the previous bundled `current` pointer,
  both old services and the old `account_active` journal; restart recovery uses the same durable
  snapshot and leaves manual attention only when that restoration cannot be proved;
- restart inspection recognizes only an `account_active` journal plus both exact managed LaunchAgents
  as active; intermediate journals recover before a second install and mismatches fail closed;
- overview Agent reduction prefers that exact active managed installation over a stopped legacy
  label, reports a transferred managed service as running-but-unverified until account sign-in, and
  fails closed when the signed-in binding ID or generation differs;
- a newly confirmed run atomically replaces only a terminal `legacy_active` or `clean_uninstalled`
  rollback journal; intermediate, active, and manual-attention journals reject a different run ID;
- a revoked pending binding is recreated only when its generation matches the same Desktop's terminal
  `legacy_active`/`clean_uninstalled` rollback journal; missing or mismatched proof fails closed with
  `HR-BIND-006`, remains visible through the migration presentation boundary, and does not call binding
  creation;
- existing-install observation/recovery remains available when new-install rollout is disabled, and
  active state must match the current account's exact binding ID/generation before it is claimed;
- a recognized running legacy Connector uses its own configured Hermes status URL for the final
  migration preflight and can enter the signed two-stage migration only while that health check and
  the complete managed-install capability agree; stopped/unhealthy or unsigned cases stay read-only;
- bilingual `HR-MIGRATE-001` through `HR-MIGRATE-005` terminal-state mapping.
- adoption of this Mac's own Hermes (2026-09-30): the planner returns `.readyToAdoptLocalHermes` only
  for the shape that has no connector to keep (`legacy.isInstalled == false`, a ready signed channel,
  and a `.usable` local checkout supplied — which the ViewModel does only with the local-runtime
  setting on), plans `.adoptLocalHermes` instead of `.installHermes`, and names the checkout in its
  detail; a running legacy Connector still plans the migration even with an adoptable
  checkout on disk; with the signed channel disabled the same inputs degrade to
  `.waitingForSignedRelease` with no adoption step. The detector names the LaunchAgent holding the
  loopback port from `ProgramArguments` (`com.hermes.dashboard` for the mini's holder) regardless of
  the label prefix, prefers `dashboard` when several match, and reports none for a server on another
  port. `DesktopIssue.migration` with that label puts the label, a pasteable
  `launchctl bootout gui/$(id -u)/<label>` and the plist move-aside into `technicalCause`, and
  degrades to an `lsof` hint with no `launchctl` command when no label is supplied.
  A coordinator refusal test asserts the stronger property for an occupied port on a fresh install:
  exactly one shutdown probe, no journal, no service mutation, no LaunchAgent, no health poll, and no
  managed `logs` directory created.
- local Hermes runtime (`DesktopLocalHermesTests`, `DesktopHermesRuntimeCoordinatorTests`):
  detection of the standard layout from git files alone (loose ref, `packed-refs`, detached `HEAD`)
  and of every surfaced shape — profiles, non-default `active_profile`, `HERMES_HOME` in the
  environment or an owner `ai.hermes.*` agent, an owner agent outside the checkout, a foreign
  entrypoint or pipx install, missing venv, group-writable entrypoint, version below 0.21.3,
  unreadable `HEAD`; upstream's update lock honoured only within its 20-minute ceiling; the planner's
  switch/keep/wait/restart/restore/surface table including the `hermes update` self-restart and the
  `git pack-refs` no-restart cases; the launcher **executed** against a stand-in Hermes (token handed
  over, `PYTHONPATH` cleared, unsafe token file, malformed token and foreign argv refused before
  exec); the exact local-mode `ProgramArguments`/environment and that `shlex.join` of them contains
  `hermes serve`; switch restarts only Hermes and keeps the exact bundled agent; a failed switch
  restores both files and proves the old server; restart on code change; no restart for a process
  started after the update; restore on removal and on turning the setting off; nothing mutated
  when disabled, unsupported or uninstalled; a managed upgrade keeps the local agent; the startup
  token/PATH reconcilers are inert in local mode; the retired schema-drift advisory is absent; the
  fresh-install gate; `HR-MIGRATE-008`/`009` bilingual contract and redaction; source wiring of the
  refresh call, both install gates, and the window card. Review fixes (each checked to fail when its
  fix is reverted): a failed return to bundled restores and proves the intact local Hermes, or keeps
  the bundled agent loaded when local is gone; a failed `bootstrap` during a switch or restart leaves
  the job loaded, and an unloaded local job is started again; an upgrade in local mode refreshes the
  kept bundled agent and a failed one restores the old one; a kept agent whose program is gone is no
  fallback (`HR-MIGRATE-010`); a loosened or older launcher is repaired while local mode stays
  recognised; the setting off does no detection on a bundled Mac (one `launchctl print` per refresh
  since 2026-09-21, to load an unloaded job); three restarts
  in 30 minutes pause and surface; launchd's loaded arguments that differ from the file reload the
  agent; adopting an update-started process; restarting only on a commit change, not a timestamp;
  waiting for reinstalled dependencies; a fresh install starting only the local Hermes; nothing
  shown mid-update; a separate non-retryable `HR-MIGRATE-010`; `/Users/<name>` redacted; an
  inline-token agent is never switched from; lease contention waits without an error. Re-review fixes (each checked to fail when reverted): two failed switches on one commit pause
  switching (`HR-MIGRATE-011`) until the commit changes or the setting is turned off; two failed
  setting-off rollbacks with one kept agent pause until it changes or the setting is turned on,
  leaving local Hermes running; a stopped local Hermes is restarted regardless of its venv; a
  persistent version mismatch and a missing or duplicated dist-info are shown; dist-info is read
  deterministically; setting off with an intact local Hermes and no kept agent is `HR-MIGRATE-012`;
  the once-per-launch running-agent check is wired.
- install-when-missing and the default-on setting (`DesktopHermesInstallerTests`,
  `DesktopHermesInstallOfferTests`, `DesktopSystemProxyTests`, `HermesInstallWiringTests`,
  `DesktopLocalHermesPresentationTests`). The driver runs **fake installer scripts with the real
  process runner** in a throwaway home — no test reaches the network, the real `~/.hermes`, launchd
  or `~/Library`: a successful run drives `--manifest` then every stage with `--stage <name>
  --non-interactive --json --dir … --hermes-home … --branch main` in manifest order, reports a
  skipped interactive stage as skipped, and returns what detection then sees; the child's `HOME` is
  the sandbox, its stdin is not a terminal, and `sudo` resolves to the refusing guard; the log is
  0600, carries the script's SHA-256 and the proxy summary, strips ANSI and redacts credentials and
  home names, and the private run directory is removed; a stage failing with "Could not resolve
  host" is retryable `HR-MIGRATE-015` and stops later stages; a compiler-style failure is
  retryable `016`; a stage without a result frame and a manifest with `protocol_version` 2 are
  non-retryable `017` before any stage runs; a captive-portal page instead of a script runs
  nothing; an unreachable download is `015`; an install detection does not accept is `018`;
  cancelling terminates the stage's process group through `SIGTERM` alone within four seconds (it
  failed through the five-second `SIGKILL` fallback before the child's signal mask was reset) and
  is not an error; root is refused before any download; a Hermes that appeared meanwhile is used
  without running anything, and an unusual one is not touched; an offer that was only shown runs
  nothing. Offers: only `.absent(hermesDataPresent: false)` on a fresh Mac with the setting on is
  offered; data without a checkout, profiles, custom home, pipx and too-old are not; an
  incomplete checkout is resumable only after Desktop's own attempt. The origin check accepts only
  HTTPS on the official host or exactly upstream's `main/scripts/install.sh` on GitHub; the manifest parser reads upstream's
  real 17b5df02 manifest line and refuses malformed ones; the stage result is the last frame; the
  run state folds progress; network signatures are recognised and a compiler error is not; output
  lines are cleaned like a terminal shows them; `HR-MIGRATE-015`–`018` are bilingual with the
  registered retryability and recovery and redact URL credentials and home names. Proxy: the
  System Settings dictionary is parsed; disabled, invalid and PAC-only settings export nothing
  (and PAC is named in the summary); each configured proxy is exported in both cases and only
  then, with IPv6 bracketed and SOCKS as `socks5h`; explicit variables win; loopback and the
  bypass list are always in `no_proxy`. Setting: on with nothing configured, off from the
  environment or after "use the bundled Hermes". Wiring (source assertions): exactly one
  `installer.install(` call site, inside `startHermesInstall()`; the card starts it only from the
  confirmation sheet and Retry; the refresh offers; both setup paths wait for the decision.
  Review fixes of 2026-09-22 (each checked to fail when its fix is reverted): a failure in
  `node-deps` after `python-deps` — detection already reads `.usable` — keeps Desktop's attempt
  pending (setup blocked, the half-installed Hermes still offered for resume) and Retry runs the
  stages again; an install that ends without upstream's `.hermes-bootstrap-complete` is `018`;
  `freshInstallProvider` refuses a Hermes whose Desktop-started install is unfinished and accepts
  it once the completion marker exists; a resume refuses a checkout replaced by the owner (inode and
  birth time differ) before downloading anything, and a first install refuses a checkout that
  appeared meanwhile; stale attempt records are dropped; the re-evaluation before running writes
  nothing, must match the shown offer and aborts without one (source assertion); `SSH_AUTH_SOCK`
  does not reach the installer; the prerequisites probe's "Could not reach https://duckduckgo.com/"
  and "Failed to download" do not turn a compiler failure into `015`; a manifest line over 4096
  bytes reaches the parser whole and log lines are split, not cut; a redirect hop off the origin is
  refused at the hop and the download stops past its limit; `*_API_KEY=`, `ghp_…`, `sk-…` and URL
  passwords containing `@` or `/` are redacted while `registry.npmjs.org/@scope` is not; quitting
  Desktop (`terminateAllProcessGroups`, wired to `applicationWillTerminate`) ends a running stage's
  process group. Re-review (both fail against d90fbcc): cancelling while the `repository` stage
  still runs after its clone landed keeps the checkout recorded, pending and resumable; after a
  quit, a record without a checkout claims one born after the install started and drops an older
  one. Owner decision on the `HR-MIGRATE-008` escape: `desktopOwnsCheckout` is true for Desktop's
  leftover checkout (after a failed stage, and when claimed after a quit) and false for a replaced
  checkout, no attempt at all, and a checkout older than the attempt; a finished-but-unusable
  checkout stays Desktop's until it is seen usable. Source assertions: both refusals classify the
  block; the built-in card is shown only for Desktop's checkout and the owner's-Hermes guidance card
  offers no built-in alternative.
- 2026-09-21 restart-probe incident (`DESKTOP_E4_TEST_RECORD.md`), each checked to fail when
  reverted where the boundary allows: the loopback shutdown decision is a pure function —
  `.waiting`/`.failed` with `ECONNREFUSED` is "stopped", `.ready` is "listening", every other error,
  `.setup`, `.preparing` and `.cancelled` stay undecided; a real loopback test on an ephemeral port
  proves a free port stopped in one probe (the old mapping took the full 15 s of five attempts and
  failed) and a bound, listening socket not stopped; a switch whose stop and restore waits both time
  out ends with the job loaded, the bundled agent restored, exactly one reload bootstrap, and a
  `DesktopServiceRecoveryFailure` naming `switch-to-local`, both `hermesStopTimedOut` causes, and an
  `HR-MIGRATE-009` diagnostic carrying them; a reload refused twice by launchd succeeds on the third
  attempt and the log keeps launchd's stderr; a reload refused every time is `hermesReloadFailed` →
  retryable `HR-MIGRATE-013`, also after a failed local restart; an upgrade whose rollback also fails
  carries both causes into `HR-MIGRATE-004`; a bundled job that is not loaded plans `.loadAgent` with
  the setting on or off and even mid-update, and is loaded with a readiness proof, while a stopped or
  unreadable bundled job is left to launchd; the setting-off path loads on every refresh and reloads
  only once per launch; the operation log is 0600, single-line per event, redacted, rotated once,
  never created without the managed logs directory, never follows a symlink, logs mutations with
  status and stderr and summarises `print` polls; `SystemCommandRunner` keeps a bounded stderr only
  when asked; the migration journal reads a whole-second or offset RFC 3339 `updatedAt`, rewrites it
  canonically on the next transition, and still rejects anything that is not RFC 3339.
  Review follow-up (both fail against the first version): loading an unloaded job while another
  process accepts on 9119 makes exactly one single-attempt probe, bootstraps nothing, never reloads,
  and surfaces retryable `HR-MIGRATE-014`, with the setting on or off; a port taken between that probe
  and the start gives the same answer with no bootstrap. `HR-MIGRATE-013` copy claims no restart.

- the Desktop entry route and first-run onboarding (`DESKTOP_ONBOARDING_REQUIREMENTS.md` §4.1): every
  row of the state table, `.checking` never rendering as the sign-in page, an unconfirmed account
  showing retry instead of spinning, an expired-session reason reaching the page while an explicit
  sign-out carries none, `.existingServiceNeedsAttention` and `.existingServicePreserved` both staying
  out of onboarding, a running setup keeping onboarding on screen, and the §6.3 resume rules;
- the new-Mac choice and its quota (§7): an account that already owns another Mac is asked first, a
  shared Mac or this Mac's own previous binding does not trigger it, manage-only is remembered, and
  "把这台 Mac 也连上" becomes unavailable only when the owned-Mac limit is reached;
- the phone step (§6.4): a `phone` or `browser` installation that appears after the step began counts,
  one that already existed does not, and a Web App sign-in is never labelled an iPhone;
- removing an owned Mac through `DELETE /v2/devices/{id}`: request path, `connector.unbind` grant and
  idempotency key, grant reuse after a lost response, fail-closed behaviour without multi-device, and
  refusal of a shared or unknown device;
- the onboarding record: stored per account, and an empty record leaves nothing behind.

Remaining email-first release acceptance requires live-provider tests for resend/cooldown, expiry,
account-existence-neutral delivery behavior, packaged-UI inspection proving that an `email_otp`-only
Gateway exposes no Google action, and a packaged two-phone run proving the verification sheet and
selective revocation. The deterministic E7 transport/controller/error and retry cases are now
automated.

Every new phase-0 behavior requires a regression test when its boundary is deterministic. User-visible
error codes additionally require localization, retryability, recovery-action, and redaction tests under
the project-wide `ERROR_HANDLING.md` contract.

## Manual phase-0 matrix

| Case | Expected result | Status |
|---|---|---|
| Existing Connector running | Desktop observes it and does not launch a replacement | Verified on target Mac 2026-09-02; PID and launch count unchanged |
| Existing Connector migration gate | Healthy configured Hermes plus matching signed-release capability exposes preparation; stopped/unhealthy/unsigned cases preserve legacy | Automated; packaged target-Mac migration still pending |
| Migration Assistant preserves managed services | Overview reports the effective managed Agent rather than the stopped legacy label; missing this-device-only account credentials require sign-in; a restored legacy label is persistently suppressed only for a proven `account_active` installation | Desktop 0.2.4 packaged reboot passed on migrated Mac 2026-09-11: only managed labels recovered, legacy stayed disabled/unloaded, and post-reboot Android REST/WebSocket carried bidirectional traffic |
| Pre-contract managed token storage | A committed matching installation moves the existing token from both `0600` plists to the `0600` private file, restarts Hermes then Connector, and commits only after local readiness plus a same-binding Cloud health timestamp newer than Connector startup; injected failure restores the old configuration and requires fresh rollback health | Automated, including stale healthy snapshot regression; Desktop 0.2.9 with managed 0.3.4 completed the packaged migration and ordered service restart on 2026-09-12; physical Android account traffic and full Mac reboot remain pending |
| Existing Connector absent | UI reports not detected and offers no destructive action | Verified 2026-09-02 |
| Gateway available | Gateway layer is healthy with safe latency | Verified on target Mac 2026-09-02; 15–18 ms observed |
| Gateway offline/DNS failure | Only Gateway layer fails; Hermes wording remains accurate | Pending fault injection |
| Hermes returns 2xx | Local Hermes layer is healthy | Verified on target Mac 2026-09-02; 117–128 ms observed |
| Hermes returns 401/403 | Layer says reachable/authentication required, not “down” | Pending real-app check |
| Logs contain credentials | Display/export contains `<redacted>` only | Automated; target redacted UI preview inspected 2026-09-02, credential injection still pending |
| Window closes | Existing Connector remains running | Verified on target Mac 2026-09-02; menu-bar app remained and window reopened |
| Two phones use current Gateway | Existing phone configuration remains usable | Pending device check |
| Light/dark mode | Same cool-blue surface language and semantic states | Light verified 2026-09-02; dark pending |
| App icon and Dock presence | Desktop packaging copy equals the canonical Android icon; the packaged app declares `AppIcon`, appears in the Dock while running, and can be kept there with Dock’s native option | Icon copy verified by packaging gate 2026-09-02; Dock presence pending HG-55 physical check |
| Componentized v2 install | Default package remains absent/inert; valid local v2 configuration plus Gateway schema 2 and `hermes-serve-v1` retain one verifier-issued token beside the matching card. A safe machine adds “下载缺失组件”; preparation changes only private cache, an exact signed-version sheet gates commit, and v1/v2/account/refresh actions remain mutually exclusive. Capability loss or unsafe machine state before either stage blocks mutation; trust/scan failure cannot expose the v1 downloader; cleanup failure offers cleanup retry only | Core capability, manifest, token, prepare/commit/cancel/cleanup, component store, migration/rollback, structured error, and configuration boundaries automated; full SwiftUI compilation required. Enabled packaged UI, capability-withdrawal race, real signed archives, and clean/existing-Mac install remain pending |
| Managed Hermes search path (HG-58) | The written LaunchAgent carries `PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin`; an agent written before this change validates without it and is given one by the next optional-component activation; a malformed `PATH` on disk is refused | Automated (written plist, legacy-agent repair, malformed-`PATH` refusal, search-path shape). **Physically unverified**: that a PDF attachment from the phone now succeeds needs the managed service restarted with a rewritten agent, which no machine has done. Read-only check afterwards: the managed `hermes-server` process `PATH` contains `/opt/homebrew/bin` |
| Managed search-path startup repair (HG-58) | On a committed `account_active` installation whose agent predates the `PATH`, launching Desktop adds the key, restarts **only** Hermes, and proves it healthy; a second launch changes nothing; an existing well-formed `PATH` is left alone; a malformed one and an agent we did not write are refused before any service changes; a failed restart restores the exact file and the running server | Automated (6 cases in `DesktopMigrationCoordinatorTests`). **Physically unverified**: that the repair fires on a real migrated Mac, that the Connector survives the Hermes-only restart window, and that a PDF then sends |
| Managed startup reconciliation classification (HG-68) | A transferred-account Connector reconciliation failure remains a hard `HR-MIGRATE-002` block; token-storage or search-path repair failures surface as retryable `HR-MIGRATE-007` without changing an available managed-upgrade operation to failed | Automated issue/stage contract plus full Desktop compilation; packaged UI remains pending |
| Local Hermes runtime switch | With `HermesGoLocalHermesRuntimeEnabled` on, a committed Mac with a usable standard Hermes rewrites only `com.hermesgo.hermes-server` to the local launcher, restarts only Hermes, passes the readiness proof, and the phone keeps REST and WebSocket traffic; the Connector's PID is unchanged; with the owner's gateway running, `cron/executions.db` gains no rows from the serve's PID | Automated with in-memory launchctl; read-only dry run on LGS-MACMINI 2026-09-21 planned `switchToLocal` (0.21.3, `17b5df02`). **Physically verified once**: the Mac mini was switched by hand with Desktop 0.2.22 on 2026-09-21 (about 17 s of Hermes unavailability, Connector PID unchanged; `DESKTOP_E4_TEST_RECORD.md`, "2026-09-21 Mac mini switched to its own Hermes"). The automatic switch of a Mac by the default-on setting is not yet verified (next row) |
| Local Hermes restart on update | After `hermes update` on a local-mode Mac, the serve's start time is later than the checkout's ref time within one refresh, either because upstream kickstarted our job or because Desktop restarted it; no duplicate or detached serve holds 9119 | Planner and coordinator automated. **Physically unverified**, including upstream's kickstart of our job |
| Local Hermes rollback | Turning the setting off restores the exact bundled agent from `Managed/state/hermes-server.bundled.plist` and restarts only Hermes | Automated. **Physically unverified** |
| Local Hermes unsupported | A Mac with profiles, a custom `HERMES_HOME`, or a second install shows `HR-MIGRATE-008` and changes nothing; a fresh install on it is refused | Automated. Packaged UI pending |
| Local runtime on by default | Upgrading Desktop on a managed Mac with a usable standard Hermes and no `HermesGoLocalHermesRuntimeEnabled` key switches it on the first refresh exactly as the "Local Hermes runtime switch" row; a managed Mac without Hermes changes nothing and shows no install offer; `defaults write … -bool false` rolls back | Planner/coordinator automated; default value automated. **Physically unverified** on a Mac that has not been switched by hand |
| Install Hermes on a clean Mac | **Manual, on a clean macOS user account or VM** (no `~/.hermes`, no `~/.local/bin/hermes`, no Homebrew `hermes`, no pipx install; ideally one run with and one without a manual HTTP/HTTPS proxy in System Settings). Sign in to Desktop; the "这台 Mac 还没有 Hermes" card appears above the setup card and the setup download action is replaced by the decide-first line. "安装 Hermes…" opens the sheet; cancelling it runs nothing (no `~/.hermes`, no `hermes-install.log`). "开始安装" shows the manifest's stages in order; `setup` and `gateway` show as skipped; `ps -o pgid,command` shows the installer in its own process group, never as root, with no `sudo` process. `Managed/logs/hermes-install.log` is 0600, names the proxy, the script's SHA-256, every stage and no `/Users/<name>`. Afterwards `~/.hermes/hermes-agent/venv/bin/hermes --version` works, the card reads "Hermes 已安装", and continuing the normal setup writes `com.hermesgo.hermes-server` in local mode directly (`ProgramArguments` name the local launcher; `Managed/state/hermes-server.bundled.plist` exists; the bundled `hermes-server` never runs). Repeat with the network or proxy broken (for example a bogus manual proxy): `HR-MIGRATE-015` with Retry and "改用内置 Hermes"; fix the proxy and Retry resumes. Cancel during `python-deps`: no installer, `git`, `uv` or `curl` process remains within ~5 s; the card says cancelled, not failed; Retry resumes. "改用内置 Hermes" writes the setting off and the setup installs the bundled copy exactly as before | Driver, proxy composition, offer rules, cancellation and error codes automated with fake installer scripts. **Not run** against upstream's real installer or on a clean Mac |
| Adopt this Mac's own Hermes (2026-09-30) | **Manual, on a Mac that already runs its own Hermes and no Connector.** With 9119 answering from this Mac's own `~/.hermes/hermes-agent` but no `com.hermesremote.connector` installed, setup offers 接管 instead of a refusal: the plan's detail names the existing checkout, the confirm sheet says only what that path changes, and the button reads 「接管并连接」. Continuing writes `com.hermesgo.hermes-server` in local mode pointing at the existing checkout, installs the Connector and both user-level LaunchAgents, binds the account, and restarts those two services briefly. Afterwards `~/.hermes/.env`, `~/.hermes/state.db` and the checkout's mtime/content are unchanged, no second copy exists under `Managed/`, and the phone reaches Hermes. Then the refusal side: `launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.hermes.dashboard.plist` (the situation found on the new mini) makes setup fail with `HR-MIGRATE-014` before anything is written — 详情 names `com.hermes.dashboard` and gives the `launchctl bootout gui/$(id -u)/com.hermes.dashboard` line; nothing under `Managed/` is created; booting the job out and retrying proceeds to adoption | Planner, detector, issue mapping and the refusal's no-write property automated. **Not run**: needs a Mac in that shape, and the mini's own holder (`com.hermes.dashboard`) was only read, never booted out |
| Managed patch 020 — bounded inline images (HG-74) | A bounded read carries `[image]` in place of an inline `data:` image; the image still appears on the phone, from the Mac path in the text part; an `https://` image URL is untouched; the placeholder never reaches a bubble | Both client-side behaviours the patch depends on are pinned by Android tests (`TimelineNoteTest`, `MultimodalContentMappingTest`) — neither is new, both were unpinned, and the patch lives in another program. Measured against the real database: one message 27,479,595→6,374 characters, the whole HG-65 session 105.07 MiB→0.265 MiB. **Not yet live**: needs a managed release and a controlled activation, so no phone has read a bounded response |
| Managed Hermes restart proof (2026-09-21) | On the packaged app, a runtime switch or managed upgrade that boots out `com.hermesgo.hermes-server` bootstraps the replacement within a few seconds of the old process exiting (`launchd.log` shows the bootstrap; `Managed/logs/desktop-runtime.log` shows `wait-stopped result=stopped attempts=1` or close to it), not after ~112 s | Automated (probe, loopback, coordinator). **Physically unverified** |
| Managed Hermes left unloaded | With Desktop running on a committed installation, `launchctl bootout gui/$UID/com.hermesgo.hermes-server` by hand is undone within one refresh (setting on or off): the job is bootstrapped again and passes readiness; if launchd refuses three bootstraps the window shows retryable `HR-MIGRATE-013` and tries again after five minutes. If another process listens on `127.0.0.1:9119` at that moment, nothing is bootstrapped, the lease is released after one probe, and the window shows retryable `HR-MIGRATE-014` | Planner and coordinator automated. **Physically unverified** |
| Service operation log | `Managed/logs/desktop-runtime.log` exists at 0600 after the first service operation, carries launchctl mutations with status and stderr and each wait's outcome, contains no token and no home-directory name, and stays under ~512 KiB across rotations | Automated. Packaged check pending |
| Hand-edited journal | A journal whose `updatedAt` is `2026-09-20T11:48:09Z` loads (no `HR-MIGRATE-002`), and the next Desktop transition rewrites it as `…09.000Z`; a non-RFC 3339 value still fails closed | Automated |
| Keychain profile | App Token persists across restart and is never shown in visible UI | Verified locally and on target with disposable test Token 2026-09-02 |
| Invalid App Token | End-to-end check reports `HR-AUTH-001` with recovery guidance | Automated + local/target UI verified 2026-09-02 |
| Local Hermes unavailable behind an online Connector | End-to-end 502 from structured or legacy Connector reports retryable `HR-CONN-006`; raw body and request ID stay out of primary copy | Automated in `EndToEndProberTests`; live test stack pending |
| v1 QR payload | JSON contains only compatible `v`, `url`, and `token` fields | Automated 2026-09-02 |
| QR reveal | Real QR is hidden by default and carries an explicit long-lived-token warning | Local + target UI verified 2026-09-02; Android scan pending |
| End-to-end success | Saved App Token reaches Gateway → Connector → Hermes through `/api/status` | Pending target production-token check |
| Account-mode presentation | A signed-in account omits the legacy App-Token probe, and Overview uses the Desktop-selected Mac's authenticated Connector/Gateway/Hermes/end-to-end snapshot instead of a saved legacy profile; switching devices cannot rename or restart local services | Automated |
| Account mode disabled | Account & Devices reports unavailable and legacy connection remains usable | Automated core behavior; packaged UI inspection pending |
| Email account login | Email challenge and six-digit exchange create/restore only the Desktop management session | Controller/API automated; live delivery and packaged UI pending |
| Browser OAuth loopback | Listener binds an ephemeral `127.0.0.1` port and rejects mismatched state | Automated locally; live Google client pending |
| Account session restart | Keychain session refreshes without changing the Connector machine identity | In-memory/store contract automated; packaged Keychain run pending |
| Two account phones | Account & Devices lists both; owner-email verification removes only the selected phone | Controller/API/PostgreSQL automated; packaged UI and physical phones pending |
| Whole-device sharing off | No share controls appear and existing device selection remains unchanged | Automated core behavior; packaged UI pending |
| Owner invites account | Explicit sessions/files/config warning and fresh owner-email verification precede mail request | Controller/API automated; live mail/two-account run pending |
| Recipient accepts invite | Pasted link requires exact token + acknowledgement and creates operator access only | Parser/API automated; two-account physical run pending |
| Owner revokes / recipient leaves | Matching access and live stream end; owner, other grantees, Connector, and Hermes continue | Gateway automated; packaged Desktop and multi-node run pending |
| Account deletion off | No Desktop danger-zone action appears and the route is not called | Core/API automated; packaged UI pending |
| Permanent account deletion | Typed `DELETE`, acknowledgement, and fresh email code precede immediate Cloud logout; success and recovered ambiguous completion land on “deletion submitted”; the explicit other-email exit reaches an empty sign-in flow, while local Hermes remains intact | Core/API/PostgreSQL automated; disposable packaged-account and privacy review pending |
| Android account-mode cutover | Android signs in by email, selects the intended owned/shared Mac, proves REST and WebSocket traffic, then Desktop migrates; protected Connector status matches the exact binding/generation before and after Desktop/Mac restart | Primary single-phone 0.1.113 path accepted 2026-09-10; migrated-Mac reboot plus post-reboot account REST/WebSocket accepted 2026-09-11; activation-interruption and multi-phone/shared-Mac matrices remain pending |
| Sign-in gate with a live phone (HG-129) | **Manual.** On a Mac that is set up and serving a phone, invalidate only the Desktop session (sign out from another client, or revoke the session). The window shows the full-window sign-in page with the `HR-AUTH-*` banner; `launchctl print gui/$UID/com.hermesgo.connector` and `com.hermesgo.hermes-server` keep their PIDs and states, and the phone keeps answering for the whole gated period. Sign back in: the window returns to the section it was on. Sign out from Settings instead: no reason banner appears | The gate is a `RootView` presentation switch and the monitoring loop is untouched by it, but `HermesGoDesktopCoreTests` cannot assert either — the loop and the window live in the executable target. **Not run** |
| First run on a clean Mac (HG-129) | **Manual, on a clean macOS user account or VM with no managed install** (the same host as the "Install Hermes on a clean Mac" row). Launch: the checking page, then a full-window sign-in page carrying the four-step hint. After the email code: no install and no other Mac → onboarding step 2; another owned Mac exists → the "把这台 Mac 也连上 / 只在这台 Mac 上管理" choice first. Step 3 shows the plan's rows and stops at the confirmation sheet; committing ends on the step 4 QR page. Android: scanning `https://mrlgs.net/` downloads the current versioned APK; after signing in with the same email the step ticks, and the overview's "还没有手机连接" card disappears. iPhone/iPad: scanning `https://mrlgs.net/app/` opens the Web App in Safari; added to the home screen and signed in, step 4 ticks as 网页版 Hermes GO, never as an iPhone. Quit during step 3 and relaunch: onboarding resumes at the step the Mac is really on, not from the beginning. At a 3-Mac limit the connect option reads "先移除一台 Mac" and removing one through the email code frees the slot | Router, quota, phone-completion rule, and the removal API are automated. The two QR codes, real phone sign-ins, and the interrupted-install resume are **not run** |

The first real-app check verified that the ad-hoc app launches and remains running. The target Mac run
then verified the installed DMG against a live legacy Connector without changing its PID, launch count,
configuration, log size, or public Relay attachment. The diagnostics UI correctly kept the App-Token
end-to-end check unavailable. Physical two-phone traffic, injected fault cases, and takeover/rollback
remain pending; public Relay health alone is not evidence for a phone UI test.

The `0.2.0-dev` target upgrade additionally verified Keychain persistence across restart, explicit QR
reveal, and the invalid-token UI mapping without restarting the legacy Connector. The disposable Token
and temporary rollback package were removed after the run. A real production Token was deliberately
not retrieved as part of this test, so the successful end-to-end and Android scan rows remain pending.

## Android-account coordinated acceptance

Run this matrix only after the Android account branch has passed its package gate. Do not migrate the
Mac merely because email login or `/v2/devices` succeeds.

1. Keep the legacy Connector active. Install the versioned Android test artifact, sign in with email,
   and confirm the intended owned or shared Mac is listed without importing a legacy App Token.
2. Record the protected `/internal/account-connectors` snapshot and the authenticated Android device
   row. The former may show zero account connections before migration; `/relay-health` must still show
   the expected legacy Connector.
3. Complete the Desktop's two-confirmation migration. Require an `account_active` journal, both exact
   managed LaunchAgents, healthy local Hermes, and an internal snapshot row whose binding UUID and
   generation match Desktop state. Before any later token-storage repair, record the active managed
   release and require the signed runtime capability boundary; 0.3.0 must remain unchanged.
4. From Android, exercise one REST status request, one real WebSocket session, **and one slash
   command** through the selected device **before calling the Desktop candidate accepted or
   proceeding to a reboot**. An established Connector control socket and a loopback HTTP 200 do not
   satisfy this gate. For a shared Mac, repeat using the grantee account and confirm revocation
   closes only that grantee's live stream.

   The slash command is not padding. A prompt exercises the gateway in-process; a slash command is
   the only thing that makes Hermes spawn a **child** process, and a child gets a different
   environment from its parent. Managed release 0.3.0 served prompts perfectly and could not run a
   single slash command, because the child could not import `tui_gateway` — and nothing in this
   matrix would have caught it (HG-28). Switching the model in the Android model picker is the
   cheapest way to exercise it: it sends `/model … --session`. A failure appears as `slash.exec`
   error 5030 in the device diagnostics and `HR-RPC-007` on screen.
5. Restart Android and Desktop independently, then reboot the Mac. Require the same account binding,
   automatic managed-service recovery, a newer process-local `connectedAt`, and successful Android
   REST/WebSocket traffic without re-entering a legacy Token.
6. On any failed gate, capture redacted diagnostics and use the journaled Desktop rollback. Confirm
   the legacy Connector returns, `/relay-health` reports it online, and the old Android path remains
   usable. Do not call a mixed legacy/account state successful.

## Later takeover gate

Managed Agent takeover must not ship until automated and real-machine tests prove:

1. the new Agent does not start while the old Connector is active;
2. configuration validates before the old service stops;
3. the new Agent receives `hello_ack` and passes local/end-to-end checks;
4. failure stops the new Agent and restores the old service;
5. the phone retains its URL, token, sessions, and history after takeover and rollback.

Items 1–4 now have injected local core tests. They are not considered target-Mac verified: the
packaged UI path is compiled but hidden in the default-off build, and no real signed/notarized
artifact has exercised it. Item 5 and the physical clean-install/upgrade/interruption matrix remain
manual gates.

## Update checking

Automated (JVM-free Swift unit tests, `npm run desktop:test`):

1. `DesktopUpdateIndexTests` proves the app index resolves every field, rejects unknown fields,
   off-origin download URLs, a filename/version mismatch, bad notes, and a wrong channel, and that
   semantic comparison is correct in both directions.
2. `DesktopUpdateCheckerTests` proves a newer app and managed release are both reported with notes,
   equal versions report nothing, a managed update is not offered when nothing is installed, a
   malformed index fails closed, and no configured source reports “not configured”.
   `DesktopManagedBootstrapConfigurationTests` also pins that a validated discovered version can
   target an existing-install upgrade card only for an index URL; an exact manifest remains pinned.
3. `scripts/test/desktop-app-update-index.test.mjs` proves the generator writes an index matching the
   DMG bytes, refuses a misnamed DMG, rejects oversized/multi-line notes, and never overwrites an
   existing index.

Manual, on a real Mac (not reproducible in CI):

1. Build an update-capable app with `HERMES_GO_APP_UPDATE_ENABLED=1` and an app index pointing at a
   prepared release; confirm Settings shows the current and component versions and the automatic-check
   switch, and that the menu bar offers “检查更新”.
2. With the published index one patch ahead, confirm a manual check opens the unified sheet with both
   update cards when both exist, that 立即更新 for the app downloads, verifies, quits, replaces
   `/Applications/Hermes Go Desktop.app`, and relaunches at the new version, and that 组件更新 opens the
   existing signed confirmation sheet.
3. Confirm an app-index download whose SHA-256 is deliberately wrong stops with `HR-DESKUPDATE-004` and
   leaves the installed app untouched.
4. Switch automatic checks off and confirm no index request is made across a launch; switch them on and
   confirm one check runs.
5. Confirm a build with no app index reports “此版本未配置更新检查” and shows no failure banner.
6. On an existing managed installation, check an index with a newer signed release: Account &
   Devices shows the discovered target and “下载并验证更新” after the check. An invalid index leaves
   the installed service and upgrade action untouched. This real-machine path remains unverified.

### HG-116 Dock icon

The asset equality gate checks the canonical Android image and Desktop packaging copy. On a
physical Mac, inspect the built app in the Dock in light and dark appearance; the faceted H should
retain its colors without an opaque white square. The local ad-hoc build is not a signed release.

维护模式手工验收（尚未执行）：在隔离测试环境配置私有 mailbox，保持一个有正在运行任务的 RPC
通道，然后暂停接单。新提示应显示 `HR-HERMESUPDATE-005`，原任务事件、审批/澄清回答和停止仍可
使用；未关闭通道时 ACK 必须保持 busy。关闭通道后才可取得 Connector 排空证明，仍不能替代 cron、
CLI、其他 profile 和内核进程身份检查。服务恢复的 launchd 命令测试使用注入 runner；本轮未对真实
LaunchAgent 执行 stop/start。

HG-199/main integration additionally preserves HG-212 bounded PM starts with unknown metadata,
while a new Machine regression refuses upgrade before worker/journal/services without strict dependency
evidence. Connector's real account-socket health fixture covers paused health admission and an
admitted stalled response remaining busy until socket cancellation. Test request publication uses
atomic rename like Desktop; production malformed/private-file refusal remains unchanged.

Final merged-baseline validation: focused upgrade inspection/consumer suites 71 tests; Desktop
677 with 1 opt-in skip; native assets/app and ad-hoc/resource checks; Node Protocol 16, Connector
162, Gateway 238 (29 environment skips), release-server 37 and scripts 407 (1 environment skip);
Web typecheck/905 tests/build; Android 2498 JVM tests/assembleDebug. The host probe reports L2
A5DN9X4708H02629 unauthorized and L3 Pixel_API_37 available but not exercised. No new visual
layout or targetSdk change was made; no APK/app handed off, no live Hermes or service operation.
