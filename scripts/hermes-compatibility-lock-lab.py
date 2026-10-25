#!/usr/bin/env python3
"""Probe official old/new updater exclusion using synthetic paths and real sibling processes.

No installer is executed and no existing Hermes home is written. This is evidence,
not a production lock adapter or a compatibility approval.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time

SOURCE = "633dda6d7fdcac7e8340471c9f5ed08cdccbd70c"
TARGET = "bf0203a794a07aac3ff764972ab9087baa4e4f4e"


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def worker(mode, root, source, target):
    sys.path.insert(0, str(source))
    if mode == "sleeper":
        time.sleep(60)
        return
    upstream = module((source if mode == "old" else target) / "hermes_cli/update_lock.py", "audited_update_lock")
    repo = root / "user/.hermes/hermes-agent"
    options = {"path": root / "user/.hermes/.hermes-update-in-progress"}
    if mode != "old":
        options["install_root"] = repo
    lock = upstream.UpdateLock(**options)
    acquired = lock.acquire()
    try:
        if mode == "owner" and acquired:
            child = subprocess.Popen([sys.executable, __file__, "--worker", "sleeper", str(root), str(source), str(target)],
                                     pass_fds=upstream.checkout_lock_fds(repo), stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(json.dumps({"acquired": True, "childPID": child.pid}), flush=True)
            time.sleep(60)
        else:
            print(json.dumps({"acquired": acquired}), flush=True)
    finally:
        lock.release()


def verify_tree(path, expected):
    path = path.resolve()
    # Existing daily installations are never execution inputs to this rehearsal.
    if path == Path.home() or Path.home() in path.parents:
        raise ValueError("daily_home_forbidden")
    env = {"PATH": "/usr/bin:/bin", "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1", "GIT_NO_LAZY_FETCH": "1"}
    def git(*args):
        return subprocess.check_output(["/usr/bin/git", "--no-replace-objects", "-C", str(path), *args], env=env, stderr=subprocess.DEVNULL)
    if git("rev-parse", "HEAD").decode().strip() != expected:
        raise ValueError("commit_mismatch")
    data = (path / "hermes_cli/update_lock.py").read_bytes()
    if data != git("show", expected + ":hermes_cli/update_lock.py"):
        raise ValueError("source_mismatch")
    return path, hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    args = parser.parse_args()
    source, old_hash = verify_tree(args.source, SOURCE)
    target, new_hash = verify_tree(args.target, TARGET)
    root = Path(tempfile.mkdtemp(prefix="hermes-compat-lock-lab-")).resolve()
    root.chmod(0o700)
    (root / "user/.hermes/hermes-agent/.git").mkdir(parents=True, mode=0o700)
    (root / "tmp").mkdir(mode=0o700)
    lab = module(Path(__file__).with_name("hermes-compatibility-lab.py"), "hermes_install_lab")
    profile = root / "isolation.sb"
    profile.write_text(lab.sandbox_profile(root, Path.home()))
    env = lab.isolated_environment(root)
    python = source / "venv/bin/python3"
    def command(mode):
        return ["/usr/bin/sandbox-exec", "-f", str(profile), str(python), str(Path(__file__).resolve()), "--worker", mode, str(root), str(source), str(target)]
    def contender(mode):
        raw = subprocess.check_output(command(mode), env=env, cwd=root, stderr=subprocess.DEVNULL, timeout=15)
        return json.loads(raw)["acquired"]
    owner = subprocess.Popen(command("owner"), env=env, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    report = {"schemaVersion": 1, "sourceCommit": SOURCE, "targetCommit": TARGET,
              "sourceLockSha256": old_hash, "targetLockSha256": new_hash,
              "suiteSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "compatibilityApproved": False, "checks": {}}
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(owner.stdout, selectors.EVENT_READ)
            if not selector.select(15):
                raise ValueError("owner_timeout")
        ready = json.loads(owner.stdout.readline())
        if not ready["acquired"]:
            raise ValueError("owner_refused")
        report["checks"]["oldRefusesLiveOwner"] = not contender("old")
        report["checks"]["newRefusesLiveOwner"] = not contender("new")
        owner.kill()
        owner.wait(timeout=5)
        os.kill(ready["childPID"], 0)
        report["checks"]["childSurvivesOwnerInterruption"] = True
        report["checks"]["newRefusesSurvivingChild"] = not contender("new")
        report["checks"]["oldRefusesSurvivingChild"] = not contender("old")
    finally:
        try:
            os.killpg(owner.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        owner.wait(timeout=5)
    # The inherited descriptor closes on child exit; bounded retry accounts for process reaping.
    report["checks"]["newAcquiresAfterTreeExit"] = False
    for _ in range(20):
        if contender("new"):
            report["checks"]["newAcquiresAfterTreeExit"] = True
            break
        time.sleep(0.1)
    report["finishedAt"] = int(time.time())
    report["status"] = "passed" if all(report["checks"].values()) else "compatibility_gap"
    output = root / "report.json"
    output.write_text(json.dumps(report, indent=2) + "\n")
    output.chmod(0o600)
    print(json.dumps({"report": str(output), "status": report["status"]}))


if __name__ == "__main__":
    if len(sys.argv) == 6 and sys.argv[1] == "--worker":
        worker(sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5]))
    else:
        try:
            main()
        except Exception:
            print(json.dumps({"code": "HR-HERMESCOMPAT-004", "summaryZh": "更新互斥验证未完成，请查看测试记录。", "summaryEn": "Update exclusion verification is incomplete. Review the test record."}))
            sys.exit(1)
