#!/usr/bin/env python3
"""Opt-in native uv → local PEP 517 custody experiment. Never installs an upgrade."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


driver = load("native_probe_driver", HERE / "hermes-maintenance-source-pm.py")
native = load("native_probe_scope", driver.pm_cli.PROTOTYPE / "pm_native_descendant.py")


def held(root):
    return driver.held(root)


def available(root):
    try:
        value = held(root)
        value.close()
        return True
    except BlockingIOError:
        return False


def status(root):
    path = root / "backend-status.json"
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
                or not 0 < info.st_size <= 512):
            raise ValueError("private bounded backend status required")
        value = json.loads(os.read(fd, 513))
    finally:
        os.close(fd)
    if (type(value) is not dict or set(value) != {"backendAlive", "descriptorsMatch", "pid", "sequence"}
            or value["backendAlive"] is not True or type(value["descriptorsMatch"]) is not bool
            or type(value["pid"]) is not int or value["pid"] <= 0
            or type(value["sequence"]) is not int or not 0 <= value["sequence"] < 400):
        raise ValueError("bounded backend status required")
    return value


def fresh_heartbeat(root, before):
    # Call only after both parents have been reaped. The pre-interruption sample is not a
    # freshness baseline: accepting it could mistake a dead backend's old status for liveness.
    baseline = status(root)
    deadline = time.monotonic() + 2
    while True:
        after = status(root)
        if after["pid"] != before["pid"] or after["descriptorsMatch"] != before["descriptorsMatch"]:
            raise ValueError("backend identity changed")
        if after["sequence"] > baseline["sequence"]:
            return after
        if time.monotonic() >= deadline:
            raise ValueError("backend did not remain alive after both parents exited")
        time.sleep(.03)


def inspect(source, prepared, mode):
    if sys.platform != "darwin" or mode not in ("raw", "proposed"):
        raise ValueError("explicit macOS native probe mode required")
    if source.is_relative_to(Path.home() / ".hermes"):
        raise ValueError("live source installation refused")
    driver.joint.source.worker.verify_source(source, driver.source_cli.NEW)
    # Only reuse read-only tools from a marked disposable preparation, never a user install.
    driver.joint.binding.directory(prepared)
    if (not prepared.name.startswith("hg199-source-transition-") or prepared.stat().st_mode & 0o077
            or (prepared / ".hg199-isolated-source-pm").is_symlink()
            or (prepared / ".hg199-isolated-source-pm").read_bytes() != driver.joint.MARKER):
        raise ValueError("marked prepared tool root required")
    uv = prepared / "data/tools/uv-0.12.3-darwin-arm64/uv"
    python = prepared / "data/tools/python-3.14.7+20260901-darwin-arm64/bin/python3.14"
    context = {"source": str(source), "uv": str(uv), "python": str(python),
               "uvSHA": driver.joint.pm.file(uv, [0, 0])["sha256"],
               "pythonSHA": driver.joint.pm.file(python, [0, 0])["sha256"]}
    root = Path(tempfile.mkdtemp(prefix="hg199-pm-descendant-")).resolve()
    for name in ("control", "data", "checkout", "user", "tmp", "cache"):
        (root / name).mkdir(mode=0o700)
    marker = root / ".hg199-native-descendant"
    marker.write_bytes(native.MARKER)
    marker.chmod(0o600)
    for kind, name in (("home", "data"), ("checkout", "checkout")):
        driver.lease.Domain(root / "control", kind, root / name).enroll()
    manifest = driver.joint.binding.private_context_fd(root / "control", context)
    actor = None
    try:
        logfd = os.open(root / "native-probe.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        profile = driver.pm_cli.sandbox(root, network=False)
        # Unlike the passive worker gate, this actor must interrupt its own native UV Popen.
        # Retain write/network/service-exec boundaries; there is no PID-input signalling API.
        original = Path(profile[-1]).read_bytes()
        if original.count(b"(deny signal)\n") != 1:
            raise ValueError("reviewed native signal profile required")
        signal_profile = driver.pm_cli.profile_file(root, "native-owned-child.sb", original.replace(b"(deny signal)\n", b""))
        with os.fdopen(logfd, "wb") as log:
            actor = subprocess.Popen([*profile[:-1], str(signal_profile), sys.executable, "-I", "-S", "-B",
                                      str(driver.pm_cli.PROTOTYPE / "pm_native_actor.py"), str(root), str(manifest), mode],
                                     env=driver.pm_cli.environment(root), cwd=root, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=log, pass_fds=(manifest,))
        os.close(manifest)
        manifest = None
        deadline = time.monotonic() + 60
        while not (root / "backend-status.json").exists():
            if actor.poll() is not None or time.monotonic() >= deadline:
                raise ValueError("actual PM/native backend did not start")
            time.sleep(.03)
        before = status(root)
        if before["descriptorsMatch"] is not (mode == "proposed"):
            raise ValueError("native backend descriptor observation disagrees")
        actor.stdin.write(b'{"killOwnedUVAndCloseCustody":true}\n')
        actor.stdin.flush()
        if driver.joint.pm.support.read(actor.stdout.fileno(), timeout=10) != {"nativeUVKilled": True, "parentCustodyClosed": True}:
            raise ValueError("owned native UV interruption missing")
        actor.kill()  # Exact test-created helper Popen; its UV Popen has already been reaped.
        if actor.wait(timeout=5) != -signal.SIGKILL:
            raise ValueError("helper death missing")
        fresh_heartbeat(root, before)
        if available(root) is (mode == "proposed"):
            raise ValueError("native descendant custody disagrees with kernel locks")
        (root / "release-backend").touch(mode=0o600, exist_ok=False)
        deadline = time.monotonic() + 5
        while not available(root):
            if time.monotonic() >= deadline:
                raise ValueError("released backend retained custody")
            time.sleep(.03)
        return {"ok": True, "mode": mode, "fixture": str(root), "fixedSource": driver.source_cli.NEW,
                "actualPMEnvironmentAPI": True, "nativeUVAndLocalBackendExecuted": True,
                "uvVersion": "0.12.3", "pythonVersion": [3, 14, 7],
                "backendSurvivedBothParents": True, "backendInheritedCustody": mode == "proposed",
                "nativeUVInterrupted": True, "helperSigkillCases": 1,
                "durableIntentPublished": False, "officialUpdateLockTested": False,
                "fullPMTreeCovered": False, "pmSyncExecuted": False, "certifiesUpgrade": False}
    finally:
        (root / "release-backend").touch(mode=0o600, exist_ok=True)
        if manifest is not None:
            os.close(manifest)
        if actor is not None:
            if actor.poll() is None:
                actor.kill()
            actor.wait(timeout=5)
            actor.stdin.close()
            actor.stdout.close()


def main():
    parser = driver.source_cli.cli.PlanArguments(description=__doc__)
    parser.add_argument("--provision", action="store_true")
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--prepared-root", type=Path, required=True)
    parser.add_argument("--mode", choices=("raw", "proposed"), required=True)
    args = parser.parse_args()
    if not args.provision:
        raise ValueError("explicit native fixture opt-in required")
    return inspect(args.source_root.resolve(), args.prepared_root.resolve(), args.mode)


if __name__ == "__main__":
    try:
        result = main()
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, SystemExit, subprocess.SubprocessError,
            driver.joint.source.worker.Refused):
        result = {"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                  "summaryChinese": "无法验证隔离的 PM 子进程维护条件。",
                  "summaryEnglish": "Isolated PM descendant maintenance could not be verified."}
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["ok"] else 1)
