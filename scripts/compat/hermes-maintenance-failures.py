#!/usr/bin/env python3
"""Live uncertainty and synthetic process death; does not certify durable maintenance."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


bootstrap = load("failure_bootstrap", Path(__file__).with_name("hermes-maintenance-bootstrap.py"))
entry = bootstrap.entry
actor = load("failure_actor", entry.PROTOTYPE / "handoff_actor.py")


def blocked(domains):
    for maintenance in (False, True):
        try:
            value = actor.lease.Lease(domains, maintenance=maintenance)
        except BlockingIOError:
            continue
        else:
            value.close()
            return False
    return True


def command(fd, name):
    os.write(fd, json.dumps({"command": name}).encode() + b"\n")


def death_case(victim, phase):
    if victim not in ("parent", "child", "both") or phase not in actor.PHASES or (victim == "both" and phase != "authorized"):
        raise ValueError("death scope")
    with tempfile.TemporaryDirectory(prefix="hg199-handoff-death-") as temporary:
        root = Path(temporary).resolve()
        for name in ("control", "data", "checkout"):
            (root / name).mkdir(mode=0o700)
        (root / "checkout/sample.py").write_bytes(b"# Synthetic input, not Hermes startup.\n")
        domains = actor.domains(root)
        for domain in domains:
            domain.enroll()
        incoming, outgoing = os.pipe()
        controls, sender = os.pipe()
        process = None
        try:
            process = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(Path(actor.__file__)), "parent",
                                        str(root), phase, victim, str(controls), str(outgoing)],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       cwd=root, env={"PATH": os.defpath, "HOME": str(root)}, pass_fds=(controls, outgoing))
            os.close(controls)
            controls = None
            os.close(outgoing)
            outgoing = None
            if actor.read(process.stdout.fileno()) != {"status": "spawned"} or actor.read(incoming) != {"phase": phase}:
                raise ValueError("death checkpoint")
            if not blocked(domains):
                raise ValueError("missing custody before death")
            if victim == "parent":
                process.kill()  # Only the Popen object created by this case.
                if process.wait(timeout=5) != -signal.SIGKILL or not blocked(domains):
                    raise ValueError("child custody lost on parent death")
                command(sender, "continue")
                authorized = phase == "authorized"
                if actor.read(incoming) != {"status": "authorized" if authorized else "rejected"}:
                    raise ValueError("parent death authorization")
                if authorized:
                    if not blocked(domains):
                        raise ValueError("authorized child lost custody")
                    command(sender, "release")
                if actor.read(incoming) != {"status": "closed"}:
                    raise ValueError("child close")
                outcome = "parent-dead"
            else:
                command(sender, "die")
                result = actor.read(process.stdout.fileno())
                outcome = {"ready": "rejected-before-commit", "accepted": "commit-uncertain",
                           "commit-received": "commit-uncertain", "authorized": "confirmed"}[phase]
                if result != {"status": "outcome", "outcome": outcome, "childExit": -signal.SIGKILL} or not blocked(domains):
                    raise ValueError("parent custody or death classification")
                authorized = phase == "authorized"
                if victim == "both":
                    process.kill()
                    if process.wait(timeout=5) != -signal.SIGKILL:
                        raise ValueError("parent death")
                else:
                    command(process.stdin.fileno(), "finish")
                    if actor.read(process.stdout.fileno()) != {"status": "closed"} or process.wait(timeout=5) != 0:
                        raise ValueError("parent close")
            if (root / "data/authorized-marker").exists() != authorized:
                raise ValueError("synthetic write boundary")
            # An explicit negative control: after all live custodians close/die,
            # these kernel locks do not remember a transaction across restart.
            writer = actor.lease.Lease(domains)
            writer.close()
            after = actor.lease.Lease(domains, maintenance=True)
            try:
                if not after.drain():
                    raise ValueError("custody leak")
            finally:
                after.close()
            return {"outcome": outcome, "syntheticWriteObserved": authorized,
                    "survivorExcludedOutsiders": True, "allCustodiansGoneAdmitsWriter": True}
        finally:
            # Closing the supervisor's command pipe makes any paused child exit;
            # its bounded reader also prevents an abandoned test process.
            for fd in (sender, incoming, controls, outgoing):
                if fd is not None:
                    os.close(fd)
            if process is not None:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()


def inspect(root):
    values = entry.sources(root, expected=bootstrap.BOOTSTRAP_SHA256)
    cases = 0
    for target in bootstrap.ENTRIES:
        for fault in entry.COMMIT_FAULTS:
            value = entry.experiment(values, "shared-bootstrap", "module", ["--help"], entry_module=target,
                                     role="bound-recovery", handoff_fault=fault)
            ran = fault == "lost-final-ack"
            if (value["liveHandshakeAccepted"] or value["liveHandshakeOutcome"] != "commit-uncertain"
                    or not value["parentRetainedOnUncertainty"] or value["parentRetainedAfterRejection"]
                    or not value["uncertaintyBlocksAfterChildExit"]
                    or value["boundIdentityVerified"] != ran or value["prefixReached"] != ran
                    or value["scratchCreated"] != ran or value["identityRejected"] == ran
                    or (not ran and value["homeMutationAttempts"] != 0)):
                raise ValueError("uncertainty misclassified")
            cases += 1
    for victim in ("parent", "child"):
        for phase in actor.PHASES:
            death_case(victim, phase)
    death_case("both", "authorized")
    return {"uncertainFrozenPrefixes": cases, "syntheticDeathCases": 9,
            "uncertaintyRetainsParentCustody": True, "survivorCustodyVerified": True,
            "allCustodiansGoneAdmitsWriter": True, "pmRecoveryInjected": True,
            "productionAuthorityVerified": False, "durableHandoffVerified": False,
            "nativePreloadVerified": False, "cronPackageBootVerified": False,
            "fullStartupVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        value = inspect(args.candidate_root)
    except (ValueError, OSError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "handoff-failure-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": value}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
