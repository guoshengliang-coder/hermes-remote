# Hermes cross-process maintenance prototype

Authorized isolation experiment for HG-199 (2026-10-15). `maintenance_lease.py` is a
stdlib POSIX reference module for possible upstream integration, with real subprocess
consumers in the compatibility harness. It is **not installed, imported by GO, or
patched into Hermes**. No upstream PR or production change is part of this experiment.

Run from the GO repository, with an isolated Python 3.11+ interpreter:

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_prototype.py'
python -I -S -B scripts/compat/hermes-maintenance-prototype.py --candidate-root <frozen-0.21.6-checkout>
```

The second command checks SHA-256 of `backend_retirement.py` from the audited
`818c13be1dc4fd28987e1e881a9408224afd4535` source before executing a disposable copy.
Source drift refuses execution with the existing bilingual `HR-HERMESUPDATE-002`.
Only `RetirementFence.acquire()` is used here; the separate maintenance-scope matrix
tests actual prepare/commit with an injected idle ledger. Neither starts a server,
updates an environment, loads real history, or writes to the supplied checkout.
Every subprocess gets a whitelist environment and disposable HOME/HERMES_HOME.

## Cache preservation and cold source materials (2026-10-24)

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_cache_material.py'
python -I -S -B scripts/compat/hermes-maintenance-cache-material.py --provision \
  --candidate-root <complete-fixed-bf0203a7-snapshot>
```

The opt-in command accepts only bf0203a794a07aac3ff764972ab9087baa4e4f4e. It verifies the supplied
source before creating two private disposable material snapshots with local-only Git fetch, no
hooks/global config/lazy transport. One receives a real source-backed timestamp cache via
py_compile, without importing or executing upstream source. The other stays cold and passes the
strict product source verifier. Neither is installed or started as a second Hermes runtime;
there is no database, PM synchronization, server, activation or existing installation operation.
The cold snapshot and example warm cache are fixture setup BEFORE the measured transaction.

A private home/checkout registry then drains its four real maintenance roles. The independent
run/commit/plan digest binds both material roots, all tracked file identities/content digests,
the Git tree, and at most 512 source-backed cache files (1 MiB plan, existing 2 GiB/65536 observation
budget). Every tracked regular file is compared against its Git blob. Declared text/eol=crlf
PowerShell material alone allows Git's CRLF worktree conversion; Python/native bytes remain exact,
and no clean/smudge filter is invoked. Both actual byte digests still enter the plan. The first
real attempts refused ordinary PowerShell CRLF differences, and are not successful evidence.

Original pre-enrolled intent slots are appended/fsynced, never replaced. A distinct
cache-material-started row binds the plan. Cache copies go into a new private directory, with
original identity/content checked before and after copying, fsynced files/directory, then a private
canonical manifest and appended cache-material-prepared row. Original source/cache bytes remain
in place. A completed coordinator is SIGKILLed by its owning supervisor; a fresh interpreter must
acquire new actual maintenance custody, match external run/commit/plan/manifest expectations and
the original complete two-slot history, and re-observe all materials. A saved success report is
never used as permission. Normal cooperating writers remain blocked after every lock closes.

Thirteen portable cases use synthetic Git/source and actual locks, cache bytes and processes.
They cover completed/copy/partial-slot deaths, changed source/cache/archive, same-byte inode
replacement, aliases, extra native code, CRLF conversion/tampering, malformed private JSON,
external expectation mismatches and bilingual redacted CLI failures. Incomplete histories refuse
before source observation and remain unchanged; an incomplete archive is never overwritten/reused.
The actual Mac fixed-candidate run preserves one backend_retirement cache and passes fresh-process
rechecking. CI runs the portable cases on Linux/macOS and the real fixed-source material command
in the unadopted candidate job.

This is a material preservation checkpoint, not cache provenance/execution or source activation
certification. The product worker still refuses warm caches even after successful preservation.
No cache is removed, no generation is activated and there is no resume/replay/finish/clear API.
The supervisor retains the original external expectations; supervisor-death durable authorization,
Git mutation interrupted in flight, official updater custody, nonparticipating writers, full
dependency closure, descendant imports and service/business recovery remain unverified.
sourceActivationVerified/cacheExecutionVerified/updaterRecoveryVerified/globalAdmissionProven/
certifiesUpgrade are always false. No production target or certified path is added.

## Proposed cooperative primitive

Each participant supplies its **fleet maintenance home** and checkout explicitly.
Profiles belonging to that fleet must use the same home domain; this experiment
does not infer profile relationships. Both domains are needed: home-only exclusion
misses another home executing the same checkout while its code changes.

The private control registry sits outside both domains. Enrollment explicitly binds
kind + canonical path + device/inode, plus stable lock-file device/inodes. It does
not occur as a writer fallback. Missing, partial, unsafe or replaced bindings fail
closed. No lock is removed to cancel maintenance. Registry trust/enrollment across
versions is an integration requirement, not a discoverable capability in this module.

| Participant state | Kernel locks retained |
|---|---|
| Writer entering | Shared admission gates, then shared lifetime writer locks |
| Writer active | Shared writer locks; admission gates released |
| Maintenance requested | Exclusive admission gates; future participants refuse |
| Draining | Gates retained; exclusive writer locks tried without killing writers |
| Maintenance ready | All gates and writer locks exclusive |
| Close | Close descriptors; inherited children may hold them; a published intent stays active |

All admission gates are acquired in stable order before lifetime locks. Busy
acquisition returns to the caller; no implicit unbounded wait, retry or timeout.
A failed drain releases partially acquired writer locks but retains closed gates.
The caller owns cancellation/deadline and must close its request. The module does
not promise fairness or recovery from a user who intentionally holds a lease forever.

`pass_fds` is available only after a writer enters or maintenance fully drains.
Descriptors use CLOEXEC otherwise. Explicit inheritance keeps the same open file
descriptions alive after parent death or parent close. Close must never issue
`LOCK_UN`, which would also unlock the child's description. PID reuse is irrelevant
to these kernel locks; PID-based discovery and a real durable handoff identity are
not implemented here. Do not serialize descriptor numbers as recovery capabilities.

## Evidence and limits

Fourteen tests use synthetic participants and actual OS locks: concurrent writers,
late admission, home/checkout overlap and unrelated domains, reversed domain order,
busy drain/cancel, competing updater, partial acquisition cleanup, inherited writer
and updater children after SIGKILL **and** explicit parent close, root/lock replacement,
missing/unsafe/aliased controls, source-drift refusal and bilingual redaction.
Replacing close-only release with `LOCK_UN` causes both parent-close custody cases
to fail; the normal module passes. That mutant lives only in a disposable copy.

The frozen-module experiment blocks participating dispatch from the same checkout
and another checkout sharing the home. An independent unmodified retirement module
still admits work, and a nonparticipant can still write the home while maintenance
holds locks. Consequently both `globalAdmissionProven` and `certifiesUpgrade` remain
false. Advisory locks do not defend against malicious same-user replacement of the
whole registry or code that ignores the protocol. Linux/macOS CI exercise OS behavior;
Windows and network filesystems have no implementation or certification.

## Before upstream adoption

This is a reference primitive, not complete upstream integration. Review these
placements before adding it to `hermes_cli/maintenance_lease.py` upstream:

1. Enter before import/bootstrap/config/schema/log writes, including
   `hermes_cli/main.py` startup recovery. Guarding only `main()` is too late if
   earlier imports can write. Direct API/CLI/serve/gateway/cron entry points need
   the same contract; an omitted entry remains unsupported.
2. Bind multiplex/profile fleet scope explicitly, and retain lifetime leases across
   queued work, human input, delegation and actual subprocesses. Existing per-process
   retirement/idle ledgers still own graceful drain; locks do not make work idle.
3. An updater must enter the exclusive path directly, avoiding its own implicit shared
   writer lock. Keep the official checkout update lock too; define acquisition order,
   trusted control enrollment and the first transition from nonparticipating versions.
4. Define durable run/source/home identity, descriptor validation and authenticated
   child/service handoff. Holding leases across a child crash is tested; safe restart,
   boot acceptance while admission remains closed, PM recovery and terminal debt are
   not implemented. Cancellation here only closes an unmodified prototype request.
