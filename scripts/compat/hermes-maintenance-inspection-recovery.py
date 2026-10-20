#!/usr/bin/env python3
"""Isolated inspection-death/recheck rehearsal, not updater or durable-consent recovery."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import uuid

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("inspection_recovery_target", HERE / "hermes-maintenance-target.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)
actor = cli.driver.load("inspection_recovery_actor", cli.driver.PROTOTYPE / "pm_inspection_actor.py")


def launch(root, plan, expected_run, expected_source, expected_sha, phase):
    manifest = cli.target.binding.private_context_fd(root / "control", plan)
    try:
        return subprocess.Popen([sys.executable, "-I", "-S", "-B", str(cli.driver.PROTOTYPE / "pm_inspection_actor.py"),
                                 str(root), str(manifest), expected_run, expected_source, expected_sha, phase],
                                cwd=root, env=cli.driver.environment(root), stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(manifest,))
    finally:
        os.close(manifest)


def close(process):
    try:
        if process.poll() is None:
            process.kill()  # Only the Popen created by this rehearsal, never a discovered PID.
        process.wait(timeout=5)
    finally:
        process.stdin.close()
        process.stdout.close()


def slots(held):
    result = []
    for domain in held.domains:
        fd = domain.intent()
        try:
            result.append(os.pread(fd, cli.target.intent.LIMIT + 1, 0))
        finally:
            os.close(fd)
    return result


def closed(root, saved):
    # A fresh EX acquisition proves the dead inspector retained no kernel custody here.
    held = cli.driver.held(root)
    try:
        if slots(held) != saved:
            raise ValueError("inspection changed transaction intent")
    finally:
        held.close()
    lease = cli.driver.load("inspection_writer", cli.driver.PROTOTYPE / "maintenance_lease.py")
    writer = None
    try:
        writer = lease.Lease([lease.Domain(root / "control", kind, root / name)
                              for kind, name in (("home", "data"), ("checkout", "checkout"))])
    except lease.Pending:
        return
    finally:
        if writer:
            writer.close()
    raise ValueError("ordinary writer entered after inspection death")


def result(value, plan):
    keys = {"preparedTargetMatched", "policy", "activationInputsCurrent", "targetAuthorityVerified",
            "durableHandoffVerified", "productionCompletionVerified", "certifiesUpgrade", "ordinaryWriterBlocked"}
    if (type(value) is not dict or set(value) != {"result"} or type(value["result"]) is not dict
            or set(value["result"]) != keys):
        raise ValueError("fresh inspector result shape changed")
    report = value["result"]
    if (report["policy"] != plan["policy"] or any(type(report[k]) is not bool for k in keys - {"policy"})
            or not report["preparedTargetMatched"] or not report["ordinaryWriterBlocked"]
            or any(report[k] for k in ("targetAuthorityVerified", "durableHandoffVerified", "productionCompletionVerified", "certifiesUpgrade"))
            or (plan["policy"] == cli.target.POLICIES[1] and not report["activationInputsCurrent"])):
        raise ValueError("inspector cannot grant completion or authority")
    return report


def inspect(root, plan, expected_run, expected_source, expected_sha, *, start=launch):
    cli.target.pm.fixture(root)
    held = cli.driver.held(root)
    try:
        cli.target.validate(held, plan, expected_run, expected_source, expected_sha)
        saved = slots(held)
    finally:
        held.close()  # The supervisor deliberately retains NO maintenance descriptor.
    matched, refused = 0, 0
    for phase, count in actor.PHASES.items():
        process = start(root, plan, expected_run, expected_source, expected_sha, phase)
        try:
            checkpoint = actor.support.read(process.stdout.fileno(), timeout=90)
            if (checkpoint != {"checkpoint": phase, "observations": count}
                    or type(checkpoint.get("observations")) is not int):
                raise ValueError("inspection checkpoint not observed")
            process.kill()
            if process.wait(timeout=5) != -signal.SIGKILL or process.stdout.read(1):
                raise ValueError("inspection did not stop at checkpoint")
        finally:
            close(process)
        closed(root, saved)
        # Always start a new interpreter with the independently retained plan. No saved verdict input.
        process = start(root, plan, expected_run, expected_source, expected_sha, "none")
        try:
            report = result(actor.support.read(process.stdout.fileno(), timeout=90), plan)
            if process.wait(timeout=10) != 0 or process.stdout.read(1):
                raise ValueError("fresh inspector did not exit normally")
        finally:
            close(process)
        closed(root, saved)
        matched += 1
    # After the last verified inspector dies, altered expectations cannot reuse its result.
    bad_plan = copy.deepcopy(plan)
    bad_plan["generation"]["environmentDigest"] = "0" * 64
    bad_args = [(plan, str(uuid.uuid4()), expected_sha), (plan, expected_run, "0" * 64),
                (bad_plan, expected_run, cli.target.binding.digest(bad_plan))]
    for candidate, run, digest in bad_args:
        process = start(root, candidate, run, expected_source, digest, "none")
        try:
            value = actor.support.read(process.stdout.fileno(), timeout=90)
            if value != {"inspectionRefused": True} or process.wait(timeout=10) != 1 or process.stdout.read(1):
                raise ValueError("changed expectations reused an old inspection")
        finally:
            close(process)
        closed(root, saved)
        refused += 1
    return {"inspectionSigkillCases": len(actor.PHASES), "freshInspectionMatches": matched,
            "changedExpectationRefusals": refused, "activationInputsCurrent": report["activationInputsCurrent"],
            "intentBytesUnchanged": True, "ordinaryWriterBlocked": True,
            "productionCompletionVerified": False, "targetAuthorityVerified": False,
            "durableHandoffVerified": False, "updaterRecoveryVerified": False, "certifiesUpgrade": False}


def main():
    parser = cli.PlanArguments(description=__doc__)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--plan-file", type=Path, required=True)
    parser.add_argument("--expected-run", required=True)
    parser.add_argument("--expected-source", required=True)
    parser.add_argument("--expected-plan-sha", required=True)
    try:
        args = parser.parse_args()
        if sys.platform != "darwin":
            raise ValueError("actual fixture inspection rehearsal requires macOS")
        value = inspect(args.fixture_root.resolve(), cli.read_plan(args.plan_file), args.expected_run,
                        args.expected_source, args.expected_plan_sha)
    except (ValueError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "pm-inspection-recheck-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": value}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
