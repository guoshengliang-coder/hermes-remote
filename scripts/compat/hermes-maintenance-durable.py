#!/usr/bin/env python3
"""Persisted admission intent in disposable sources; not production restart authority."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import uuid


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


bootstrap = load("durable_bootstrap", Path(__file__).with_name("hermes-maintenance-bootstrap.py"))
entry = bootstrap.entry
lease = load("durable_lease", entry.PROTOTYPE / "maintenance_lease.py")
intent = load("durable_intent", entry.PROTOTYPE / "durable_intent.py")
support = load("durable_support", entry.PROTOTYPE / "handoff_actor.py")
FAULTS = ("wrong-run", "wrong-source", "wrong-home", "wrong-entry", "wrong-role", "wrong-scope", "changed-source")


class Fixture:
    def __init__(self, values, target):
        self.temporary = tempfile.TemporaryDirectory(prefix="hg199-durable-")
        self.root = Path(self.temporary.name).resolve()
        self.control, self.home, self.checkout = [self.root / name for name in ("control", "data", "checkout")]
        for path in (self.control, self.home, self.checkout):
            path.mkdir(mode=0o700)
        entry.stage(values, self.checkout, "shared-bootstrap")
        self.domains = lease._domains([lease.Domain(self.control, kind, path) for kind, path in
                                     (("home", self.home), ("checkout", self.checkout))])
        for domain in self.domains:
            domain.enroll()
        self.records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in self.domains]
        self.target, self.names = target, list(values)
        self.context = intent.binding.context(str(uuid.uuid4()), entry.SOURCE_ID, self.checkout, self.home,
                                              target, self.records, self.names)

    def close(self):
        self.temporary.cleanup()

    def slots(self):
        return [d._path("intent").read_bytes() for d in self.domains]

    def publish_and_die(self, phase):
        fd = intent.binding.private_context_fd(self.control, self.context)
        child = None
        try:
            child = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(entry.PROTOTYPE / "durable_actor.py"),
                                      str(self.root), json.dumps(self.records), str(fd), self.context["run"],
                                      self.context["source"], str(self.home), self.target, phase],
                                     cwd=self.root, env={"PATH": os.defpath, "HOME": str(self.root)},
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(fd,))
            os.close(fd)
            fd = None
            if support.read(child.stdout.fileno()) != {"checkpoint": phase}:
                raise ValueError("intent publisher checkpoint")
            child.kill()  # Only the disposable publisher created above; supervisor owns no lease.
            if child.wait(timeout=5) != -signal.SIGKILL:
                raise ValueError("intent publisher death")
        finally:
            if fd is not None:
                os.close(fd)
            if child is not None:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
                child.stdin.close()
                child.stdout.close()

    def probe(self, role="writer", context=None, home=None, target=None, compete=False):
        context = self.context if context is None else context
        home = self.home if home is None else home
        target = self.target if target is None else target
        durable = role == "durable-recovery"
        fd, child = None, None
        try:
            binding = {}
            if durable:
                fd = intent.binding.private_context_fd(self.control, context)
                binding = {"context": fd, "run": context["run"], "source": context["source"]}
            child = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(entry.PROTOTYPE / "startup_probe.py"),
                                      str(self.checkout), json.dumps(self.records), "module", json.dumps(["--help"]),
                                      target, role, "[]", json.dumps(binding)], cwd=self.root,
                                     env={"PATH": os.defpath, "HOME": str(self.root), "HERMES_HOME": str(home)},
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, pass_fds=(fd,) if fd is not None else ())
            if fd is not None:
                os.close(fd)
                fd = None
            value = entry.read_result(child, durable=durable)
            if durable and value["durableIntentVerified"]:
                try:
                    outsider = lease.Lease(self.domains, maintenance=True)
                except BlockingIOError:
                    value["recoveryRetainsExclusive"] = True
                else:
                    outsider.close()
                    value["recoveryRetainsExclusive"] = False
                if compete:
                    other = self.probe("durable-recovery")
                    value["competingRecoveryRejected"] = (other["identityRejected"] and not other["bootstrapEntered"]
                                                           and other["homeMutationAttempts"] == 0)
            child.stdin.write("release\n")
            child.stdin.flush()
            if child.wait(timeout=5) != 0:
                raise ValueError("intent probe exit")
            return value
        finally:
            if fd is not None:
                os.close(fd)
            if child is not None:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
                child.stdin.close()
                child.stdout.close()


def inspect(root):
    values = entry.sources(root, expected=bootstrap.BOOTSTRAP_SHA256)
    rejected = 0
    for target in bootstrap.ENTRIES:
        fixture = Fixture(values, target)
        try:
            fixture.publish_and_die("complete")
            saved = fixture.slots()
            writer = fixture.probe()
            if not writer["blocked"] or writer["homeMutationAttempts"] or writer["scratchCreated"]:
                raise ValueError("post-death writer admitted")
            for fault in FAULTS:
                context = json.loads(intent.binding.encode(fixture.context))
                home, requested = fixture.home, target
                path = fixture.checkout / "hermes_constants.py"
                original = path.read_bytes()
                try:
                    if fault == "wrong-run": context["run"] = str(uuid.uuid4())
                    if fault == "wrong-source": context["source"] = "0" * 40
                    if fault == "wrong-role": context["role"] = "writer"
                    if fault == "wrong-scope": context["domains"] = []
                    if fault == "wrong-entry":
                        requested = "agent.legacy_cli" if target != "agent.legacy_cli" else "hermes_cli.main"
                        context["entry"] = requested
                    if fault == "wrong-home":
                        home = fixture.home / "profiles/other"
                        home.mkdir(mode=0o700, parents=True)
                    if fault in ("wrong-home", "changed-source"):
                        if fault == "changed-source":
                            path.write_bytes(original + b"\n# synthetic change\n")
                        context = intent.binding.context(context["run"], context["source"], fixture.checkout, home,
                                                         requested, fixture.records, fixture.names)
                    refusal = fixture.probe("durable-recovery", context, home, requested)
                    if (not refusal["identityRejected"] or refusal["durableIntentVerified"]
                            or refusal["bootstrapEntered"] or refusal["homeMutationAttempts"]):
                        raise ValueError("wrong recovery context admitted")
                    rejected += 1
                finally:
                    if path.read_bytes() != original:
                        path.write_bytes(original)
            recovered = fixture.probe("durable-recovery", compete=True)
            if (recovered["identityRejected"] or not recovered["durableIntentVerified"] or not recovered["prefixReached"]
                    or not recovered["scratchCreated"] or recovered["admissionCalls"] != 1
                    or not recovered["recoveryRetainsExclusive"] or not recovered["competingRecoveryRejected"]):
                raise ValueError("new process recovery failed")
            if not fixture.probe()["blocked"] or fixture.slots() != saved:
                raise ValueError("recovery exit cleared maintenance")
        finally:
            fixture.close()
    fixture = Fixture(values, "hermes_cli.main")
    try:
        fixture.publish_and_die("partial")
        saved = fixture.slots()
        refusal = fixture.probe("durable-recovery")
        if (not fixture.probe()["blocked"] or not refusal["identityRejected"]
                or refusal["bootstrapEntered"] or refusal["homeMutationAttempts"] or fixture.slots() != saved):
            raise ValueError("partial intent repaired or admitted")
    finally:
        fixture.close()
    return {"persistedIntentEntries": 4, "newProcessRecoveryPrefixes": 4, "rejectedRecoveryContexts": rejected,
            "publisherSigkillCases": 5, "partialPublicationStaysClosed": True, "processRestartAdmissionVerified": True,
            "recoveryExitKeepsIntent": True, "automaticIntentClearImplemented": False, "pmRecoveryInjected": True,
            "productionAuthorityVerified": False, "durableHandoffVerified": False, "osRebootVerified": False,
            "nativePreloadVerified": False, "cronPackageBootVerified": False,
            "fullStartupVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}


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
                          "reason": "durable-intent-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": value}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
