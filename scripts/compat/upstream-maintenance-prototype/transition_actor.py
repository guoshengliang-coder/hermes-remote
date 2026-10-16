"""Fixture coordinator paused at a generation-plan or terminal append boundary."""
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("transition_support", Path(__file__).with_name("handoff_actor.py"))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
lease, transition, binding = support.load("maintenance_lease"), support.load("generation_transition"), support.load("bound_handoff")


def run(records, manifest, operation, phase):
    held = lease.Lease([lease.Domain(*record) for record in records], maintenance=True)
    try:
        if not held.drain():
            raise ValueError("fixture coordinator busy")
        plan = binding.read_context(manifest)

        def checkpoint(point, index):
            if (phase == "partial" and point == "slot-synced" and index == 0) or (phase == "complete" and point == "all-synced"):
                print(json.dumps({"checkpoint": operation + "-" + phase}), flush=True)
                if support.read(sys.stdin.fileno()) != {"command": "close"}:
                    raise ValueError("fixture coordinator command")

        if operation == "plan":
            transition.prepare(held, plan, checkpoint)
        elif operation == "finish":
            witness = transition.check_fixture_prefix(held, plan)
            transition.finish_fixture(held, plan, witness, checkpoint)
        else:
            raise ValueError("fixture coordinator operation")
    finally:
        held.close()
        os.close(manifest)


if __name__ == "__main__":
    try:
        if sys.argv[4] not in ("partial", "complete"):
            raise ValueError("fixture coordinator phase")
        run(json.loads(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4])
    except Exception:
        print(json.dumps({"failed": True}), flush=True)
        raise SystemExit(1) from None
