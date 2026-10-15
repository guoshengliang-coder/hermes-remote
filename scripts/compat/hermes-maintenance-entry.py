#!/usr/bin/env python3
"""Frozen CLI/bootstrap admission-placement experiment; never upgrades an installation."""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time

PROTOTYPE = Path(__file__).with_name("upstream-maintenance-prototype")
ENTRY_SHA256 = {
    "hermes_cli/main.py": "1491ff17d20b54c024c052003d394798cb2e673c8b9565b4a8be4c1fd6b21dae",
    "hermes_bootstrap.py": "ef827fba947d33dc29fe4afd587264b982a37e5b1da2130a4806c8d84e58ee6c",
    "hermes_constants.py": "5d43e6102c69748a2e64fc91623a49e1b0d76623416ce4ec37f23f216c3ce91f",
    "hermes_cli/__init__.py": "6a4ab05b8621ec1cd66a5abbe30fa32ed5bddddea95903036da01f0af1af94ce",
}


def sources(root):
    values = {name: (Path(root) / name).read_bytes() for name in ENTRY_SHA256}
    if any(hashlib.sha256(data).hexdigest() != ENTRY_SHA256[name] for name, data in values.items()):
        raise ValueError("startup sources require review")
    return values


def stage(values, checkout, placement):
    for name, data in values.items():
        target = checkout / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    entry = checkout / "hermes_cli/main.py"
    tree = ast.parse(entry.read_text())
    admission = ast.parse("from hermes_cli._hg199_entry_binding import enter\n_hg199_lease = enter()\n").body
    if placement == "before-bootstrap":
        tree.body[1:1] = admission  # Preserve the module docstring; enter before every upstream import.
    elif placement == "inside-main":
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "main"]
        if len(functions) != 1:
            raise ValueError("unexpected main boundary")
        functions[0].body[0:0] = admission
    else:
        raise ValueError("unsupported admission placement")
    entry.write_text(ast.unparse(ast.fix_missing_locations(tree)) + "\n")


def read_result(child, timeout=8):
    deadline = time.monotonic() + timeout
    data = b""
    while b"\n" not in data:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([child.stdout], [], [], remaining)[0]:
            raise ValueError("startup probe deadline")
        chunk = os.read(child.stdout.fileno(), 4097 - len(data))
        if not chunk or len(data) + len(chunk) > 4096:
            raise ValueError("startup probe output")
        data += chunk
    value = json.loads(data.split(b"\n", 1)[0])
    types = {"blocked": bool, "bootstrapEntered": bool, "prefixReached": bool, "admissionCalls": int,
             "homeMutationAttempts": int, "pmRecoveryInjected": bool, "scratchCreated": bool}
    if (type(value) is not dict or set(value) != set(types)
            or any(type(value[name]) is not kind for name, kind in types.items())):
        raise ValueError("startup probe failed")
    return value


def experiment(values, placement, mode, argv, profile=False, maintenance=True):
    spec = importlib.util.spec_from_file_location("entry_lease", PROTOTYPE / "maintenance_lease.py")
    lease = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lease)
    with tempfile.TemporaryDirectory(prefix="hg199-startup-entry-") as directory:
        root = Path(directory).resolve()
        control, home, checkout = [root / name for name in ("control", "data", "checkout")]
        for target in (control, home, checkout):
            target.mkdir(mode=0o700)
        active = home / "profiles/named" if profile else home
        active.mkdir(parents=True, exist_ok=True, mode=0o700)
        stage(values, checkout, placement)
        domains = [lease.Domain(control, kind, target) for kind, target in (("home", home), ("checkout", checkout))]
        for domain in domains:
            domain.enroll()
        request = None
        child = None
        try:
            if maintenance:
                request = lease.Lease(domains, maintenance=True)
                if not request.drain():
                    raise ValueError("unexpected writer")
            records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in domains]
            child = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(PROTOTYPE / "startup_probe.py"),
                                      str(checkout), json.dumps(records), mode, json.dumps(argv)],
                                     env={"PATH": os.defpath, "HOME": str(root), "HERMES_HOME": str(active)},
                                     cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True)
            value = read_result(child)
            if not maintenance:
                request = lease.Lease(domains, maintenance=True)
                value["retainedWriterBlocksDrain"] = not request.drain()
            child.stdin.write("release\n")
            child.stdin.flush()
            child.wait(timeout=5)
            if child.returncode != 0:
                raise ValueError("startup probe exit")
            if not maintenance:
                value["drainsAfterPrefixExit"] = request.drain()
            return value
        finally:
            if child is not None:
                if child.poll() is None:
                    child.kill()  # Only this test-owned subprocess, never a discovered PID.
                child.wait(timeout=5)
                child.stdin.close()
                child.stdout.close()
            if request is not None:
                request.close()


def inspect(root):
    values = sources(root)  # Verify every copied input before any source is executed.
    cases = 0
    for mode in ("module", "console-import", "script"):
        for argv, profile in ((["serve"], False), (["gateway", "run"], False),
                              (["cron", "tick"], False), (["-p", "named", "serve"], True)):
            early = experiment(values, "before-bootstrap", mode, argv, profile)
            late = experiment(values, "inside-main", mode, argv, profile)
            if (early != {"blocked": True, "bootstrapEntered": False, "prefixReached": False,
                          "admissionCalls": 1, "homeMutationAttempts": 0, "pmRecoveryInjected": True, "scratchCreated": False}
                    or late["blocked"] or not late["prefixReached"] or not late["scratchCreated"]
                    or late["admissionCalls"] != 0 or late["homeMutationAttempts"] == 0):
                raise ValueError("startup admission scope changed")
            cases += 1
    admitted = experiment(values, "before-bootstrap", "module", ["serve"], maintenance=False)
    if (admitted["blocked"] or not admitted["prefixReached"] or not admitted["scratchCreated"]
            or not admitted["retainedWriterBlocksDrain"] or not admitted["drainsAfterPrefixExit"]):
        raise ValueError("startup lease lifetime changed")
    return {"pairedEntryCases": cases, "earlyGuardBlocksBeforeBootstrap": True,
            "insideMainGuardStillWrites": True, "admittedPrefixRetainsWriterLease": True,
            "pmRecoveryInjected": True, "fullStartupVerified": False, "profileSelectionVerified": False,
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
                          "reason": "startup-admission-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": observations}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