5. Audit every writer and exercise actual ledgers/services before producing any GO
   evidence or enabling upgrade. Old 0.21.3/0.21.6 do not acquire these leases today.

The first legacy-to-cooperative transition, full dependency/ABI recovery, exact service
restoration and business/device acceptance remain separate blockers in HG-199.

## CLI import placement experiment (2026-10-15)

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_entry.py'
python -I -S -B scripts/compat/hermes-maintenance-entry.py --candidate-root <frozen-0.21.6-checkout>
```

The entry experiment verifies SHA-256 for four frozen inputs before copying them:
`hermes_cli/main.py`, `hermes_cli/__init__.py`, `hermes_bootstrap.py`, and
`hermes_constants.py`. Only the disposable `main.py` is transformed with AST to
insert a prototype admission call, either before bootstrap imports or inside
`main()`. The binding is explicitly injected by the test child; it is not a new
environment variable, installed launcher or proposed production configuration.
Supplied sources, installed code and runtime state are never modified.

Twelve paired cases execute the actual CLI import and bootstrap scratch hook:
runpy module loading (the `python -m` code path), console-style module import,
and script loading, each with serve/gateway/cron/named-profile-shaped arguments.
The child exits at the return from `hermes_bootstrap.export_scratch_tmp_env`,
before full CLI startup. PM launch/activation and interrupted-update recovery
are injected no-ops. The actual scratch code creates `cache/scratch` and its
prune stamp in disposable data; no real history, updater, service or cron task runs.
The profile case receives an explicit disposable HERMES_HOME; it does not execute
or verify profile selection. It is not end-to-end console-wrapper verification.

With maintenance held, an import-time guard refuses before bootstrap, with zero
home mutation attempts. A guard inside `main()` still allows bootstrap to create
scratch before it can execute: this is the negative control, not a passing guard.
Moving the early call to `main()` makes the frozen-source matrix reject the result.
An admitted prefix checkpoint retains the acquired lease in the probe until its
controlled exit; a separate maintenance request cannot drain until that exit.
This tests the probe's retention, not a full runtime's shutdown/handoff lifecycle.

Seven synthetic-fixture tests cover the matrix, all-input drift refusal, outside
write/process-spawn tripwires, unexpected-import/output failure, partial-line deadlines and bilingual CLI error
redaction. They complement the real frozen-source experiment on Linux/macOS CI.
Children have whitelist environments, bytecode writing disabled and bounded waits.
Audit hooks reject observed outside writes, process/signalling and network calls;
they are test tripwires, not an OS security sandbox for arbitrary supplied code.

The result keeps `pmRecoveryInjected=true`, `fullStartupVerified=false`,
`profileSelectionVerified=false`, `globalAdmissionProven=false` and
`certifiesUpgrade=false`. Recovery/activation must eventually run under their own
validated exclusive handoff; suppressing them is only an experiment boundary.
No upstream patch or production admission configuration is delivered here.

### Frozen-source routing audit

| Audited surface at 818c13be | Consequence / remaining work |
|---|---|
| `pyproject.toml` scripts: `hermes_cli.main:main`, `agent.legacy_cli:main`, `acp_adapter.entry:main` | One main guard cannot cover every console entry |
| `hermes_cli/main.py:16` imports bootstrap; `main()` is at line 3519 | A guard only inside main comes after import-time effects |
| `hermes_bootstrap.py:544` interrupted-pull recovery, `:588` prepare_launch, `:612` recovery / activation | Acquire a validated maintenance role before any of these; they are injected here |
| `hermes_bootstrap.py:622` exports scratch via real `hermes_constants.get_scratch_dir` | The paired experiment observes an actual early filesystem writer |
| `hermes_cli/main.py:666` repeats scratch export after profile re-homing | Early fleet-home scope must remain correct after profile selection |
| `agent/legacy_cli.py`, `acp_adapter/entry.py`, `gateway/run.py` also import bootstrap directly | Additional callers still need reviewed entry integration |
| `cron/scheduler.py` also has a direct `__main__` surface | A general audit must include independent schedulers and library writers |

This is a bounded routing audit, not an exhaustive inventory. Bootstrap recovery,
Python startup/.pth hooks, alternate entries, true profile multiplexing, actual
queued work, native children and durable exclusive service handoff remain uncovered.

## Shared bootstrap and exclusive recovery role experiment

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_bootstrap.py'
python -I -S -B scripts/compat/hermes-maintenance-bootstrap.py --candidate-root <frozen-0.21.6-checkout>
```

Eleven pinned files now include the CLI, legacy CLI, ACP and gateway module/package
inputs, plus `agent/jiter_preload.py`. The new experiment transforms only disposable
copies: either retain the main-only guard or insert one shared bootstrap admission
call after the docstring/`__future__` declarations, before other bootstrap imports.
The existing original source checkout remains untouched.

Twelve paired cases (four modules × three loading forms) demonstrate that the shared
guard blocks every audited bootstrap prefix with zero home mutation attempts. The
main-only guard misses nine alternate-entry prefixes, which create real scratch
files while the maintenance gates are closed. Four admitted writer cases retain a
probe-owned shared lease until exit. Full command dispatch still does not run.

`Lease.inherit_maintenance(domains, descriptors)` adds a **trusted-launch kernel
primitive**, not an authenticated recovery protocol. It duplicates explicitly
passed descriptors with CLOEXEC, verifies complete/unique descriptor shape, writable
access, current domain/control identities and exclusive nonblocking flock custody.
An independent open of the same inode cannot impersonate the open description held
by a parent. Failed validation closes only new duplicates; caller descriptors stay
owned. Release still closes only, never `LOCK_UN`.

The test child receives descriptors via explicit `pass_fds`, adopts them and closes
its original inherited copies. It never requests an ordinary writer lease beneath
the parent's exclusive gates. In four frozen-prefix recovery cases the parent then
closes its own copies: both a new writer and another maintenance request remain
excluded until the child exits. This avoids self-blocking from reacquiring a shared
lease while retaining exclusion across parent close. It is not real PM recovery.

Eight new tests cover duplicate custody/lifetime, self-blocking and writer-role
refusal, same-inode independent opens, malformed/closed/read-only/foreign descriptors,
replaced control files, the synthetic entry matrix, all-input drift and bilingual
error redaction. Descriptor integers can be reused even during duplication; identity
and kernel-lock validation are mandatory. Removing the exclusive-lock verification
makes the same-inode regression fail. Replacing inheritance with shared-writer
acquisition makes the frozen recovery-role matrix fail. Both mutants run only in
disposable copies; the normal implementation passes.

These limits are explicit in the output:

- `pmRecoveryInjected=true`: launch, activation and interrupted-update recovery are
  still no-ops; only the real scratch prefix executes.
- `nativePreloadVerified=false`: `agent/__init__.py` invokes native-parser preload
  BEFORE `agent.legacy_cli` imports bootstrap. The isolated `-S` child has no installed
  third-party parser, so this does not verify native extension initialization.
- `cronPackageBootVerified=false`: source audit of `cron/worker_bootstrap.py` shows
  dependency activation can run during `cron/__init__.py`, before scheduler bootstrap.
  Shared bootstrap alone cannot guard it; no cron worker is launched here.
- `authenticatedHandoffVerified=false`: this kernel-only experiment has no run/source/home/role
  authorization, trusted enrollment, transfer acknowledgement or restart/re-exec/durable custody.
  A saved JSON descriptor number or same-inode match is not authority;
  an unlocked descriptor could newly acquire an exclusive lock without proving any
  parent transaction. The primitive is used only by the explicit test-owned launcher.
- `fullStartupVerified`, `globalAdmissionProven`, `certifiesUpgrade` stay false.

Recovery role must be selected before ordinary writer admission and bound to an
authorized transaction before production use. Never infer it from a subcommand name,
PID, environment flag or a stale receipt. Package initialization, Python startup/.pth
hooks, direct library writers, actual PM/official update locks and first legacy
transition remain separate integration work. The legacy entry's broad bootstrap
`ModuleNotFoundError` fallback also needs a fail-closed audit before adoption.

