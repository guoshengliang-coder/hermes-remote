"""Test-owned inspection coordinator. Its checkpoints never authorize completion."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

spec = importlib.util.spec_from_file_location("inspection_target_cli", Path(__file__).parents[1] / "hermes-maintenance-target.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)
support = cli.driver.load("inspection_frames", Path(__file__).with_name("handoff_actor.py"))
PHASES = {"validated": 0, "observed": 1, "verified": 2}


def run(root, manifest, expected_run, expected_source, expected_sha, phase, *, inspect=cli.inspect):
    try:
        if phase not in (*PHASES, "none"):
            raise ValueError("inspection checkpoint changed")
        plan = cli.target.binding.read_context(manifest)
    finally:
        os.close(manifest)

    def checkpoint(name, observations):
        if name == phase:
            print(json.dumps({"checkpoint": name, "observations": observations}), flush=True)
            if support.read(sys.stdin.fileno(), timeout=30) != {"command": "continue"}:
                raise ValueError("inspection checkpoint command")
    result = inspect(root, plan, expected_run, expected_source, expected_sha, checkpoint=checkpoint)
    print(json.dumps({"result": result}), flush=True)


if __name__ == "__main__":
    try:
        root, manifest, expected_run, expected_source, expected_sha, phase = sys.argv[1:]
        run(Path(root), int(manifest), expected_run, expected_source, expected_sha, phase)
    except (ValueError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        # The public harness supplies the registered bilingual error, never a raw child exception.
        print(json.dumps({"inspectionRefused": True}), flush=True)
        raise SystemExit(1)
