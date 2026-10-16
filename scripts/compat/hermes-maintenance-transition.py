#!/usr/bin/env python3
"""Fixture generation/terminal experiment, never a production completion policy."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


durable = load("transition_durable", Path(__file__).with_name("hermes-maintenance-durable.py"))
transition = load("generation_transition", durable.entry.PROTOTYPE / "generation_transition.py")


class Fixture:
    def __init__(self, values, target):
        self.base = durable.Fixture(values, target)
        self.generation = self.base.checkout / transition.DEPENDENCY
        self.generation.write_bytes(b"fixture dependency generation: before\n")
        self.base.names.append(transition.DEPENDENCY)
        before = transition.binding.context(self.base.context["run"], durable.entry.SOURCE_ID, self.base.checkout,
                                             self.base.home, target, self.base.records, self.base.names)
        self.base.context = before
        source = self.base.checkout / "hermes_constants.py"
        self.before_bytes = {source: source.read_bytes(), self.generation: self.generation.read_bytes()}
        self.after_bytes = {source: self.before_bytes[source] + b"\n# HG199 synthetic generation, not an upstream release\n",
                            self.generation: b"fixture dependency generation: after\n"}
        after = json.loads(transition.binding.encode(before))
        after["source"] = hashlib.sha256(b"synthetic fixture generation" + b"".join(self.after_bytes.values())).hexdigest()[:40]
        for path, data in self.after_bytes.items():
            after["files"][path.name] = hashlib.sha256(data).hexdigest()
        self.plan = {"schema": 1, "policy": transition.POLICY, "before": before, "after": after}
        held = self.held()
        try:
            durable.intent.begin(held, before)
        finally:
            held.close()

    def close(self):
        self.base.close()

    def held(self):
        value = durable.lease.Lease(self.base.domains, maintenance=True)
        if not value.drain():
            value.close()
            raise ValueError("fixture still busy")
        return value

    def stage_target(self):
        held = self.held()
        try:
            transition.resume_before_update(held, self.plan)
            for path, data in self.after_bytes.items():
                path.write_bytes(data)  # ONLY the explicitly planned, disposable fixture inputs.
            transition.target(held, self.plan)
        finally:
            held.close()

    def coordinator_death(self, operation, phase):
        manifest = transition.binding.private_context_fd(self.base.control, self.plan)
        child = None
        try:
            child = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(durable.entry.PROTOTYPE / "transition_actor.py"),
                                      json.dumps(self.base.records), str(manifest), operation, phase], cwd=self.base.root,
                                     env={"PATH": os.defpath, "HOME": str(self.base.root)}, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(manifest,))
            os.close(manifest)
            manifest = None
            if durable.support.read(child.stdout.fileno()) != {"checkpoint": operation + "-" + phase}:
                raise ValueError("fixture coordinator checkpoint")
            child.kill()  # Only this test-owned coordinator; its observed prefix child already exited.
            if child.wait(timeout=5) != -signal.SIGKILL:
                raise ValueError("fixture coordinator death")
        finally:
            if manifest is not None:
                os.close(manifest)
            if child is not None:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
                child.stdin.close()
                child.stdout.close()


def inspect(root):
    values = durable.entry.sources(root, expected=durable.bootstrap.BOOTSTRAP_SHA256)
    refused = 0
    for target in durable.bootstrap.ENTRIES:
        fixture = Fixture(values, target)
        try:
            fixture.coordinator_death("plan", "complete")
            fixture.stage_target()
            if not fixture.base.probe()["blocked"]:
                raise ValueError("fixture opened before terminal proof")
            fixture.coordinator_death("finish", "complete")
            saved = fixture.base.slots()
            result = fixture.base.probe()
            if result["blocked"] or not result["prefixReached"] or result["admissionCalls"] != 1:
                raise ValueError("completed fixture writer rejected")
            for path, old in fixture.before_bytes.items():
                path.write_bytes(old)
                if not fixture.base.probe()["blocked"]:
                    raise ValueError("stale fixture generation admitted")
                refused += 1
                path.write_bytes(fixture.after_bytes[path])
            if fixture.base.slots() != saved or not all(len(data.splitlines()) == 3 for data in saved):
                raise ValueError("fixture history reset or changed")
        finally:
            fixture.close()
    for operation in ("plan", "finish"):
        fixture = Fixture(values, "hermes_cli.main")
        try:
            if operation == "finish":
                held = fixture.held()
                try:
                    transition.prepare(held, fixture.plan)
                finally:
                    held.close()
                fixture.stage_target()
            fixture.coordinator_death(operation, "partial")
            saved = fixture.base.slots()
            if not fixture.base.probe()["blocked"] or fixture.base.slots() != saved:
                raise ValueError("partial fixture journal opened admission")
        finally:
            fixture.close()
    return {"fixtureGenerationEntries": 4, "completedFixtureWriterPrefixes": 4, "staleGenerationRefusals": refused,
            "coordinatorSigkillCases": 10, "partialPlanAndTerminalStayClosed": True,
            "fixtureCompletionPolicyVerified": True, "intentHistoryPreserved": True,
            "realDependencyGenerationVerified": False, "productionCompletionVerified": False,
            "productionAuthorityVerified": False, "durableHandoffVerified": False, "osRebootVerified": False,
            "pmRecoveryInjected": True, "fullStartupVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", required=True, type=Path)
    args = parser.parse_args()
    try:
        value = inspect(args.candidate_root)
    except (ValueError, OSError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "fixture-transition-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": value}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
