#!/usr/bin/env python3
"""Opt-in actual PM spawn boundary observation, not a PM completion/recovery test."""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("pm_worker_observe_driver", HERE / "hermes-maintenance-source-pm.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
joint = driver.joint


def attempt_lease(root):
    try:
        return driver.held(root)
    except BlockingIOError:
        return None


def checkout_available(root):
    fd = os.open(root / "checkout/.git/hermes-update.lock", os.O_RDWR | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return False
        return True
    finally:
        os.close(fd)


def inspect(old, candidate, mode):
    if sys.platform != "darwin" or mode not in ("raw", "proposed"):
        raise ValueError("explicit macOS isolated PM spawn mode required")
    if any(p.is_relative_to(Path.home() / ".hermes") for p in (old, candidate)):
        raise ValueError("live installation refused")
    fixture = driver.source_cli.Fixture(old, candidate)
    root = fixture.root
    marker = root / ".hg199-isolated-source-pm"
    marker.write_bytes(joint.MARKER)
    marker.chmod(0o600)
    locked = json.loads((candidate / "pm/lock.json").read_bytes())["packages"]["python"]["version"]
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)\+[A-Za-z0-9._-]+", locked)
    if not match:
        raise ValueError("unknown fixed Python target")
    plan = {"schema": 1, "policy": joint.POLICY, "source": fixture.plan,
            "pmInputs": joint.binding.snapshot(candidate, list(joint.PM_INPUTS)),
            "pythonVersion": list(map(int, match.groups()))}
    expectations = (*fixture.args[:3], joint.binding.digest(plan))
    manifest = joint.binding.private_context_fd(root / "control", plan)
    read_event, write_event = os.pipe()
    read_gate, write_gate = os.pipe()
    actor, value = None, None
    try:
        log_fd = os.open(root / "pm-worker-probe.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(log_fd, "wb") as log:
            actor = subprocess.Popen([*driver.pm_cli.sandbox(root, network=True), sys.executable, "-I", "-S", "-B",
                                      str(driver.pm_cli.PROTOTYPE / "pm_real_worker_actor.py"), str(root), str(candidate),
                                      str(manifest), str(write_event), str(read_gate), *expectations, mode],
                                     env=driver.pm_cli.environment(root), cwd=root, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=log, pass_fds=(manifest, write_event, read_gate))
        for fd in (manifest, write_event, read_gate):
            os.close(fd)
        manifest = write_event = read_gate = None
        ready = joint.pm.support.read(read_event, timeout=900)
        if (type(ready) is not dict or set(ready) != {"realWorkerSpawnGated", "descriptorsMatch", "runtimeVersion", "pid"}
                or ready["realWorkerSpawnGated"] is not True or ready["descriptorsMatch"] is not (mode == "proposed")
                or type(ready["pid"]) is not int or ready["pid"] <= 0):
            raise ValueError("actual selected PM interpreter did not reach gated spawn")
        actor.kill()  # Exact self-created Popen; runtime bootstrap children have finished.
        if actor.wait(timeout=5) != -signal.SIGKILL:
            raise ValueError("last-custodian death not observed")
        value = attempt_lease(root)
        if mode == "proposed":
            if value is not None or checkout_available(root):
                raise ValueError("gated PM worker did not retain both custody domains")
        elif value is None or not checkout_available(root):
            raise ValueError("raw-boundary negative control did not expose its custody gap")
        if value is not None:
            value.close()
            value = None
        os.write(write_gate, b"exit-without-worker\n")
        os.close(write_gate)
        write_gate = None
        deadline = time.monotonic() + 10
        while value is None:
            value = attempt_lease(root)
            if value is None:
                if time.monotonic() >= deadline:
                    raise ValueError("gated worker did not release custody")
                time.sleep(.02)
        if not checkout_available(root):
            raise ValueError("official checkout still held after gate exit")
        history = joint.source.slots(value)
        official = os.open(root / "checkout/.git/hermes-update.lock", os.O_RDWR | os.O_NOFOLLOW)
        original_observer = joint.pm._observe_root
        observed = []
        try:
            fcntl.flock(official, fcntl.LOCK_EX | fcntl.LOCK_NB)
            def observe(*args):
                observed.append(True)
                return original_observer(*args)
            joint.pm._observe_root = observe
            try:
                joint.verify(value, plan, *expectations, official_fd=official, generation_sha="0" * 64, observe_prefix=())
            except ValueError:
                pass
            else:
                raise ValueError("unknown in-flight synchronization became prepared")
            if observed or joint.source.slots(value) != history:
                raise ValueError("unknown sync recheck executed runtime or changed history")
        finally:
            joint.pm._observe_root = original_observer
            os.close(official)
        value.close()
        value = None
        fixture.blocked()
        return {"actualPMBootstrapExecuted": True, "selectedPMRuntimeVersion": ready["runtimeVersion"],
                "gatedRealPMWorkerSpawnObserved": True, "mode": mode, "workerInheritedCustody": mode == "proposed",
                "rawCustodyGapObserved": mode == "raw", "parentSigkillCases": 1,
                "unknownStartedHistoryRefusedBeforeRuntime": True, "ordinaryWriterBlocked": True,
                "realPMWorkerOperationExecuted": False, "allPMDescendantsCovered": False,
                "pmInFlightRecoveryVerified": False, "updaterRecoveryVerified": False,
                "productionCompletionVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}
    finally:
        if value is not None:
            value.close()
        for fd in (manifest, read_event, write_event, read_gate, write_gate):
            if fd is not None:
                os.close(fd)
        if actor is not None:
            if actor.poll() is None:
                actor.kill()
            actor.wait(timeout=5)


def main():
    parser = driver.source_cli.cli.PlanArguments(description=__doc__)
    parser.add_argument("--old-root", required=True, type=Path)
    parser.add_argument("--candidate-root", required=True, type=Path)
    parser.add_argument("--provision", action="store_true")
    parser.add_argument("--mode", required=True, choices=("raw", "proposed"))
    try:
        args = parser.parse_args()
        if not args.provision:
            raise ValueError("explicit isolated PM bootstrap opt-in required")
        result = inspect(args.old_root.resolve(), args.candidate_root.resolve(), args.mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, joint.source.worker.Refused, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "pm-worker-custody-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
