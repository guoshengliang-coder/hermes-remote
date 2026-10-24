#!/usr/bin/env python3
"""Opt-in same-run source → PM worker → native local build custody; never a completed upgrade."""
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


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


driver = load("chain_cli_driver", HERE / "hermes-maintenance-source-pm.py")
chain = load("chain_cli_policy", driver.pm_cli.PROTOTYPE / "pm_chain_custody.py")
native = load("chain_cli_heartbeat", HERE / "hermes-maintenance-pm-native.py")
worker_probe = load("chain_cli_checkout_probe", HERE / "hermes-maintenance-pm-worker.py")


def slots(domains):
    result = []
    for domain in domains:
        fd = domain.intent()
        try:
            data = os.read(fd, 16_385)
            if len(data) > 16_384:
                raise ValueError("bounded chain history required")
            result.append(data)
        finally:
            os.close(fd)
    return result


def inspect(old, candidate, mode):
    if sys.platform != "darwin" or mode not in ("worker-only", "full-chain"):
        raise ValueError("explicit isolated macOS chain mode required")
    if any(p.is_relative_to(Path.home() / ".hermes") for p in (old, candidate)):
        raise ValueError("live installation refused")
    fixture = driver.source_cli.Fixture(old, candidate)
    root = fixture.root
    for name, content in ((".hg199-isolated-source-pm", driver.joint.MARKER),
                          (".hg199-isolated-pm-chain", chain.MARKER)):
        path = root / name
        path.write_bytes(content)
        path.chmod(0o600)
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
    read_worker, write_worker = os.pipe()
    actor, held = None, None
    try:
        profile = driver.pm_cli.sandbox(root, network=True)
        original = Path(profile[-1]).read_bytes()
        if original.count(b"(deny signal)\n") != 1:
            raise ValueError("reviewed owned-child signal profile required")
        own_signal_profile = driver.pm_cli.profile_file(root, "chain-owned-children.sb", original.replace(b"(deny signal)\n", b""))
        logfd = os.open(root / "pm-chain.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(logfd, "wb") as log:
            actor = subprocess.Popen([*profile[:-1], str(own_signal_profile), sys.executable, "-I", "-S", "-B",
                str(driver.pm_cli.PROTOTYPE / "pm_chain_actor.py"), str(root), str(candidate), str(manifest),
                str(write_events), str(read_worker), *expected, mode],
                env=driver.pm_cli.environment(root), cwd=root, stdin=subprocess.PIPE,
                stdout=log, stderr=log, pass_fds=(manifest, write_events, read_worker))
        for fd in (manifest, write_events, read_worker):
            os.close(fd)
        manifest = write_events = read_worker = None
        prepared = driver.joint.pm.support.read(read_events, timeout=900)
        if (type(prepared) is not dict or set(prepared) != {"chainPrepared", "buildPlanSHA", "historySHA"}
                or prepared["chainPrepared"] is not True
                or any(type(prepared[k]) is not str or re.fullmatch("[0-9a-f]{64}", prepared[k]) is None
                       for k in ("buildPlanSHA", "historySHA"))):
            raise ValueError("bounded chain preparation event required")
        previous = driver.joint.planned(primary)
        row = {"schema": 1, "kind": "pm-build-audit-started", "previous": driver.joint.source.sha(previous),
               "jointPlanSHA": expected[3], "buildPlanSHA": prepared["buildPlanSHA"]}
        history = previous + driver.joint.binding.encode(row) + b"\n"
        if driver.joint.source.sha(history) != prepared["historySHA"] or slots(fixture.domains) != [history] * 2:
            raise ValueError("original same-run history differs")
        deadline = time.monotonic() + 90
        while not (root / "backend-status.json").exists():
            if actor.poll() is not None or time.monotonic() >= deadline:
                raise ValueError("actual PM worker/native backend did not start")
            time.sleep(.03)
        before = native.status(root)
        if before["descriptorsMatch"] is not (mode == "full-chain"):
            raise ValueError("native backend custody observation differs")
        os.write(write_worker, b'{"killOwnedUV":true}\n')
        if driver.joint.pm.support.read(read_events, timeout=15) != {"nativeUVKilled": True, "workerBusinessStopped": True}:
            raise ValueError("native UV stop and worker cleanup not confirmed")
        actor.stdin.write(b'{"killOwnedWorkerAndClose":true}\n')
        actor.stdin.flush()
        if driver.joint.pm.support.read(read_events, timeout=15) != {
                "workerKilled": True, "callerBusinessStopped": True, "callerCustodyClosed": True}:
            raise ValueError("worker stop and caller cleanup not confirmed")
        actor.kill()  # Exact caller Popen; it already reaped its worker, which reaped its UV.
        if actor.wait(timeout=5) != -signal.SIGKILL:
            raise ValueError("caller death missing")
        native.fresh_heartbeat(root, before)  # New heartbeat after ALL THREE owning parents ended.
        held = worker_probe.attempt_lease(root)
        if mode == "full-chain":
            if held is not None or worker_probe.checkout_available(root):
                raise ValueError("backend did not retain maintenance and official custody")
        elif held is None or not worker_probe.checkout_available(root):
            raise ValueError("worker-only negative control did not expose the native gap")
        if held is not None:
            held.close()
            held = None
        if slots(fixture.domains) != [history] * 2:
            raise ValueError("in-flight history was changed")
        (root / "release-backend").touch(mode=0o600, exist_ok=False)
        deadline = time.monotonic() + 10
        while held is None:
            held = worker_probe.attempt_lease(root)
            if held is None:
                if time.monotonic() >= deadline:
                    raise ValueError("released backend retained custody")
                time.sleep(.03)
        fd = os.open(root / "checkout/.git/hermes-update.lock", os.O_RDWR | os.O_NOFOLLOW)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            driver.joint.validate(held, primary, *expected, fd, "after")
            # The default sync/recovery policy must reject this distinct audit history BEFORE
            # probing a runtime. There is no completed sync/generation checkpoint to reuse.
            original_observer = driver.joint.pm._observe_root
            observed = []

            def forbidden_probe(*args, **kwargs):
                observed.append(True)
                raise RuntimeError("unknown build must not run a runtime observer")

            driver.joint.pm._observe_root = forbidden_probe
            rejected = False
            try:
                driver.joint.verify(held, primary, *expected, official_fd=fd,
                                    generation_sha="0" * 64, observe_prefix=())
            except ValueError:
                rejected = True
            finally:
                driver.joint.pm._observe_root = original_observer
            if not rejected or observed:
                raise ValueError("build audit was mistaken for an ordinary sync")
        finally:
            os.close(fd)
        if slots(fixture.domains) != [history] * 2:
            raise ValueError("unknown audit history was rewritten")
        held.close()
        held = None
        fixture.blocked()
        return {"ok": True, "fixture": str(root), "mode": mode, "sourceTransition": True,
                "actualPMWorkerAndNativeBuildExecuted": True, "sameRunBackendSurvivedThreeParents": True,
                "backendInheritedMaintenanceAndOfficialCustody": mode == "full-chain",
                "nativeUVKilledCases": 1, "workerKilledCases": 1, "callerSigkillCases": 1,
                "unknownBuildHistoryStillBlocks": True, "syncVenvExecuted": False,
                "sourceBefore": driver.source_cli.OLD, "sourceCommit": driver.source_cli.NEW,
                "bootstrapDeathVerified": False, "allPMDescendantsCovered": False,
                "updaterRecoveryVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}
    finally:
        (root / "release-backend").touch(mode=0o600, exist_ok=True)
        if held is not None:
            held.close()
        for fd in (manifest, read_events, write_events, read_worker, write_worker):
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
    parser.add_argument("--old-root", type=Path, required=True)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--mode", choices=("worker-only", "full-chain"), required=True)
    args = parser.parse_args()
    if not args.provision:
        raise ValueError("explicit same-run build-audit opt-in required")
    return inspect(args.old_root.resolve(), args.candidate_root.resolve(), args.mode)


if __name__ == "__main__":
    try:
        result = main()
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, subprocess.SubprocessError,
            chain.joint.source.worker.Refused, driver.joint.source.worker.Refused,
            driver.source_cli.source.worker.Refused):
        result = {"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                  "summaryChinese": "无法验证同一事务的 PM 子进程维护条件。",
                  "summaryEnglish": "Same-run PM descendant maintenance could not be verified."}
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["ok"] else 1)