## Live bound parent-child handoff experiment

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_handoff.py'
python -I -S -B scripts/compat/hermes-maintenance-handoff.py --candidate-root <frozen-0.21.6-checkout>
```

This separate test uses the same eleven pinned input hashes and transforms only disposable
copies. A trusted test parent retains ready exclusive home/checkout leases, creates an anonymous
AF_UNIX socketpair and writes a private context. It closes the writer, opens a read-only FD and
unlinks the file before spawning. Explicit `pass_fds` transfers that FD, one private channel end
and the actual lock descriptions. There is no discoverable socket, environment token or reusable
receipt path; descriptor integers or JSON alone are not authority. This is NOT an OS-sealed file
or protection against an arbitrary malicious process with the same UID.

The context binds schema, recovery role, canonical run UUID, fixed candidate source label,
canonical checkout/active home plus directory identities, requested entry, domain records/registry
identities and hashes of the eleven staged source inputs (including the transformed bootstrap).
The trusted launch arguments independently supply the expected run/source; actual home, checkout,
entry and domains must match. Candidate label plus this bounded input set is **not** verification
of the entire Git tree or runtime/dependency closure. Directory ownership/permissions, no-follow
regular source reads, per-file/total budgets and before/after metadata checks constrain observation;
they do not create an immutable filesystem snapshot or prevent all same-UID/TOCTOU attacks.

Before any copied package import, `bound_handoff.py` validates this context and adopts the actual
exclusive locks. It sends `ready` with a fresh child challenge and binding digest. The parent checks
that digest and sends `grant` with a fresh transfer value; the child checks and revalidates actual
files before `accepted`. Only after the parent validates accepted and sends `commit` does the child
revalidate, send `committed` and enter the copied prefix. Each canonical JSON frame is bounded to
16 KiB including newline, with a two-second read deadline and strict shape/types/duplicate-key
refusal. Nonces and private context never appear in output. The parent retains its own lease until
committed (and, in this experiment, the prefix checkpoint). The child closes original inherited FDs
after adoption; CLOEXEC duplicates retain exclusion until controlled exit, using close, never unlock.

Five positive cases cover the four entry modules and one explicitly provided named profile home.
After parent close, both an outsider writer and a competing maintainer remain excluded; after child
exit maintenance can drain. Thirteen negative cases cover wrong run/source/home/role/entry/domains,
changed source after context creation or after hello, mismatched challenge, discarded/altered accepted,
missing lock and fresh independent opens of the same lock inode. All refuse before copied-package
initialization with zero home mutation attempts, while outsiders still cannot enter through the
parent's gates. Challenge mismatch is generated by changing this run's challenge, not replaying a
captured packet. Lost accepted is simulated by discarding it and closing without commit.

Nine tests exercise the matrix plus eager package writes on refusal, private FD shape, actual source
and directory changes, symlinks/traversal, canonical JSON/types, partial frames/deadlines/EOF/limits,
explicit roles and bilingual `HR-HERMESUPDATE-002` redaction. A disposable mutant skips actual-file
validation while leaving the private protocol and kernel locks intact: changed source is then
admitted and writes scratch, demonstrating why matching context bytes alone is insufficient.
Both macOS and Linux CI run these tests and the fixed real-source experiment.

Only `liveBindingChecksVerified=true` is claimed. `productionAuthorityVerified=false`,
`durableHandoffVerified=false`, `nativePreloadVerified=false`, `cronPackageBootVerified=false`,
`fullStartupVerified=false`, `globalAdmissionProven=false` and `certifiesUpgrade=false` remain
explicit. PM/recovery are still injected no-ops. Ordinary/shared-bootstrap experiments keep their
old kernel-only semantics; this is no upstream patch, supported launcher or GO execution adapter.
The live anonymous channel does not authorize a new process from a saved receipt after restart.
Actual PM/official-lock ordering, native/cron/other writers, trusted fleet registration, all profiles,
services and first legacy transition remain separate work. If a final acknowledgement is lost after
commit, retaining the parent's locks cannot prove the child did no work; durable resolution of that
uncertain state, mid-protocol death, re-exec and OS restart are not certified by these original cases.

## Commit uncertainty and process-death experiment

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_failures.py'
python -I -S -B scripts/compat/hermes-maintenance-failures.py --candidate-root <frozen-0.21.6-checkout>
```

`Uncertain` now distinguishes any issuer failure while/after attempting commit from a pre-commit
refusal. Classification starts BEFORE the first possible commit byte, including failed/partial
sends. Confirmed means only the live handshake completed. A child success report cannot release an
uncertain parent's original lease. The probe verifies actual exclusion after the child exits,
before any fresh lock acquisition could hide a release gap. The parent's original lease remains
until test cleanup; this experiment does not automatically retry, cancel, recover or publish a
durable result, and nothing here is a production coordinator.

Eight fixed-source cases (four entries × two faults) discard commit before delivery or discard a
valid final acknowledgement after the child is authorized. Both are issuer-uncertain, but only
the latter executes real scratch writes. These are controlled discard injections, not a claim
of physical packet loss or OS crash recovery. The same injected PM/early-recovery and bounded
scratch-prefix limits still apply. A disposable mutant restores close-on-child-success: after
the child exits an actual writer can enter, exposing the gap that a pre-close boolean misses.

`handoff_actor.py` separately uses **synthetic actors**, one placeholder source input and a marker,
not copied Hermes code, to test actual SIGKILL. The supervisor owns no maintenance descriptors;
only the test parent and child do. Independent private control pipes pause the child after ready,
after accepted, after validated commit before final ack, or after authorization/marker write.
At each point kill the parent Popen object or command the child to SIGKILL itself; never signal a
discovered PID. Parent death leaves child custody; child death leaves parent custody. Releasing a
paused pre-commit child after parent death refuses before the marker. A child that cannot send final
ack also refuses; an already authorized child retains exclusion until controlled exit. Parent
classification for child death is pre-commit at ready, uncertain at accepted/commit-received and
confirmed at authorized, even though the child is dead. Confirmation is not service boot evidence.

A ninth case kills the authorized child and then the parent. With all live custodians gone, an
actual fresh writer can enter and another maintainer can drain. `allCustodiansGoneAdmitsWriter=true`
is a mandatory **negative control**, not a completion signal. Kernel locks do not remember a
maintenance transaction after all descriptors are gone. A durable admission intent checked by every
participant, trusted restart identity and resolution of uncertain work are still required.

Six tests cover the full synthetic matrix, close-on-child-success mutant, failed commit send,
pre-commit transport failure, bounded control frames/explicit test scope and bilingual redaction.
Linux/macOS CI run those tests plus the fixed-source cases. The optional `accept` checkpoint callback
is only for this test fixture, not an upstream or production extension point. No real PM activation,
native preload, cron package boot, service, ledger/business task or OS reboot runs in the death
fixture. Production authority, durable handoff, full startup, global admission and upgrade
certification remain false. The supervisor's observation is not a restarted owner's recovery proof.

