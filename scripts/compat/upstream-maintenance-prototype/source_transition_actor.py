"""Test-owned source coordinator; never runs upstream Python or opens admission."""
import json
import os
from pathlib import Path
import sys

import importlib.util
spec = importlib.util.spec_from_file_location("source_actor_module", Path(__file__).with_name("source_transition.py"))
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)
lease, support = source.load("maintenance_lease"), source.load("handoff_actor")


def run(root, manifest, run_id, before, after, digest, action, phase):
    plan = source.binding.read_context(manifest)
    os.close(manifest)
    held = lease.Lease([lease.Domain(root / "control", kind, root / name)
                        for kind, name in (("home", "data"), ("checkout", "checkout"))], maintenance=True)
    try:
        if not held.drain() or action not in ("prepare", "move") or phase not in ("partial", "complete"):
            raise ValueError("source coordinator scope")

        def checkpoint(point, index):
            if phase == "partial" and point == "slot-synced" and index == 0:
                pause()

        def pause():
            print(json.dumps({"checkpoint": action + "-" + phase}), flush=True)
            support.read(sys.stdin.fileno(), timeout=30)
            raise ValueError("checkpoint coordinator cannot continue")
        args = (held, plan, run_id, before, after, digest)
        if action == "prepare":
            source.prepare(*args, checkpoint=checkpoint)
        elif phase == "complete":
            source.move(*args)
        else:
            raise ValueError("no mid-checkout recovery policy")
        pause()
    finally:
        held.close()


if __name__ == "__main__":
    try:
        root, manifest, run_id, before, after, digest, action, phase = sys.argv[1:]
        run(Path(root), int(manifest), run_id, before, after, digest, action, phase)
    except (ValueError, TypeError, OSError, RuntimeError, source.worker.Refused):
        print(json.dumps({"sourceCoordinatorRefused": True}), flush=True)
        raise SystemExit(1)
