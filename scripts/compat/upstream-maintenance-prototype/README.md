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
| Cancel/finish | Close this owner's descriptors; inherited children may still hold them |

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

Thirteen tests use synthetic participants and actual OS locks: concurrent writers,
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
- `authenticatedHandoffVerified=false`: run/source/home/role authorization, trusted
  enrollment, transfer acknowledgement, restart/re-exec and durable custody are not
  implemented. A saved JSON descriptor number or same-inode match is not authority;
  an unlocked descriptor could newly acquire an exclusive lock without proving any
  parent transaction. The primitive is used only by the explicit test-owned launcher.
- `fullStartupVerified`, `globalAdmissionProven`, `certifiesUpgrade` stay false.

Recovery role must be selected before ordinary writer admission and bound to an
authorized transaction before production use. Never infer it from a subcommand name,
PID, environment flag or a stale receipt. Package initialization, Python startup/.pth
hooks, direct library writers, actual PM/official update locks and first legacy
transition remain separate integration work. The legacy entry's broad bootstrap
`ModuleNotFoundError` fallback also needs a fail-closed audit before adoption.