## Persisted admission intent experiment

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_durable.py'
python -I -S -B scripts/compat/hermes-maintenance-durable.py --candidate-root <frozen-0.21.6-checkout>
```

Explicit domain enrollment now uses schema 2 and pre-creates an owner-only fixed-inode `intent` slot,
pinning its identity alongside the gate/lifetime locks. New writers acquire every shared admission
gate, check each slot for EXACT enrolled idle bytes, then take lifetime locks. Any non-idle bytes,
including empty/torn/oversized records, prevent admission. Missing/replaced/linked/unsafe controls
refuse; old schema does not auto-migrate or enroll. The original death matrix publishes no intent,
so its negative control remains valid for the raw kernel-only lifecycle.

`durable_intent.begin` requires drained exclusive home/checkout custody, revalidates actual lock/control
identities and the independently expected binding context, and only accepts two initially idle slots.
It writes the same canonical context plus digest to both stable files, fsyncs each and the registry,
then revalidates before returning. This module has no unlink, replacement, truncation/reset, clear or finish API.
No data operation may happen before successful return. `resume` takes fresh exclusive/drained custody
and requires EVERY slot to match the independently expected record exactly; it synchronizes and checks
again before returning. Existing/malformed/partial slots never authorize a fresh begin or implicit repair.
The digest detects byte mismatch; it is not a signature or authority from saved JSON.

The context includes run/source/home/checkout/role/entry/domain identities and the eleven staged source
input hashes, with all earlier bounded-input limitations. Fixed candidate label is not a whole Git/runtime
closure proof. A trusted test launcher passes a read-only/unlinked private context FD plus independently
expected run/source and actual entry/home to a NEW recovery process. No old lock descriptors are used;
the new process must take exclusive locks and match the persisted intent before any copied package
initialization. The supervisor keeps the expected context in memory and owns no maintenance locks.
This does NOT recover consent after that supervisor/Desktop also dies or authorize from a receipt alone.

Four frozen-source entry fixtures SIGKILL their publisher AFTER all slots/registry synchronization. A
new ordinary writer is then blocked before real scratch writes. Four new recovery prefixes succeed;
wrong run/source/home/entry/role/scope or changed source rejects in 28 cases before copied imports,
and an overlapping recovery cannot enter. Recovery exit leaves intent bytes unchanged and still blocks
ordinary writers. PM remains injected and execution stops at scratch, as in earlier experiments.
A fifth publisher dies after syncing only the first slot: its exact pair stays closed, resume refuses,
and no control is repaired. Partial multi-domain publication is not atomic: a different pair omitting
the written slot may enter, but no data mutation was authorized before all target slots were synchronized.

Ten tests cover that matrix and a disposable mutant that omits idle validation: a post-death writer
then creates scratch. Additional cases use real control files for corrupt/empty/torn/oversized contents,
replacement/symlink/hardlink/permissions/missing/legacy bindings, begin before drain, exact resume,
partial pwrite and failed fsync, invalid/oversized context, shared-home/checkout versus unrelated scope,
and bilingual redaction. Linux/macOS CI run tests and fixed-source cases. The publisher is a synthetic
process, not an official updater. The new recovery prefix uses actual copied input but not real services.

`processRestartAdmissionVerified=true` claims only this process-level admission property. Production
authority, full durable handoff, OS reboot, native preload, cron package boot, full startup, global
admission and upgrade certification stay false. fsync return is not physical power-loss verification.
Same-UID malicious rewriting/rollback of the control plane is excluded. This base matrix has no automatic clearing,
partial-record repair, completion proof, actual changed-source/environment-generation transition or
cross-exec/service handoff. Idle controls are never reconstructed from empty locks or old metadata.
Production trusted enrollment, all writer coverage, legacy transition and independent final service/
business/device evidence remain necessary. This module is still not installed or adopted by GO/Hermes.

## Fixture-only generation transition and terminal policy

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_transition.py'
python -I -S -B scripts/compat/hermes-maintenance-transition.py --candidate-root <frozen-0.21.6-checkout>
```

This experiment adds `generation_transition.py` and a test-owned coordinator actor. The driver checks
the same eleven pinned source inputs before making disposable copies. It adds a twelfth, synthetic
dependency-generation text marker and plans a source comment change. The target source label is
synthetic, NOT another upstream Git commit/version. No real environment, PM activation, package
installation, official update or service runs. Scope is exactly a `hg199-durable-*` temporary root
with its `control`, `data` and `checkout` domains and the policy `scratch-prefix-fixture-v1`.
That path restriction is an accidental-use guard, not a production authorization mechanism.

Under drained exclusive custody, `prepare` validates the current before context and appends a canonical
before/after plan to BOTH existing intent slots. Only source/file hashes may differ; all run/home/
checkout/entry/role/domain identities remain bound. Each slot and registry is synchronized before
return. `resume_before_update` requires the exact plan and old actual inputs; after changing only the
planned fixture bytes, `target` requires those new actual hashes. No old record is rewritten to bypass
changed-source refusal. Partial/malformed/unplanned state refuses without repair. This is append-only
within the existing stable inodes: no truncation, replacement, unlink or reconstruction of idle bytes.
Individual canonical frames/private contexts remain bounded to 16 KiB; the journal limit is 64 KiB.

`check_fixture_prefix` owns and observes a real isolated child. It passes actual exclusive descriptors,
a read-only/unlinked target-context FD, a separate independently expected plan FD and a fresh private
socketpair. Before any copied package import, the child validates target plan/actual files under
inherited exclusive custody, then performs the live bound handshake. The monitor requires the exact
result shape, verified binding, bootstrap entry, one admission call, observed home mutation and actual
scratch directory. It releases the child and requires normal exit zero, then rechecks actual target
inputs. Only this monitor mints an in-memory witness bound to the same lease object, plan, a fresh
nonce and a five-second monotonic lifetime. No handshake result alone or saved JSON authorizes finish.

`finish_fixture` accepts that one-use witness and appends a fixture terminal; an uncertain append
consumes the witness too. It is explicitly NOT a production completion API. A new ordinary writer,
under ALL shared admission gates, may accept a non-idle pair only when both have identical canonical
three-row history, the exact fixture policy and actual target inputs matching the plan. Restoring old
source bytes or the old marker refuses. Before entry it synchronizes both slots/registry itself and
rechecks contents; visible complete bytes do not prove a dead publisher returned from fsync, and a
sync failure refuses admission. Another pair sharing a home/checkout cannot borrow this
completion. Prior intent and plan remain readable. This single-epoch fixture has no next-upgrade
history/archiving protocol. Hash chaining and a nonce are integrity checks in the trusted registry;
they are not signatures or protection against malicious same-UID rewriting/rollback.

The frozen matrix completes four entry fixtures, rejects eight stale source/marker cases, and kills
ten test-owned coordinators: four after complete plan publication, four after complete terminal
publication, plus first-slot interruptions in plan and terminal publication. The lock-free supervisor
retains the independent expected plan. After plan death it can reacquire exclusive custody and
validate before mutation; after full terminal death a new writer validates history/target before
its scratch prefix. Partial plans/terminals stay closed and unchanged. No discovered PID is signalled.

Ten tests cover this matrix, immutable plan identity, target drift, absent/boolean/saved witnesses,
expired/cross-custodian/one-use evidence, incomplete child results/nonzero exit, partial terminal and
overlap refusal, corrupt/truncated/extra history, scope/policy and bilingual redacted errors. A copied
mutant omitting the witness check opens admission with no prior observed prefix; the real policy
rejects it. Linux/macOS CI run all cases and the separate frozen-source matrix.

`fixtureCompletionPolicyVerified=true` is separate from `realDependencyGenerationVerified=false` and
`productionCompletionVerified=false`. Production authorization, full durable handoff, actual services/
business, native preload, cron initialization, full startup, global admission, OS reboot/power loss
and upgrade certification remain unverified/false. After monitor death before terminal, the next
coordinator must re-observe the prefix; partial records need a separate recovery design. Consent
after the supervisor/Desktop also dies is not recovered from journal JSON. The prior durable-intent
module still has no clear/finish/reset operation. GO execution/recovery remain disabled, the installed
Hermes is untouched, and no upstream PR is created by this fixture work.

## Isolated actual PM generation observation

```bash
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_pm.py'
python -I -S -B scripts/compat/hermes-maintenance-pm.py --selector-matrix <frozen-0.21.6-checkout>
# Explicit opt-in, macOS only: new disposable HOME, tools and cache; network downloads allowed.
python -I -S -B scripts/compat/hermes-maintenance-pm.py --provision --candidate-root <clean-pinned-git-checkout> --fault-matrix
# Read/recheck a marked fixture; adding --fault-matrix explicitly tampers/restores its own test inputs.
python -I -S -B scripts/compat/hermes-maintenance-pm.py --fixture-root <printed-fixture>
```

This is an observation tool, NOT a new completion policy. A private `hg199-pm-generation-*` root
must have the exact owner-only marker and mode 0700. Before provisioning it verifies the full fixed
Git input using the existing worker source guard, then makes a new clone and new data/control/user/
cache directories. No old seed, installed Hermes, real conversation or existing environment is used.
Drained exclusive home/checkout custody publishes a durable intent BEFORE official
`pm.client.sync_venv([], explicit=True)` runs. That intent is never finished or cleared here.
The real PM runs with a whitelist environment in a macOS kernel sandbox denying signals, service/
sudo/brew execution and writes outside the fixture. Network is allowed only during this explicit
provisioning step; no official updater/finish or application service runs. Its private log is retained.

