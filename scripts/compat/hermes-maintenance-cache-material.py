#!/usr/bin/env python3
"""Opt-in fixed-source cache preservation; no runtime or upgrade execution."""
import importlib.util
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load("cache_material_cli_fixture", HERE / "upstream-maintenance-prototype/cache_material_fixture.py")
arguments = load("cache_material_cli_arguments", HERE / "hermes-maintenance-target.py")
COMMIT = "bf0203a794a07aac3ff764972ab9087baa4e4f4e"


def inspect(seed):
    case = fixture.Fixture(seed, COMMIT)
    manifest = case.preserve_and_kill()
    observed = case.recheck_child(manifest)
    fixture.blocked(case.root)
    return {"ok": True, "fixture": str(case.root), "sourceCommit": COMMIT,
            "materialCoordinatorSigkillCases": 1, "freshProcessRecheck": True,
            "ordinaryWriterBlocked": True, "coldSourcePreparedBeforeMeasuredTransaction": True,
            "hermesRuntimeStarted": False, "installedHermesModified": False, **observed}


def main():
    parser = arguments.PlanArguments(description=__doc__)
    parser.add_argument("--provision", action="store_true")
    parser.add_argument("--candidate-root", type=Path, required=True)
    args = parser.parse_args()
    if not args.provision:
        raise ValueError("isolated material opt-in required")
    return inspect(args.candidate_root.resolve(strict=True))


if __name__ == "__main__":
    try:
        result = main()
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, subprocess.SubprocessError,
            fixture.material.worker.Refused):
        result = {"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
            "summaryChinese": "无法核验源码缓存保全材料。",
            "summaryEnglish": "Source cache preservation materials could not be verified.",
            "certifiesUpgrade": False}
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["ok"] else 1)
