#!/usr/bin/env python3
"""Opt-in real fixed source + PM preparation; never production upgrade completion."""
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import uuid

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    # Upstream's standalone UpdateLock uses dataclasses; it must have a real module identity.
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


source_cli = load("joint_source_cli", HERE / "hermes-maintenance-source.py")
pm_cli = source_cli.cli.driver
joint = load("joint_source_pm", pm_cli.PROTOTYPE / "source_pm.py")
lease = joint.source.load("maintenance_lease")


def official(root, code_root, commit):
    # Reject non-fixtures and non-pinned code before importing any upstream module or creating
    # its marker. A valid arbitrary Git commit is not authority to execute UpdateLock code.
    joint.binding.directory(root)
    marker = root / ".hg199-isolated-source-pm"
    info = marker.lstat()
    if (not root.name.startswith("hg199-source-transition-") or root.stat().st_mode & 0o077
            or info.st_mode & 0o077 or info.st_uid != os.getuid() or info.st_nlink != 1
            or joint.binding.snapshot(root, [marker.name])[marker.name] != joint.source.sha(joint.MARKER)
            or commit != source_cli.NEW):
        raise ValueError("marked fixed-source fixture required before upstream import")
    joint.source.worker.verify_source(code_root, commit)
    module = load("joint_official_lock_" + uuid.uuid4().hex, code_root / "hermes_cli/update_lock.py")
    lock = module.UpdateLock(path=root / "data/.hermes-update-in-progress", install_root=root / "checkout")
    if not lock.acquire():
        raise ValueError("official update already owns this isolated checkout")
    fds = module.custody_spawn_kwargs().get("pass_fds", ())
    if len(fds) != 1:
        lock.release()
        raise ValueError("actual official checkout descriptor required")
    return lock, fds[0]


def held(root):
    value = lease.Lease([lease.Domain(root / "control", kind, root / name)
                         for kind, name in (("home", "data"), ("checkout", "checkout"))], maintenance=True)
    if not value.drain():
        value.close()
        raise ValueError("joint fixture writers not drained")
    return value


def recheck_child(fixture, plan, expectations, generation_sha, hold=False):
    manifest = joint.binding.private_context_fd(fixture.root / "control", plan)
    process = None
    try:
        process = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(pm_cli.PROTOTYPE / "source_pm_actor.py"),
                                    str(fixture.root), str(manifest), *expectations, generation_sha,
                                    "hold" if hold else "verify"],
                                   env=pm_cli.environment(fixture.root), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(manifest,))
        os.close(manifest)
        manifest = None
        value = joint.pm.support.read(process.stdout.fileno(), timeout=120)
        if value != {"jointRecheck": True, "held": hold}:
            raise ValueError("new coordinator did not recheck joint source/PM")
        if hold:
            process.kill()  # Only this Popen; probes have already exited at this checkpoint.
            if process.wait(timeout=5) != -signal.SIGKILL:
                raise ValueError("joint coordinator death not observed")
        elif process.wait(timeout=10) != 0:
            raise ValueError("new coordinator failed")
        if process.stdout.read(1):
            raise ValueError("unexpected coordinator output")
    finally:
        if manifest is not None:
            os.close(manifest)
        if process is not None:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()
    fixture.blocked()