`pm_generation.py` verifies actual exclusive descriptors before observing. A bounded child runs the
three SHA-256-pinned PM selection inputs (`pm/__init__.py`, `pm/environments.py`, `hermes_constants.py`)
and reads the committed environment without activation/provisioning. The selected facts/install key,
environment, site-packages and interpreter must be inside the expected fixture layout; Python must
resolve into its tools. Another child runs that selected interpreter with `-I -S -B`, explicitly adds
only the selected site-packages (no .pth execution), imports `packaging.version` from there and
exercises SSL/SQLite/SHA-256. It checks executable/base/module paths, output shape, status zero and
bounded single-frame output; interpreter major/minor must match the selected site directory. A
regression first reproduced acceptance of a 3.12 report for a 3.11 site and passes after this guard.
This check is not native-extension ABI certification. Observer children deny writes/process/network via an audit tripwire;
the opt-in macOS run also inherits a kernel no-network/no-outside-write sandbox. Audit hooks alone
are not an OS security boundary.

Observation binds facts bytes, source activation inputs, pyvenv.cfg, the actual binary, runtime output,
and content/metadata digests of both the selected environment and Python base tree. Limits are 65536
entries, 2 GiB per observation, 512 MiB per file and depth 64. No-follow reads compare before/after
identity/size/mtime/ctime/mode/owner; traversal refuses special files, escaping links and changed trees.
Internal links stay within those two selected roots. Hardlinked regular files are observed, not
backed up or treated as immutable. The one known uv `venv/.lock` may be empty mode 0666, nlink 1 under
the private 0700 fixture; it is still included in the digest. Other writable inputs refuse. No
permissions are changed to obtain a passing observation. This is not an atomic whole-filesystem
snapshot or protection against malicious same-UID rewriting.

Lower-level sync publishes a committed dependency generation but DOES NOT publish the shell's
activation input stamps; source audit finds that responsibility in the full `pm.cli.cmd_install`
flow. Therefore a wholly absent inputs directory records `activationInputsCurrent=false` without
inventing stamps. Present inputs must all match nanosecond mtimes and .project-root; partial/stale
publication refuses. An observation with this false flag is not published-start/activation readiness.
The tool does not install all extras/default tools merely to fabricate that readiness.

`revalidate` performs a new actual observation and compares the whole result. A saved dictionary is
only comparison data, never user consent, a witness for finish, or authority to open admission.
The explicit fault matrix changes a test-only dependency file, facts whitespace and lock input bytes
while retaining mtime, restores its own bytes, and verifies fresh observations again. All three must
invalidate the prior observation despite identical generation path and reported versions. Kernel
custody is held throughout; only newly created test children can be killed on timeout. PM's own
descendants do not thereby gain this protocol: official child custody/lock ordering and coordinator
death during sync remain unverified. Active intent blocks cooperating writers after inspector exit.

Fourteen tests use SYNTHETIC selector inputs/runtime reports for ordinary portable CI. They cover
content/binary/facts/source/stamp/project drift, missing config, exact exclusive scope/marker,
unpublished versus partial stamps, malformed runtime reports, escaping links, special files,
permissions, budgets, bounded/error/side-effect child behavior and bilingual redaction. A negative
control omits tree observation and then misses a same-generation dependency change. Separately,
Linux/macOS execute the REAL pinned selector against one synthetic selection and three invalid
records; its output explicitly says no real PM provisioning or selected interpreter execution.
Real PM/runtime/content-drift evidence comes ONLY from the opt-in Mac run, not those CI records.

`selectedPMGenerationObserved=true` is deliberately separate from the prior synthetic transition's
false `realDependencyGenerationVerified`. This observer publishes NO maintenance terminal and does
not add evidence to that policy or the GO Operating Machine. Dependency closure/ABI/dlopen/OS/whole
Hermes activation, service boot/business, durable consent/recovery, full startup/global admission and
upgrade certification remain false/unverified. The selected packaging import is not a business test.
The prepared-target diagnostic below adds explicit plan comparison. Trusted production plan origin,
actual code-generation transitions and the missing services/writers remain unverified; an observed
current environment cannot authorize itself.

Local 2026-10-16 evidence: the complete new-home `--provision --fault-matrix` run passed in
`/private/tmp/hg199-pm-generation-n0r7sgc8`. Official sync completed from a new tools/cache root,
selected Python 3.14.7 and packaging 26.0 actually ran, and 8477 environment/2013 Python-base entries
(240323588 observed bytes) were rechecked. All three drift cases refused old observations, restored
their own bytes and passed fresh rechecks. After all inspector locks closed, an actual ordinary
prototype writer remained blocked by intent. `activationInputsCurrent=false` and every closure/
terminal/production/durable/full-startup/global/upgrade claim remained false. This is not a full
Hermes cold-install, CLI activation, service or business acceptance result. Portable unit baseline:
110 tests passed; the pinned real-selector synthetic matrix passed one selection/three refusals.

### Prepared-target plan diagnostic (2026-10-20)

`pm_target.py` compares a separately supplied canonical plan with a marked PM fixture while holding
the actual drained home/checkout exclusive leases. It has no plan builder, finish, clear or admission
API. The plan requires the exact original 11-file context, all six PM selector/lock input hashes,
environment/Python-base paths and directory identities, both complete tree digests, exact Python
and packaging versions, and one of two fixed policies. Expected run, source and canonical plan SHA
are separate required arguments. Both intent slots must still contain exactly this transaction;
neither an idle/partial slot nor a new run can borrow it. Two fresh observations and a final context/
intent/input recheck reject drift; saved observations and booleans are not plans.

`pm-prepared-target-diagnostic-v1` permits an explicitly unstamped prepared environment for diagnosis.
`pm-activated-target-diagnostic-v1` additionally requires current real activation input stamps; it
never fabricates them. Neither policy establishes upgrade readiness or production authority.

```bash
python -I -S -B scripts/compat/hermes-maintenance-target.py \
  --fixture-root <marked-disposable-pm-fixture> --plan-file <owner-only-canonical-plan> \
  --expected-run <original-run> --expected-source 818c13be1dc4fd28987e1e881a9408224afd4535 \
  --expected-plan-sha <independently-supplied-canonical-plan-sha256>
```

The CLI accepts only private 0600 bounded regular plan files, verifies the pinned full Git source
before copied code execution, and uses the existing macOS sandbox for selector/runtime children.
It closes inspection custody and then confirms an actual ordinary writer is still blocked. Failures
use registered bilingual `HR-HERMESUPDATE-002`, retryable false, without private paths/exceptions.
The CLI reacquires fixture custody; it does not implement a durable authenticated recovery handoff.

Thirteen portable tests use synthetic reports, including the negative control where omitting the
second observation misses dependency drift. Linux full discovery and the macOS prototype job run
them. The actual Mac rehearsal reused the previously prepared new-home fixture: a one-off external
test launcher recorded tree/input expectations before starting the verifier, with known producer
versions Python 3.14.7/packaging 26.0. It identified the existing transaction from the intent **only as
test comparison data**, not as recovered consent. No plan was inferred inside the verifier. The
prepared policy matched 8477 environment/2013 base entries; the activated policy and wrong version,
tree digest, independent plan SHA and independent run all refused (five refusals). All intent bytes
remained unchanged and an ordinary writer remained blocked after every check released its locks.
This is a baseline of an already-prepared fixture, not a before-sync target decision, a real old/new
source upgrade, independently authenticated production expectations, service/business acceptance,
dependency closure or durable authorization after Desktop death. Every completion/authority/
handoff/upgrade certification flag remains false. Nothing connects this result to the older synthetic
`finish_fixture`, the Operating Machine or the execution UI.

### Inspection interruption and fresh recheck (2026-10-20)

`hermes-maintenance-inspection-recovery.py` exercises death of a **read-only inspector**, not death
inside PM sync or an official updater. It requires the same external plan/run/source/SHA arguments
as the prepared-target CLI. The surviving test supervisor validates the original active transaction,
retains the independently supplied comparison inputs and closes ALL of its maintenance descriptors
before starting an inspector. Each new inspector receives an owner-only read-only/unlinked plan FD;
the actor rejects linked/writable descriptors, reacquires its own exclusive leases and runs the
existing full-source guard and sandboxed selected-runtime observations. There is no saved-verdict
input, authorization recovery from the journal or finish/admission operation.

