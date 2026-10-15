#!/usr/bin/env python3
"""Counterexample to global writer exclusion, not a safety/admission certification.

Runs the exact retirement module from frozen source snapshots in two isolated Python processes.
Only the idle ledger is injected; no Hermes server, task, database or service is started.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

LOADER = '''import importlib.util,sys
spec=importlib.util.spec_from_file_location("retirement_fixture",sys.argv[1])
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
'''
PARENT = LOADER + '''
import types,subprocess,json,os
package=types.ModuleType("hermes_cli");package.__path__=[];sys.modules["hermes_cli"]=package
idle=types.ModuleType("hermes_cli.web_server_idle_proof")
idle.idle_proof=lambda:{"idle":True};sys.modules[idle.__name__]=idle
permit=module.retirement.prepare()
assert permit["ok"] and module.retirement.commit(permit["token"])["ok"]
closed=not module.retirement.acquire()
other=subprocess.run([sys.executable,"-I","-S","-B","-c",sys.argv[2],sys.argv[1]],
    env=os.environ.copy(),check=True,capture_output=True,text=True,timeout=5)
assert other.stdout.strip() in ("True","False")
print(json.dumps({"committedProcessRefuses":closed,"separateProcessAdmits":other.stdout.strip()=="True"}))
'''


def inspect(root):
    module = Path(root).resolve() / "hermes_cli/backend_retirement.py"
    with tempfile.TemporaryDirectory(prefix="hg199-maintenance-scope-") as temporary:
        home = Path(temporary)
        (home / "data").mkdir(mode=0o700)
        env = {"PATH": os.defpath, "HOME": str(home), "HERMES_HOME": str(home / "data"),
               "XDG_CACHE_HOME": str(home / "cache")}
        result = subprocess.run([sys.executable, "-I", "-S", "-B", "-c", PARENT, str(module),
                                 LOADER + "print(module.retirement.acquire())"], env=env, cwd=home,
                                capture_output=True, text=True, timeout=12, check=True)
        value = json.loads(result.stdout)
        if value != {"committedProcessRefuses": True, "separateProcessAdmits": True}:
            # A future change needs a new audit, never automatic promotion to global proof.
            raise ValueError("maintenance scope changed")
        return {**value, "globalAdmissionProven": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-root", required=True, type=Path)
    parser.add_argument("--candidate-root", required=True, type=Path)
    args = parser.parse_args()
    try:
        values = {"old": inspect(args.old_root), "candidate": inspect(args.candidate_root)}
    except (OSError, ValueError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "maintenance-scope-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": values, "certifiesUpgrade": False}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
