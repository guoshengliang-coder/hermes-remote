"""Actual pinned PM caller; instrument one spawn while retaining all original target bytes."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


HERE = Path(__file__).parent
driver = load("real_worker_joint_driver", HERE.parent / "hermes-maintenance-source-pm.py")
custody = load("real_worker_custody", HERE / "pm_worker_custody.py")
joint = driver.joint


def run(root, candidate, manifest, events, gate, expectations, mode):
    if mode not in ("raw", "proposed"):
        raise ValueError("unknown custody observation mode")
    plan = joint.binding.read_context(manifest)
    os.close(manifest)
    held = driver.held(root)
    lock = None
    raw = subprocess.Popen
    try:
        lock, official = driver.official(root, candidate, expectations[2])
        joint.prepare(held, plan, *expectations, official_fd=official)
        joint.move(held, plan, *expectations, official_fd=official)
        joint.append(held, joint.planned(plan), joint.event(joint.planned(plan), "pm-sync-started", expectations[3]))
        fds = (*held.pass_fds, official)
        identities = [[fd, [os.fstat(fd).st_dev, os.fstat(fd).st_ino]] for fd in fds]
        captured = False

        def gate_spawn(argv, **kwargs):
            command = [argv[0], "-I", "-B", str(HERE / "pm_worker_gate.py"), str(root),
                       str(events), str(gate), json.dumps(identities)]
            return raw(command, **{k: v for k, v in kwargs.items() if k != "pass_fds"},
                       pass_fds=(*kwargs.get("pass_fds", ()), events, gate))

        def capture(argv, *args, **kwargs):
            nonlocal captured
            if (not captured and type(argv) is list and len(argv) == 4
                    and argv[1:3] == ["-I", "-B"] and argv[3] == str(root / "checkout/pm/worker.py")):
                if args:
                    raise ValueError("unreviewed positional PM options")
                captured = True
                # Preserve the real _request's command/env/stdin/stdout. Only replace its
                # script with our no-mutation gate, and add the private observation channels.
                if mode == "proposed":
                    return custody.spawn(held, plan, *expectations, official_fd=official, argv=argv, options=kwargs)
                return gate_spawn(argv, **kwargs)
            return raw(argv, *args, **kwargs)

        custody.POPEN = gate_spawn
        subprocess.Popen = capture
        sys.path.insert(0, str(root / "checkout"))
        from pm.client import sync_venv
        sync_venv([], explicit=True)
        raise ValueError("gated PM operation must not report completion")
    finally:
        subprocess.Popen = raw
        if lock is not None:
            lock.release()
        held.close()


if __name__ == "__main__":
    try:
        root, candidate, manifest, events, gate, run_id, before, after, digest, mode = sys.argv[1:]
        run(Path(root), Path(candidate), int(manifest), int(events), int(gate),
            (run_id, before, after, digest), mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, joint.source.worker.Refused):
        raise SystemExit(1)