The target verifier exposes test checkpoints after initial plan validation (zero observations),
after the first actual observation, and after both observations and final transaction recheck.
The supervisor observes each exact bounded checkpoint, SIGKILLs only that Popen and confirms its
signal exit. At these checkpoints all selector/runtime children have exited; this does not test
death while PM/observer descendants are active. A fresh EX acquisition confirms inspector custody
is gone, both intent slots must remain byte-identical, and an ordinary writer must still be blocked.
Another new interpreter then rechecks the retained original plan and must exit zero with one exact
diagnostic result. Nonzero exit, extra output, boolean checkpoints and completion/authority flags
refuse. The last three runs separately reject a wrong run, independent plan SHA and environment
digest. Cleanup only stops processes this harness created; it never discovers or signals a PID.

```bash
python -I -S -B scripts/compat/hermes-maintenance-inspection-recovery.py \
  --fixture-root <marked-disposable-pm-fixture> --plan-file <owner-only-canonical-plan> \
  --expected-run <original-run> --expected-source 818c13be1dc4fd28987e1e881a9408224afd4535 \
  --expected-plan-sha <independently-retained-canonical-plan-sha256>
```

The public rehearsal is macOS-only; it neither provisions PM nor edits a selected generation. Ten
portable tests use real POSIX files, locks and test-process deaths with explicitly SYNTHETIC selector/
runtime reports. They check checkpoint placement, new-process launches, changed actual dependency
after a verified inspector dies, invalid expectations, descriptor custody, strict result/exit/output
handling, cleanup and bilingual redaction. A negative control accepts the saved report's shape after
dependency drift; the fresh inspector rejects that actual drift. Report shape alone proves no
freshness. The synthetic child implementation is confined to the test file, with no shipping CLI
option to enable it. Linux discovery and the macOS compatibility job run these tests.

The Mac opt-in reused the prior externally recorded prepared fixture baseline (Python 3.14.7,
packaging 26.0): three observed SIGKILL checkpoints, three new-process matches and three changed-
expectation refusals passed. Intent bytes stayed identical and ordinary admission remained blocked
after every inspector exit. `activationInputsCurrent=false`; no stamps were manufactured. The
supervisor survives this entire test. Expectations still originate from an already-prepared test
fixture, not authenticated production consent, a pre-sync target decision or durable authorization
after Desktop/supervisor death. No actual old/new source transition, updater interruption/resume,
service/business recovery, OS reboot, dependency closure or global writer coverage is verified.
`updaterRecoveryVerified`, `durableHandoffVerified`, `targetAuthorityVerified`,
`productionCompletionVerified` and `certifiesUpgrade` all remain false. Product execution stays off.

### Planned real Git source switch (2026-10-23)

`hermes-maintenance-source.py` fixes the old 0.21.3 commit `17b5df02` and target 0.21.6 commit
`818c13be`, accepts two clean self-contained local Git sources and creates new marked private
`hg199-source-transition-*` fixtures. It executes Git and prototype code only, never upstream
Python, PM, the official updater, bootstrap or a service. Both input sources remain read-only.
The clone copies objects without hardlinks; an explicit local fetch brings the verified target in
before enrollment. Subsequent Git operations prohibit transport, hooks, filters and lazy fetches
through the existing worker guard. All target commits/trees/blobs must already be present.

The source-only plan binds both actual commit/tree IDs and before/after contexts with the same
run/home/checkout/entry/domain identities. Four shared bootstrap source hashes are explicit; the
existing worker additionally checks the full Git tree/object inventory, clean index and every
Python source blob. Future file hashes are checked against target Git objects BEFORE plan append
or checkout; recomputing the plan digest cannot make inconsistent target hashes valid. This guard
has a regression that first failed without the target-object check. It is not runtime verification.

Under drained exclusive custody, `source_transition.prepare` appends the canonical plan to both
original stable intent slots, synchronizes slots/registry and rechecks. The original record is
never rewritten. A fresh coordinator requires the exact external run/before/after/plan SHA and
both identical histories, revalidates actual old inputs, then performs a local detached checkout
without force/reset/stash. After movement, a new coordinator verifies the planned target while
matching the ORIGINAL intent plus plan; it does not replace intent with a new-source context.
There is no terminal, finish, repair or admission API. Existing ordinary writers reject this history.

The matrix kills three test-owned coordinators: after full plan publication, after full checkout
and target verification, and after the first slot's plan append. The supervisor retains the plan
but no maintenance descriptor. Fresh EX acquisition/recheck verifies the first two boundaries;
the partial append is refused and remains unchanged. Six refusals cover wrong run/from/to/SHA,
old-stage reuse after checkout and changed actual target bytes. Intent inodes and published history
remain unchanged across checkout; ordinary writers remain blocked. No mid-checkout interruption,
official updater lock/PM handoff, service recovery, full startup or whole-writer coverage is tested.

```bash
python -I -S -B scripts/compat/hermes-maintenance-source.py \
  --old-root <clean-fixed-0.21.3-git-source> --candidate-root <clean-fixed-0.21.6-git-source>
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_source.py'
```

Eight portable unit tests use SYNTHETIC Git commits with real checkout, objects, locks, stable
intent files and process deaths. A negative control skips actual-source checks and then misses
tampering despite a matching plan/journal. Linux/macOS CI separately run the matrix on the real
fixed upstream commits; they do not install or execute Hermes. Public failures use the existing
bilingual non-retryable `HR-HERMESUPDATE-002` and redact private paths/low-level output. Fixtures
are retained for diagnosis, including the deliberately tampered target and incomplete plan.
This is a source observation policy, not authenticated production consent or a before-PM runtime
plan. `pmExecuted`, `runtimeVerified`, `updaterRecoveryVerified`, `productionCompletionVerified`,
`durableHandoffVerified` and `certifiesUpgrade` stay false. Product execution remains disabled.

### Joint source and PM preparation (2026-10-24, isolated only)

`hermes-maintenance-source-pm.py --provision` creates a NEW marked source-transition fixture from
fixed old 17b5df02 and target 818c13be Git inputs. An external plan fixes the original source plan,
the six distinct PM selector/lock inputs and the exact Python version declared by the target lock
before any movement or PM work. Future expectations must match actual target Git blobs, even if a
caller recomputes the JSON digest. The lock order is drained home/checkout EX, then the actual
upstream checkout/marker lock, then plan append, source move and PM synchronization. Unknown or
partial history remains pending; the original intent/inodes are never replaced or cleared.

The joint policy requires the real official checkout descriptor in addition to the prototype lease.
The source is moved locally under both locks. A `pm-sync-started` event is synchronized to both
original slots BEFORE spawning official `pm.client.sync_venv([], explicit=True)`. Parent custody
remains held throughout. Its top Python child receives the maintenance and official descriptors;
this does NOT prove every PM grandchild inherits them. A nonzero exit, timeout, process death or
failed checkpoint leaves started/partial history and cannot automatically replay synchronization.
There is no unknown-sync recovery or maintenance terminal API yet.

After a zero exit, the selected interpreter/packaging and actual environment are observed twice,
using the existing bounded read-only engine. The public PM observer retains its original fixture/
lease guard; the joint layer independently validates its stricter marked scope before using that
shared private engine. The prepared event binds actual environment/base directory identities,
content digests, exact runtime/packaging versions, activation status and the full pins digest
(including facts and activation inputs). The facts-byte regression first failed without pinsDigest;
merely restoring bytes/mtime does not undo the recorded ctime drift.

A new process must have the same external run/from/to/plan SHA AND a separate generation digest.
It acquires fresh locks, requires complete identical canonical prepared histories before running
any interpreter probe, then re-reads actual source/PM inputs twice and synchronizes/rechecks slots.
Saved JSON is comparison data; it cannot authorize completion. Wrong generation, facts/dependency
content drift, wrong stage/source, missing/foreign/readonly official descriptors, partial writes,
failed PM exits and plan mismatch refuse. CLI refusal retains bilingual nonretryable
`HR-HERMESUPDATE-002`, with no raw private path, child output or exception.

