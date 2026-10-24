"""New-process joint recheck; does not sync PM, finish maintenance or open admission."""
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("joint_actor_driver", Path(__file__).parents[1] / "hermes-maintenance-source-pm.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


def run(root, manifest, expectations, generation_sha, mode):
    plan = driver.joint.binding.read_context(manifest)
    os.close(manifest)
    if mode not in ("hold", "verify"):
        raise ValueError("unknown joint diagnostic action")
    value = driver.held(root)
    lock = None
    try:
        lock, fd = driver.official(root, root / "checkout", expectations[2])
        driver.joint.verify(value, plan, *expectations, official_fd=fd,
                            generation_sha=generation_sha, observe_prefix=driver.pm_cli.sandbox(root))
        print(json.dumps({"jointRecheck": True, "held": mode == "hold"}), flush=True)
        if mode == "hold":
            driver.joint.pm.support.read(sys.stdin.fileno(), timeout=30)
            raise ValueError("held diagnostic cannot continue")
    finally:
        if lock is not None:
            lock.release()
        value.close()


if __name__ == "__main__":
    try:
        root, manifest, run_id, before, after, digest, generation_sha, mode = sys.argv[1:]
        run(Path(root), int(manifest), (run_id, before, after, digest), generation_sha, mode)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, driver.joint.source.worker.Refused):
        print(json.dumps({"jointRecheck": False}), flush=True)
        raise SystemExit(1)