def provision(old, candidate, faults=False):
    if sys.platform != "darwin":
        raise ValueError("real isolated PM preparation requires macOS")
    if any(root.is_relative_to(Path.home() / ".hermes") for root in (old, candidate)):
        raise ValueError("live installation refused")
    # Source-only constructor validates both fixed real Git inputs and creates a NEW clone/home.
    fixture = source_cli.Fixture(old, candidate)
    marker = fixture.root / ".hg199-isolated-source-pm"
    marker.write_bytes(joint.MARKER)
    marker.chmod(0o600)
    locked = json.loads((candidate / "pm/lock.json").read_bytes())["packages"]["python"]["version"]
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)\+[A-Za-z0-9._-]+", locked)
    if not match:
        raise ValueError("unknown pinned runtime")
    plan = {"schema": 1, "policy": joint.POLICY, "source": fixture.plan,
            "pmInputs": joint.binding.snapshot(candidate, list(joint.PM_INPUTS)),
            "pythonVersion": list(map(int, match.groups()))}
    expectations = (*fixture.args[:3], joint.binding.digest(plan))
    value = fixture.held()
    lock = None
    try:
        # Fixed lock order: drained home/checkout EX -> official checkout/marker -> source/PM.
        # The official lock is held before the first source movement, not just during PM.
        lock, fd = official(fixture.root, candidate, expectations[2])
        joint.prepare(value, plan, *expectations, official_fd=fd)
        joint.move(value, plan, *expectations, official_fd=fd)
        generation_sha = joint.sync(value, plan, *expectations, official_fd=fd,
                                   environment=pm_cli.environment(fixture.root),
                                   command_prefix=pm_cli.sandbox(fixture.root, network=True),
                                   observe_prefix=pm_cli.sandbox(fixture.root))
        result = joint.verify(value, plan, *expectations, official_fd=fd,
                              generation_sha=generation_sha, observe_prefix=pm_cli.sandbox(fixture.root))
        history = joint.source.slots(value)
    finally:
        if lock is not None:
            lock.release()
        value.close()
    fixture.blocked()
    recheck_child(fixture, plan, expectations, generation_sha)
    deaths, refusals = 0, 0
    if faults:
        recheck_child(fixture, plan, expectations, generation_sha, hold=True)
        deaths += 1
        recheck_child(fixture, plan, expectations, generation_sha)
        value = fixture.held()
        lock = None
        try:
            lock, fd = official(fixture.root, fixture.root / "checkout", expectations[2])
            try:
                joint.verify(value, plan, *expectations, official_fd=fd,
                             generation_sha="0" * 64, observe_prefix=pm_cli.sandbox(fixture.root))
            except ValueError:
                refusals += 1
            else:
                raise ValueError("wrong independent generation expectation accepted")
            facts = next((fixture.root / "data/installs").glob("*/facts.json"))
            before, stamp = facts.read_bytes(), facts.stat()
            try:
                facts.write_bytes(before + b"\n")
                os.utime(facts, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                try:
                    joint.verify(value, plan, *expectations, official_fd=fd,
                                 generation_sha=generation_sha, observe_prefix=pm_cli.sandbox(fixture.root))
                except ValueError:
                    refusals += 1
                else:
                    raise ValueError("changed actual PM facts accepted")
            finally:
                # Restore our fixture's bytes only. ctime still changes: the original checkpoint
                # correctly remains stale, rather than treating a byte restore as authority.
                facts.write_bytes(before)
                os.utime(facts, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            if joint.source.slots(value) != history:
                raise ValueError("diagnostic recheck changed joint history")
        finally:
            if lock is not None:
                lock.release()
            value.close()
        fixture.blocked()
    result.update(fixedGitSourceSwitchObserved=True, realPMExecuted=True, officialCheckoutLockUsed=True,
                  newProcessJointRecheckObserved=True, jointRecheckCoordinatorSigkillCases=deaths,
                  jointExpectationRefusals=refusals, ordinaryWriterBlocked=True,
                  updaterExecuted=False, fullStartupVerified=False)
    return result


def main():
    parser = source_cli.cli.PlanArguments(description=__doc__)
    parser.add_argument("--old-root", required=True, type=Path)
    parser.add_argument("--candidate-root", required=True, type=Path)
    parser.add_argument("--provision", action="store_true")
    parser.add_argument("--fault-matrix", action="store_true")
    try:
        args = parser.parse_args()
        if not args.provision:
            raise ValueError("explicit isolated PM execution opt-in required")
        result = provision(args.old_root.resolve(), args.candidate_root.resolve(), args.fault_matrix)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, joint.source.worker.Refused, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "source-pm-preparation-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
