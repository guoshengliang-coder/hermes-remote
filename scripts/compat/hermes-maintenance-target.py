#!/usr/bin/env python3
"""Opt-in prepared-target diagnostic; requires an explicit independent plan and digest."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("target_pm_driver", HERE / "hermes-maintenance-pm.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
target = driver.load("prepared_pm_target", driver.PROTOTYPE / "pm_target.py")


def read_plan(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or stat.S_IMODE(before.st_mode) != 0o600
                or before.st_uid != os.getuid() or before.st_nlink != 1
                or not 1 <= before.st_size <= target.binding.LIMIT):
            raise ValueError("private bounded plan required")
        data = os.read(fd, target.binding.LIMIT + 1)
        if (len(data) != before.st_size or target.pm.stamp(os.fstat(fd)) != target.pm.stamp(before)
                or target.pm.stamp(path.lstat()) != target.pm.stamp(before)):
            raise ValueError("plan changed during read")
        return target.binding.decode(data)
    finally:
        os.close(fd)


def inspect(root, plan, expected_run, expected_source, expected_sha):
    target.pm.fixture(root)
    if expected_source != driver.COMMIT:
        raise ValueError("source requires review")
    worker = driver.load("target_source_worker", driver.WORKER)
    worker.verify_source(root / "checkout", expected_source, installation=True)
    held = driver.held(root)
    try:
        result = target.verify(held, plan, expected_run, expected_source, expected_sha, driver.sandbox(root))
    finally:
        held.close()
    lease = driver.load("target_writer_probe", driver.PROTOTYPE / "maintenance_lease.py")
    writer = None
    try:
        domains = [lease.Domain(root / "control", kind, root / name)
                   for kind, name in (("home", "data"), ("checkout", "checkout"))]
        writer = lease.Lease(domains)
    except lease.Pending:
        result["ordinaryWriterBlocked"] = True
    else:
        raise ValueError("persistent admission blocking lost")
    finally:
        if writer:
            writer.close()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--plan-file", type=Path, required=True)
    parser.add_argument("--expected-run", required=True)
    parser.add_argument("--expected-source", required=True)
    parser.add_argument("--expected-plan-sha", required=True)
    args = parser.parse_args()
    try:
        result = inspect(args.fixture_root.resolve(), read_plan(args.plan_file), args.expected_run,
                         args.expected_source, args.expected_plan_sha)
    except (ValueError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "pm-target-plan-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
