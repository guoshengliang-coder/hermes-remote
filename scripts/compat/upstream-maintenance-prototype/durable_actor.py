"""Synthetic publisher held at a selected persistence boundary; no Hermes imports."""
import json
import os
from pathlib import Path
import sys
import importlib.util

spec = importlib.util.spec_from_file_location("durable_support", Path(__file__).with_name("handoff_actor.py"))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
load, read = support.load, support.read

lease, intent, binding = load("maintenance_lease"), load("durable_intent"), load("bound_handoff")


def run(root, records, context_fd, run_id, source, home, entry, phase):
    held = lease.Lease([lease.Domain(*record) for record in records], maintenance=True)
    try:
        if not held.drain():
            raise ValueError("publisher busy")
        context = binding.read_context(context_fd)
        binding.validate(context, run_id, source, root / "checkout", Path(home), entry, records)

        def checkpoint(point, index):
            if (phase == "partial" and point == "slot-synced" and index == 0) or (phase == "complete" and point == "all-synced"):
                print(json.dumps({"checkpoint": phase}), flush=True)
                if read(sys.stdin.fileno()) != {"command": "close"}:
                    raise ValueError("publisher command")

        intent.begin(held, context, checkpoint)
    finally:
        held.close()
        os.close(context_fd)


if __name__ == "__main__":
    try:
        root = Path(sys.argv[1])
        if not root.name.startswith("hg199-durable-") or sys.argv[8] not in ("partial", "complete"):
            raise ValueError("publisher scope")
        run(root, json.loads(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7], sys.argv[8])
    except Exception:
        print(json.dumps({"failed": True}), flush=True)
        raise SystemExit(1) from None
