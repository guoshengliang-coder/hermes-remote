"""Synthetic PM-worker custody parent for kernel tests. Never imports upstream PM."""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

spec = importlib.util.spec_from_file_location("worker_parent_custody", Path(__file__).with_name("pm_worker_custody.py"))
custody = importlib.util.module_from_spec(spec)
spec.loader.exec_module(custody)
joint = custody.joint
lease = joint.source.load("maintenance_lease")


def run(root, manifest, gate, expectations, mode):
    plan = joint.binding.read_context(manifest)
    os.close(manifest)
    held = lease.Lease([lease.Domain(root / "control", kind, root / name)
                        for kind, name in (("home", "data"), ("checkout", "checkout"))], maintenance=True)
    official, child = None, None
    try:
        if not held.drain() or mode not in ("raw", "proposed"):
            raise ValueError("worker probe scope")
        official = os.open(root / "checkout/.git/hermes-update.lock", os.O_RDWR | os.O_NOFOLLOW)
        fcntl.flock(official, fcntl.LOCK_EX | fcntl.LOCK_NB)
        joint.validate(held, plan, *expectations, official, "after")
        joint.require_slots(held, joint.event(joint.planned(plan), "pm-sync-started", expectations[3]))
        fds = (*held.pass_fds, official)
        identities = [[fd, [os.fstat(fd).st_dev, os.fstat(fd).st_ino]] for fd in fds]
        env = {"HOME": str(root / "user"), "HERMES_HOME": str(root / "data"), "PATH": os.defpath,
               "TMPDIR": str(root / "tmp"), "HG199_TEST_DESCRIPTOR_IDENTITIES": json.dumps(identities)}
        argv = [str(root / "data/tools/worker-host/bin/python"), "-I", "-B", str(root / "checkout/pm/worker.py")]
        options = {"stdin": gate, "stdout": subprocess.PIPE, "stderr": subprocess.DEVNULL,
                   "env": env, "cwd": str(root)}
        if mode == "proposed":
            child = custody.spawn(held, plan, *expectations, official_fd=official, argv=argv, options=options)
        else:
            child = custody.POPEN(argv, **options)  # Existing default close_fds boundary, no pass_fds.
        os.close(gate)
        ready = joint.pm.support.read(child.stdout.fileno(), timeout=10)
        if (type(ready) is not dict or set(ready) != {"workerReady", "descriptorsMatch", "pid"}
                or ready["workerReady"] is not True or ready["pid"] != child.pid
                or ready["descriptorsMatch"] is not (mode == "proposed")):
            raise ValueError("synthetic worker not ready")
        print(json.dumps(ready), flush=True)
        command = joint.pm.support.read(sys.stdin.fileno(), timeout=15)
        if command != {"closeParentCustody": True}:
            raise ValueError("unknown parent probe command")
        held.close()
        os.close(official)
        official = None
        print(json.dumps({"parentCustodyClosed": True}), flush=True)
        if child.wait(timeout=12) != 0:
            raise ValueError("worker did not exit")
    finally:
        if child is not None:
            if child.poll() is None:
                child.kill()  # Only this test-owned Popen on an ordinary error/cleanup path.
            child.wait(timeout=5)
            child.stdout.close()
        if official is not None:
            os.close(official)
        held.close()


if __name__ == "__main__":
    try:
        root, manifest, gate, run_id, before, after, digest, mode = sys.argv[1:]
        run(Path(root), int(manifest), int(gate), (run_id, before, after, digest), mode)
    except (ValueError, TypeError, OSError, RuntimeError, joint.source.worker.Refused):
        print(json.dumps({"workerCustodyProbeRefused": True}), flush=True)
        raise SystemExit(1)
