#!/usr/bin/env python3
"""Shared bootstrap and inherited recovery-role experiments, not runtime certification."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess

spec = importlib.util.spec_from_file_location("maintenance_entry", Path(__file__).with_name("hermes-maintenance-entry.py"))
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)

BOOTSTRAP_SHA256 = {**entry.ENTRY_SHA256,
    "agent/legacy_cli.py": "fb7e1b7ca3cfb8317a8d48904dd5f8b08d10a76536629f36ebfe8dae3d8c5b9d",
    "agent/__init__.py": "4b8495cff6ef2502e6cf2f42e384670d5da7a9cfe7d155e4b52fe971afc4c362",
    "agent/jiter_preload.py": "98e6b8131e74255f21000f55f4d77709e8c34200374819654dbea763cdbf6d1a",
    "acp_adapter/entry.py": "32cb6a33fc83c8925ce0c8a1aa526ef6618cb37d27832710fe2a7e9a79dfe7ee",
    "acp_adapter/__init__.py": "c841e4cd574c5ae19003aebaee22396db3cc515dd600934059bbe78958324e98",
    "gateway/run.py": "a3f05d3b91ed23fdab2946c050619cce822b8c483e6f5dde75cf17c11e8a5ffe",
    "gateway/__init__.py": "c69d534d5d7acdd52b71bf168c09071402feb7352a0dca58c2f9efd0175fcded",
}
ENTRIES = ("hermes_cli.main", "agent.legacy_cli", "acp_adapter.entry", "gateway.run")


def inspect(root):
    values = entry.sources(root, expected=BOOTSTRAP_SHA256)
    cases, missed = 0, 0
    for target in ENTRIES:
        for mode in ("module", "console-import", "script"):
            shared = entry.experiment(values, "shared-bootstrap", mode, ["--help"], entry_module=target)
            main_only = entry.experiment(values, "before-bootstrap", mode, ["--help"], entry_module=target)
            if (not shared["blocked"] or not shared["bootstrapEntered"] or shared["prefixReached"]
                    or shared["admissionCalls"] != 1 or shared["homeMutationAttempts"] != 0 or shared["scratchCreated"]):
                raise ValueError("shared bootstrap guard changed")
            if target == "hermes_cli.main":
                if not main_only["blocked"] or main_only["scratchCreated"]:
                    raise ValueError("main guard changed")
            else:
                if (main_only["blocked"] or not main_only["prefixReached"] or not main_only["scratchCreated"]
                        or main_only["admissionCalls"] != 0 or main_only["homeMutationAttempts"] == 0):
                    raise ValueError("alternate entry scope changed")
                missed += 1
            cases += 1
        ordinary = entry.experiment(values, "shared-bootstrap", "module", ["--help"],
                                    entry_module=target, maintenance=False)
        recovery = entry.experiment(values, "shared-bootstrap", "module", ["--help"],
                                    entry_module=target, role="recovery")
        if (ordinary["blocked"] or not ordinary["scratchCreated"] or not ordinary["retainedWriterBlocksDrain"]
                or not ordinary["drainsAfterPrefixExit"] or recovery["blocked"] or not recovery["scratchCreated"]
                or recovery["admissionCalls"] != 1 or not recovery["handoffBlocks_writer"]
                or not recovery["handoffBlocks_maintenance"] or not recovery["drainsAfterRecoveryExit"]):
            raise ValueError("bootstrap role lifetime changed")
    return {"sharedEntryCases": cases, "mainOnlyMissedCases": missed, "admittedWriterEntries": len(ENTRIES),
            "inheritedRecoveryEntries": len(ENTRIES), "outsidersBlockedAcrossParentClose": True,
            "pmRecoveryInjected": True, "nativePreloadVerified": False, "cronPackageBootVerified": False,
            "authenticatedHandoffVerified": False, "fullStartupVerified": False,
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
                          "reason": "bootstrap-admission-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": observations}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