Mac real validation completed a fresh old-to-target Git movement plus official-lock-held PM sync:
selected Python 3.14.7 and packaging 26.0 ran, then fresh-process recheck passed. One self-owned
recheck coordinator was SIGKILLed only AFTER its actual observer children had exited; another
process still matched the same prepared generation. Wrong external generation and changed actual
facts were refused, the original history stayed identical and ordinary writers remained blocked.
The deliberately drifted fixture is retained for diagnosis; its checkpoint remains stale.

Portable unit cases use synthetic Git commits and PM/runtime reports, with real Git/locks/files/
subprocess execution; they are distinct from this real Mac run. Linux full discovery and macOS's
explicit suite cover them. They do not provision network dependencies in CI.

```bash
python -I -S -B scripts/compat/hermes-maintenance-source-pm.py --provision --fault-matrix \
  --old-root <clean-fixed-0.21.3-git-source> --candidate-root <clean-fixed-0.21.6-git-source>
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_maintenance_source_pm.py'
```

This is preparation observation, not a complete official updater run. Activation inputs remain
false in the real bottom-level-sync fixture; no stamps are fabricated. Supervisor death, PM-in-flight
child custody/recovery, source-move interruption, full CLI/services/business, dependency closure,
whole-writer coverage and production authority remain unverified. `updaterRecoveryVerified`,
`productionCompletionVerified`, `durableHandoffVerified`, `globalAdmissionProven`, `certifiesUpgrade`
remain false. The app does not import this prototype or enable execution.

### Actual PM worker spawn custody (2026-10-24, still isolated)

`pm_worker_custody.spawn` proposes a narrowly reviewed mutable-PM worker launch. It revalidates
the original joint plan, actual source/locks and identical `pm-sync-started` histories before
Popen. Only `[private-python, -I, -B, checkout/pm/worker.py]` is accepted; the actual executable must
resolve inside this fixture's tools and pass bounded byte/permission inspection. The caller remains
responsible for proving its toolchain. Home/data/cwd are bound to the fixture; PYTHONPATH/PYTHONHOME/
VIRTUAL_ENV, arbitrary spawn options and arbitrary commands are refused. close_fds remains true,
with only the actual maintenance/official descriptors explicitly inherited. No environment FD number
is authority. This is not wired into GO or the default joint PM sync.

Five portable tests use synthetic Git/PM-worker code and a fixture-only launcher of the host Python;
copying a Mach-O executable alone failed on its relative framework dependency, so that copy is NOT
claimed as a verified interpreter closure. Actual POSIX parent SIGKILL and active parent close prove
that a proposed worker retains both maintenance and official locks; the raw Popen negative control
loses both while its worker remains alive. A separate supervisor pipe gates the worker independently
of parent lifetime. After controlled worker exit, fresh locks return, but the original started intent
still blocks ordinary writers and runtime verification. Command/context/option refusal and bilingual
CLI redaction are also covered.

`hermes-maintenance-pm-worker.py --provision --mode raw|proposed` separately exercises the fixed real
old/target source and the actual `pm.client._request` worker command. Each mode creates a NEW fixture,
runs real official-lock-held PM runtime bootstrap, then captures exactly the mutable worker spawn.
The selected real Python 3.14.7 executes a no-mutation test gate in place of worker imports. The gate
reports descriptor identities over a private channel and remains alive after the test-owned caller
is SIGKILLed. Raw mode observed a free maintenance/official lock while the gated worker still lived;
proposed mode retained both. Both released on gate exit, and unknown started history refused BEFORE
any interpreter observation, unchanged and still closed to ordinary writers. Both real Mac modes
passed. The installed candidate's source bytes are never patched; the Popen hook exists only in the
isolated instrumented caller and only substitutes this captured spawn.

```bash
python -I -S -B scripts/compat/hermes-maintenance-pm-worker.py --provision --mode raw \
  --old-root <clean-fixed-old-git> --candidate-root <clean-fixed-target-git>
python -I -S -B scripts/compat/hermes-maintenance-pm-worker.py --provision --mode proposed \
  --old-root <clean-fixed-old-git> --candidate-root <clean-fixed-target-git>
```

The real worker OPERATION is never executed in this gate experiment. Runtime bootstrap is real;
all later PM worker/uv/build descendants and earlier bootstrap-death boundaries remain unproven.
A retained top-level worker does not prove a child/grandchild also retains custody if that worker
dies. No unknown-sync retry, full PM/updater recovery, terminal maintenance or product authorization
is added. `realPMWorkerOperationExecuted`, `allPMDescendantsCovered`, `pmInFlightRecoveryVerified`,
`updaterRecoveryVerified`, `productionCompletionVerified`, `globalAdmissionProven`, `certifiesUpgrade`
remain false. The next step is real downstream-mutator custody and recovery-policy validation.

### Native uv build descendant probe (2026-10-24, independent narrow experiment)

`hermes-maintenance-pm-native.py` executes the fixed target's actual `PythonEnvironment` API,
the existing disposable preparation's read-only uv 0.12.3/Python 3.14.7, and a new private local
PEP 517 project with no external build requirements. Tool bytes are independently reread in the
child and actual versions checked; this is not a toolchain provenance/closure certificate. The
original prepared root, source and tools are not modified. All cache, venv, build and temporary
outputs go to a new `hg199-pm-descendant-*` root; network and writes outside it are denied.

The narrowly reviewed proposed spawn keeps close_fds and passes only the enrolled, drained home/
checkout descriptions for the exact offline requirements command and fixture environment. The raw
control uses the original Popen options. The actual native uv process launches our stdlib local
backend, which reports descriptor identities and bounded heartbeat sequences. The test caller kills
its exact uv Popen and closes its own custody; the supervisor then SIGKILLs that caller. Only a NEW
heartbeat after both parents were reaped proves backend liveness. Raw mode observed free locks;
proposed mode kept both domains locked until the backend was released. Both actual Mac modes pass.
The local backend intentionally aborts; no wheel, sync success or upgrade completion is certified.

Unlike passive gates, this caller needs to interrupt its own uv. Its dedicated sandbox profile
permits signals while retaining file/network/service-exec restrictions; there is no external PID
input, broad kill or service action. Other passive experiment profiles keep their signal denial.

```bash
python -I -S -B scripts/compat/hermes-maintenance-pm-native.py --provision --mode raw \
  --source-root <clean-fixed-target-git> --prepared-root <prior-disposable-source-pm-root>
python -I -S -B scripts/compat/hermes-maintenance-pm-native.py --provision --mode proposed \
  --source-root <clean-fixed-target-git> --prepared-root <prior-disposable-source-pm-root>
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_pm_native_descendant.py'
```

Five portable tests cover custody/context refusals, temporary dup lifetime, escaped requirements,
strict opt-in/bilingual redaction and stale-versus-post-death heartbeat evidence. The CLI redaction
case failed before reusing the strict argument parser. Native uv/backend execution is opt-in only,
not represented by those tests. This independent probe publishes NO durable intent and tests NO
official update lock; it is not the original pm.client worker or full sync chain. Separate passing
worker/native boundaries cannot be composed into a full-tree proof. No arbitrary hooks, other
platforms, unknown-sync recovery, global writers or production authority are certified. GO/default
sync do not import this proposal; fullPMTreeCovered, pmSyncExecuted and certifiesUpgrade stay false.

### Same-run actual PM worker/native build chain (2026-10-24, isolated)

`hermes-maintenance-pm-chain.py` binds the fixed source transition, original durable intent, actual
official checkout lock, PM worker and native build to ONE new temporary run/home. A separate bounded
build plan fixes project/tool bytes, the selected worker interpreter and exact build_environment
wire arguments/package declarations. It uses a distinct pm-build-audit-started history row; it never
relabels a build as sync_venv. Anonymous private context/descriptions must match the actual drained
domains, source and history before upstream worker business. The real initial wire includes the
sender's cancel=false; duplicate/unknown packets, numeric Boolean substitutions, other operations,
package/callback/context/argument changes and unreviewed spawn branches refuse.

