#!/usr/bin/env python3
"""Opt-in actual prepare_runtime/local-build death probe, never complete PM bootstrap or an upgrade."""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


chain = load("bootstrap_cli_chain", HERE / "hermes-maintenance-pm-chain.py")
driver = chain.driver
policy = load("bootstrap_cli_policy", driver.pm_cli.PROTOTYPE / "pm_bootstrap_custody.py")


def prepare_available(root):
    policy.prepare_identity(root)
    fd = os.open(root / "runtime-audit/.prepare.lock", os.O_RDWR | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            return False
    finally:
        os.close(fd)


def copy_tools(prepared, root):
    driver.joint.binding.directory(prepared)
    marker = prepared / ".hg199-isolated-source-pm"
    if (not prepared.name.startswith("hg199-source-transition-") or prepared.stat().st_mode & 0o077
            or marker.is_symlink() or marker.read_bytes() != driver.joint.MARKER):
        raise ValueError("marked disposable tools required")
    tools = root / "data/tools"
    tools.mkdir(mode=0o700)
    for name in ("uv-0.12.3-darwin-arm64", "python-3.14.7+20260901-darwin-arm64"):
        source = prepared / "data/tools" / name
        if source.resolve(strict=True) != source:
            raise ValueError("canonical disposable tool directory required")
        # Copy the private interpreter directory as a whole, not just its Mach-O executable.
        # This is still not a dependency-closure or signed toolchain provenance certificate.
        count = 0
        for entry in source.rglob("*"):
            count += 1
            if count > 65_536 or not entry.resolve(strict=True).is_relative_to(source):
                raise ValueError("bounded internal tool tree required")
        shutil.copytree(source, tools / name, symlinks=True)


def inspect(old, candidate, prepared, mode):
    if sys.platform != "darwin" or mode not in ("raw", "proposed"):
        raise ValueError("explicit isolated macOS runtime mode required")
    if any(p.is_relative_to(Path.home() / ".hermes") for p in (old, candidate, prepared)):
        raise ValueError("live installation refused")
    fixture = driver.source_cli.Fixture(old, candidate)
    root = fixture.root
    for name, content in ((".hg199-isolated-source-pm", driver.joint.MARKER),
                          (".hg199-isolated-pm-bootstrap", policy.MARKER)):
        (root / name).write_bytes(content)
        (root / name).chmod(0o600)
    copy_tools(prepared, root)
    locked = json.loads((candidate / "pm/lock.json").read_bytes())["packages"]["python"]["version"]
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)\+[A-Za-z0-9._-]+", locked)
    if not match:
        raise ValueError("fixed Python input required")
    primary = {"schema": 1, "policy": driver.joint.POLICY, "source": fixture.plan,
               "pmInputs": driver.joint.binding.snapshot(candidate, list(driver.joint.PM_INPUTS)),
               "pythonVersion": list(map(int, match.groups()))}
    expected = (*fixture.args[:3], driver.joint.binding.digest(primary))
    manifest = driver.joint.binding.private_context_fd(root / "control", primary)
    read_events, write_events = os.pipe()
    actor = held = None
    try:
        profile = driver.pm_cli.sandbox(root, network=False)
        original = Path(profile[-1]).read_bytes()
        if original.count(b"(deny signal)\n") != 1:
            raise ValueError("reviewed owned-child signal profile required")
        signal_profile = driver.pm_cli.profile_file(root, "bootstrap-owned-child.sb", original.replace(b"(deny signal)\n", b""))
        logfd = os.open(root / "bootstrap-probe.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(logfd, "wb") as log:
            actor = subprocess.Popen([*profile[:-1], str(signal_profile), sys.executable, "-I", "-S", "-B",
                str(driver.pm_cli.PROTOTYPE / "pm_bootstrap_actor.py"), str(root), str(candidate),
                str(manifest), str(write_events), *expected, mode],
                env=driver.pm_cli.environment(root), cwd=root, stdin=subprocess.PIPE,
                stdout=log, stderr=log, pass_fds=(manifest, write_events))
        for fd in (manifest, write_events):
            os.close(fd)
        manifest = write_events = None
        event = driver.joint.pm.support.read(read_events, timeout=120)
        if (type(event) is not dict or set(event) != {"runtimePrepared", "runtimePlanSHA", "historySHA"}
                or event["runtimePrepared"] is not True
                or any(type(event[k]) is not str or re.fullmatch("[0-9a-f]{64}", event[k]) is None
                       for k in ("runtimePlanSHA", "historySHA"))):
            raise ValueError("bounded runtime preparation event required")
        previous = driver.joint.planned(primary)
        history = previous + driver.joint.binding.encode({"schema": 1, "kind": "pm-runtime-audit-started",
            "previous": driver.joint.source.sha(previous), "jointPlanSHA": expected[3],
            "runtimePlanSHA": event["runtimePlanSHA"]}) + b"\n"
        if driver.joint.source.sha(history) != event["historySHA"] or chain.slots(fixture.domains) != [history] * 2:
            raise ValueError("original runtime audit history differs")
        deadline = time.monotonic() + 60
        while not (root / "backend-status.json").exists():
            if actor.poll() is not None or time.monotonic() >= deadline:
                raise ValueError("actual runtime native backend did not start")
            time.sleep(.03)
        before = chain.native.status(root)
        if before["descriptorsMatch"] is not (mode == "proposed"):
            raise ValueError("runtime backend descriptor custody differs")
        actor.stdin.write(b'{"killOwnedUVAndClose":true}\n')
        actor.stdin.flush()
        if driver.joint.pm.support.read(read_events, timeout=15) != {
                "nativeUVKilled": True, "runtimeBusinessStopped": True, "callerCustodyClosed": True}:
            raise ValueError("runtime cleanup not confirmed")
        actor.kill()  # This exact Popen, after its UV was reaped and runtime cleanup stopped.
        if actor.wait(timeout=5) != -signal.SIGKILL:
            raise ValueError("runtime caller death missing")
        chain.native.fresh_heartbeat(root, before)
        held = chain.worker_probe.attempt_lease(root)
        available = held is not None
        if held is not None:
            held.close()
            held = None
        if (available is (mode == "proposed")
                or chain.worker_probe.checkout_available(root) is (mode == "proposed")
                or prepare_available(root) is (mode == "proposed")):
            raise ValueError("runtime descendant did not retain the expected three lock domains")
        (root / "release-backend").touch(mode=0o600, exist_ok=False)
        deadline = time.monotonic() + 10
        while held is None:
            held = chain.worker_probe.attempt_lease(root)
            if held is None:
                if time.monotonic() >= deadline:
                    raise ValueError("released runtime backend retained custody")
                time.sleep(.03)
        if not prepare_available(root) or not chain.worker_probe.checkout_available(root):
            raise ValueError("released runtime backend retained other custody")
        if chain.slots(fixture.domains) != [history] * 2 or (root / "runtime-audit/selected.json").exists():
            raise ValueError("unknown runtime history changed or a failed generation published")
        held.close()
        held = None
        fixture.blocked()
        return {"ok": True, "fixture": str(root), "mode": mode, "sourceTransition": True,
            "sourceBefore": driver.source_cli.OLD, "sourceCommit": driver.source_cli.NEW,
            "actualPrepareRuntimeAPI": True, "localProjectOverridesPMDependencies": True,
            "backendSurvivedUVAndCaller": True, "backendRetainedAllThreeCustodies": mode == "proposed",
            "runtimePublicationOccurred": False, "unknownRuntimeIntentBlocksWriters": True,
            "localRuntimePreparationDeathVerified": True, "wholeRuntimeBootstrapVerified": False,
            "allPMDescendantsCovered": False, "updaterRecoveryVerified": False,
            "globalAdmissionProven": False, "certifiesUpgrade": False}
    finally:
        (root / "release-backend").touch(mode=0o600, exist_ok=True)
        if held is not None:
            held.close()
        for fd in (manifest, read_events, write_events):
            if fd is not None:
                os.close(fd)
        if actor is not None:
            if actor.poll() is None:
                actor.kill()
            actor.wait(timeout=5)
            actor.stdin.close()


def main():
    parser = driver.source_cli.cli.PlanArguments(description=__doc__)
    parser.add_argument("--provision", action="store_true")
    for name in ("old", "candidate", "prepared"):
        parser.add_argument("--" + name + "-root", type=Path, required=True)
    parser.add_argument("--mode", choices=("raw", "proposed"), required=True)
    args = parser.parse_args()
    if not args.provision:
        raise ValueError("explicit runtime preparation audit opt-in required")
    return inspect(args.old_root.resolve(), args.candidate_root.resolve(), args.prepared_root.resolve(), args.mode)


if __name__ == "__main__":
    try:
        result = main()
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, subprocess.SubprocessError,
            policy.joint.source.worker.Refused, driver.joint.source.worker.Refused, driver.source_cli.source.worker.Refused):
        result = {"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
            "summaryChinese": "无法验证 PM 引导准备的维护条件。",
            "summaryEnglish": "PM runtime preparation maintenance could not be verified."}
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["ok"] else 1)
