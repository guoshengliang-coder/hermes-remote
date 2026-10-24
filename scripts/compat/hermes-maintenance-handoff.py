#!/usr/bin/env python3
"""Live bound handoff in disposable startup prefixes; no production/restart authority."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess

spec = importlib.util.spec_from_file_location("bootstrap_driver", Path(__file__).with_name("hermes-maintenance-bootstrap.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)
entry = bootstrap.entry


def inspect(root):
    values = entry.sources(root, expected=bootstrap.BOOTSTRAP_SHA256)
    targets = [(target, False) for target in bootstrap.ENTRIES] + [("hermes_cli.main", True)]
    for target, profile in targets:
        value = entry.experiment(values, "shared-bootstrap", "module", ["--help"], profile=profile,
                                 entry_module=target, role="bound-recovery")
        if (value["blocked"] or value["identityRejected"] or not value["boundIdentityVerified"]
                or not value["liveHandshakeAccepted"] or not value["prefixReached"]
                or not value["scratchCreated"] or value["admissionCalls"] != 1
                or not value["handoffBlocks_writer"] or not value["handoffBlocks_maintenance"]
                or not value["drainsAfterRecoveryExit"]):
            raise ValueError("live handoff lifetime changed")
    for fault in entry.BINDING_FAULTS:
        value = entry.experiment(values, "shared-bootstrap", "module", ["--help"],
                                 role="bound-recovery", handoff_fault=fault)
        if (not value["blocked"] or not value["identityRejected"] or value["boundIdentityVerified"]
                or value["liveHandshakeAccepted"] or not value["parentRetainedAfterRejection"]
                or value["bootstrapEntered"] or value["prefixReached"] or value["scratchCreated"]
                or value["admissionCalls"] != 0 or value["homeMutationAttempts"] != 0):
            raise ValueError("live handoff refusal changed")
    return {"boundRecoveryPrefixes": len(targets), "rejectedBindingCases": len(entry.BINDING_FAULTS),
            "liveBindingChecksVerified": True, "outsidersBlockedAcrossParentClose": True,
            "rejectionsRetainParentCustody": True, "pmRecoveryInjected": True,
            "productionAuthorityVerified": False, "durableHandoffVerified": False,
            "nativePreloadVerified": False, "cronPackageBootVerified": False, "fullStartupVerified": False,
            "globalAdmissionProven": False, "certifiesUpgrade": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", required=True, type=Path)
    args = parser.parse_args()
    try:
        observations = inspect(args.candidate_root)
    except (OSError, ValueError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "live-handoff-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": observations}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