The fixed target source is unchanged. An isolated shim invokes its actual worker.main, checking
the wire before dispatch and instrumenting only the reviewed quiet pm.environment native branch.
The actual API creates a fresh environment and reaches the local PEP 660 editable backend. Each
mode has its own fresh source clone/tools/cache. Worker-only inherits the actual maintenance and
official descriptions into the worker but keeps the original native options. Full-chain explicitly
passes those validated descriptions at the reviewed native venv/lock/sync spawns too, retaining
close_fds. No context/control/event descriptors are passed to uv/backend.

The supervisor waits for the actual backend, requests that its shim kill its exact uv Popen, then
requires worker business/cleanup to stop while custody is STILL held. The caller kills that exact
worker Popen, stops its own SDK cleanup before closing custody, and is SIGKILLed by its supervisor.
A NEW heartbeat after ALL THREE parents were reaped proves backend liveness. Actual Mac modes pass:
worker-only loses both kinds of lock; full-chain retains home/checkout/official locks until backend
exit. Original history bytes stay identical, the default sync verifier refuses before any runtime
observer, and proposed ordinary writer admission stays closed after all locks are free.

```bash
python -I -S -B scripts/compat/hermes-maintenance-pm-chain.py --provision --mode worker-only \
  --old-root <clean-fixed-old-git> --candidate-root <clean-fixed-target-git>
python -I -S -B scripts/compat/hermes-maintenance-pm-chain.py --provision --mode full-chain \
  --old-root <clean-fixed-old-git> --candidate-root <clean-fixed-target-git>
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_pm_chain_custody.py'
```

Eight portable cases cover plan/tool/project/wire/options/history refusal, actual cancel shape,
duplicate/oversized controls, editable backend entrypoints and bilingual/nonretryable CLI redaction.
A wrong fixed commit first exposed uncaught Refused classes from separately loaded modules; their
known aliases now map to HR-HERMESUPDATE-002. Earlier real attempts missing cancel or build_editable
failed and are not counted as successful chain evidence. The same dedicated sandbox signal exception
as the native probe permits only code with exact owned Popen handles; file/service-exec boundaries
remain, and network is allowed for the earlier real bootstrap. The build itself is offline/local.

This is ONE observed post-bootstrap quiet build path, deliberately aborted. PM runtime bootstrap
itself completes before the measured death boundary. `syncVenvExecuted=false` means the application
sync_venv API was not called; private runtime/local-build sync primitives DO run. There is no finish,
unknown-sync retry, arbitrary branch/full-tree/global-writer proof or durable production authority.
Bootstrap-death, allPMDescendantsCovered, updaterRecoveryVerified, globalAdmissionProven and
certifiesUpgrade remain false. GO/default joint sync do not import this audit.

### Actual runtime preparation death boundary (2026-10-24, local project only)

`hermes-maintenance-pm-bootstrap.py` exercises the fixed target's actual
`pm.runtime.prepare_runtime` / `stage_runtime` / `PythonEnvironment` APIs, in a NEW marked
source-transition fixture with its original two-slot intent and actual official update lock.
It copies uv 0.12.3 and the complete private Python 3.14.7 tool directory from an existing
marked disposable preparation; it never reads an installed Hermes. File bytes are checked,
but this copy is not a signed toolchain provenance or complete interpreter-closure certificate.

The API's explicit project parameter selects a private local dependency graph instead of PM's
real runtime dependencies. A preliminary offline lock and metadata generation are fixture setup,
outside the measured death boundary. The bounded plan binds project/backend/lock bytes, tools,
original joint identity and stable private `.prepare.lock` inode. A distinct
`pm-runtime-audit-started` row is synced BEFORE runtime preparation; it is not a sync checkpoint.
The unchanged upstream API acquires its actual preparation lock. The isolated actor captures
that exact descriptor after successful upstream lock_fd, and rechecks it with the exact reviewed
streamed native venv/sync command, private generation, temporary project bytes and environment.
Other commands/output branches are refused. No GO/default PM path imports this proposal.

Original options retain close_fds without passing custody; the proposal explicitly passes only
the four maintenance roles, actual official lock and actual preparation lock. The local PEP 517
backend scans their inode identities. After its exact UV Popen is killed, the actor waits for
upstream preparation/cleanup to stop BEFORE closing custody; its exact caller Popen is then
SIGKILLed. A new heartbeat after BOTH parents are reaped proves backend liveness. Actual Mac
raw/proposed runs pass: raw loses all three kinds of exclusion; proposed retains all six lock
descriptions until backend exit. Fresh locks become available afterwards, no selected generation
is published, history is unchanged, and the original intent still blocks cooperating writers.

```bash
python -I -S -B scripts/compat/hermes-maintenance-pm-bootstrap.py --provision --mode proposed \
  --old-root <clean-fixed-old-git> --candidate-root <clean-fixed-target-git> \
  --prepared-root <marked-disposable-source-pm-tools>
python -I -S -B -m unittest discover -s scripts/compat -p 'test_hermes_pm_bootstrap_custody.py'
```

Eight portable tests use synthetic inputs and real POSIX locks: strict plan/tool/project/context,
actual preparation fd, copied scratch content, partial history/default sync refusal before an
observer, all-six-description retention versus the raw negative control after parent close,
metadata without an early build, and strict bilingual CLI refusal. CI runs those on Linux/macOS;
the actual native API experiment is separate, opt-in and macOS-only. The sandbox forbids network,
outside writes and service tools; its signal exception exists only for exact owned Popen handles.
Error cleanup also joins business threads before explicitly closing descriptors; an unjoinable
fixture thread ends its own process atomically rather than releasing custody under live writes.

This deliberately failed local graph does NOT install real PM dependencies, publish a successful
runtime, test downloads/tool realization, validation/GC/worker launch, arbitrary native branches,
full runtime bootstrap, in-flight replay/recovery, all writers or production authority. The normal
joint sync verifier refuses this history. `localRuntimePreparationDeathVerified=true` is narrow;
wholeRuntimeBootstrapVerified, allPMDescendantsCovered, updaterRecoveryVerified,
globalAdmissionProven and certifiesUpgrade remain false. No terminal intent or automatic retry
is added, and Desktop upgrade/recovery execution remains disabled.

### Actual candidate SessionDB nonparticipant (2026-10-24)

`hermes-maintenance-database-writers.py --candidate-root <fixed-bf0203a7-snapshot>` verifies the
complete source before any enrollment/import and again afterwards. A new private registry binds
the supplied checkout and a NEW synthetic home. The supervisor retains all four drained EX roles;
an independent cooperating child refuses admission before AND after another process runs the
actual unmodified SessionDB create/write API. Three sample message contents and SQLite quick_check
confirm real writes. After parent close, the cooperating process may enter again. No intent is
published; there is no official checkout lock, live data, service or updater in this probe.

The actual Mac fixed-source run observes the expected nonparticipant write. This is a counterexample
to treating our external locks as global exclusion, not evidence that all other upstream entry
points lack protection. The separate retirement-module experiment on the same candidate still
observes process-local retirement, but is not used to infer the database result. No GO production
component imports this harness or treats a passing counterexample as upgrade certification.

The DB child inherits no maintenance descriptions or owner configuration. It has private HOME/data/
cache/tmp and Python audit tripwires for outside writes, process/signalling and network operations.
The reviewed version_info import needs seven exact read-only Git command shapes in the fixed source
cwd; only these are allowed, with transport/global/system config/replace refs/hooks/fsmonitor and
optional index writes disabled. Other commands, explicit environment overrides and other cwd refuse.
The first two real attempts refused incomplete version-query allowances and do not count as success.
No database guard, model/auth response or retirement result is patched. These hooks are not an OS
sandbox for malicious native modules; this probe accepts only verified fixed upstream bytes.

Seven portable tests use SYNTHETIC SQLite/API code and real kernel/cooperating processes: locked
control plus writes, drift before import/enrollment, refused API fails rather than certifies, outside
write and Git mutator tripwires, missing cooperative control and bilingual CLI redaction. The real
upstream API is executed separately by the fixed candidate history job, with isolated pinned test
dependencies. Both globalAdmissionProven and certifiesUpgrade always remain false. The upstream
integration and first nonparticipating-version transition are still required before execution.
