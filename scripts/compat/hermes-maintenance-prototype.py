#!/usr/bin/env python3
"""Isolated upstream retirement + proposed POSIX lease experiment, never certification."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

PROTOTYPE = Path(__file__).with_name("upstream-maintenance-prototype") / "maintenance_lease.py"
RETIREMENT_SHA256 = "1122ace38dea6bd7cc298c2e1f59aefa757a7ed88d8a01dc7bddc14a47c55c55"

PARTICIPANT = '''
import importlib.util,json,sys
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
m=load("maintenance_prototype",sys.argv[1])
r=load("retirement_fixture",sys.argv[2])
domains=[m.Domain(*domain) for domain in json.loads(sys.argv[3])]
if sys.argv[4]=="uncooperative":
    print(json.dumps({"uncooperativeAdmits":r.retirement.acquire()}))
else:
    try: held=m.Lease(domains)
    except BlockingIOError: print(json.dumps({"participantBlockedBeforeDispatch":True}))
    else:
        try: print(json.dumps({"participantBlockedBeforeDispatch":False,"admitted":r.retirement.acquire()}))
        finally: held.close()
'''


def inspect(candidate_root):
    source = (Path(candidate_root) / "hermes_cli/backend_retirement.py").read_bytes()
    if hashlib.sha256(source).hexdigest() != RETIREMENT_SHA256:
        raise ValueError("retirement module requires review")
    spec = importlib.util.spec_from_file_location("maintenance_prototype", PROTOTYPE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix="hg199-upstream-maintenance-") as directory:
        root = Path(directory).resolve()
        control, home, checkout, other = [root / name for name in ("control", "home", "checkout", "other-checkout")]
        for path in (control, home, checkout, other):
            path.mkdir(mode=0o700)
        # Only copy the frozen retirement module; no bootstrap, server or user data.
        retirement = checkout / "backend_retirement.py"
        retirement.write_bytes(source)
        domains = [module.Domain(control, kind, path) for kind, path in
                   (("home", home), ("checkout", checkout), ("checkout", other))]
        for domain in domains:
            domain.enroll()
        request = module.Lease(domains[:2], maintenance=True)
        try:
            if not request.drain():
                raise ValueError("unexpected participant in isolated domain")
            def run(selected, mode):
                args = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in selected]
                result = subprocess.run([sys.executable, "-I", "-S", "-B", "-c", PARTICIPANT,
                                         str(PROTOTYPE), str(retirement), json.dumps(args), mode],
                                        env={"PATH": os.defpath, "HOME": str(root), "HERMES_HOME": str(home)},
                                        cwd=root, capture_output=True, text=True, check=True, timeout=5)
                return json.loads(result.stdout)
            same = run(domains[:2], "participant")
            different = run([domains[0], domains[2]], "participant")
            bypass = run(domains[:2], "uncooperative")
            if (same != {"participantBlockedBeforeDispatch": True}
                    or different != same or bypass != {"uncooperativeAdmits": True}):
                raise ValueError("prototype scope changed")
            return {"sameCheckoutParticipantBlocked": True, "otherCheckoutSameHomeParticipantBlocked": True,
                    "realUnmodifiedRetirementStillBypasses": True, "globalAdmissionProven": False,
                    "certifiesUpgrade": False}
        finally:
            request.close()


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
                          "reason": "maintenance-prototype-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": observations}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
