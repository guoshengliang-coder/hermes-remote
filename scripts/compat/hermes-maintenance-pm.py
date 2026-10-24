#!/usr/bin/env python3
"""Opt-in isolated PM generation observation; never production completion."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).parent
PROTOTYPE = HERE / "upstream-maintenance-prototype"
COMMIT = "818c13be1dc4fd28987e1e881a9408224afd4535"
MARKER = b"HG199 isolated PM generation observation v1\n"
WORKER = HERE.parents[1] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def environment(root):
    return {"PATH": os.defpath, "HOME": str(root / "user"), "HERMES_HOME": str(root / "data"),
            "TMPDIR": str(root / "tmp"), "XDG_CACHE_HOME": str(root / "cache"),
            "UV_CACHE_DIR": str(root / "cache/uv"), "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": "/dev/null",
            "NO_COLOR": "1", "NONINTERACTIVE": "1", "PYTHONDONTWRITEBYTECODE": "1"}


def held(root, enroll=False):
    lease = load("pm_fixture_lease", PROTOTYPE / "maintenance_lease.py")
    domains = [lease.Domain(root / "control", kind, root / name) for kind, name in (("home", "data"), ("checkout", "checkout"))]
    if enroll:
        for domain in domains:
            domain.enroll()
    value = lease.Lease(domains, maintenance=True)
    if not value.drain():
        value.close()
        raise ValueError("isolated PM scope busy")
    return value


def profile_file(root, name, data):
    path = root / name
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1
                or info.st_size != len(data) or path.read_bytes() != data):
            raise ValueError("fixture sandbox profile changed")
    else:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
    return path


def sandbox(root, network=False):
    # Opt-in real PM execution is macOS-only; unit/source checks remain portable.
    if sys.platform != "darwin":
        raise ValueError("isolated real PM run requires macOS")
    data = ('(version 1)\n(allow default)\n(deny signal)\n'
        '(deny process-exec (literal "/bin/launchctl") (literal "/usr/bin/launchctl") '
        '(literal "/usr/bin/osascript") (literal "/usr/bin/sudo") (literal "/opt/homebrew/bin/brew"))\n'
        '(deny file-write* (require-all (require-not (subpath ' + json.dumps(str(root)) + ')) '
        '(require-not (literal "/dev/null"))))\n' + ('' if network else '(deny network*)\n')).encode()
    profile = profile_file(root, "provision.sb" if network else "observe.sb", data)
    return ["/usr/bin/sandbox-exec", "-f", str(profile)]


def provision(source):
    if sys.platform != "darwin":
        raise ValueError("isolated real PM run requires macOS")
    if source.is_relative_to(Path.home() / ".hermes"):
        raise ValueError("live installation cannot be an experiment source")
    # Clone only a verified pinned Git input. All state/tools/cache are new.
    go = load("pm_source_worker", WORKER)
    go.verify_source(source, COMMIT, installation=True)
    root = Path(tempfile.mkdtemp(prefix="hg199-pm-generation-", dir="/private/tmp")).resolve()
    for name in ("user", "data", "control", "tmp"):
        (root / name).mkdir(mode=0o700)
    marker = root / ".hg199-isolated-pm-generation"
    marker.write_bytes(MARKER)
    marker.chmod(0o600)
    env = environment(root)
    subprocess.run(["/usr/bin/git", "-c", "core.hooksPath=/dev/null", "clone", "--shared", "--quiet",
                    str(source), str(root / "checkout")], env=env, stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=60)
    go.verify_source(root / "checkout", COMMIT)
    value = held(root, enroll=True)
    try:
        intent = load("pm_fixture_intent", PROTOTYPE / "durable_intent.py")
        bootstrap = load("pm_fixture_bootstrap", HERE / "hermes-maintenance-bootstrap.py")
        records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in value.domains]
        context = intent.binding.context(str(uuid.uuid4()), COMMIT, root / "checkout", root / "data",
                                         "hermes_cli.main", records, list(bootstrap.BOOTSTRAP_SHA256))
        intent.begin(value, context)  # Remains active even if PM/observer fails or every process dies.
        # No official updater/finish, service, existing venv or conversation runs.
        code = 'import sys; sys.path.insert(0, sys.argv[1]); from pm.client import sync_venv; sync_venv([], explicit=True)'
        log = root / "provision.log"
        fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            subprocess.run([*sandbox(root, network=True), sys.executable, "-I", "-S", "-B", "-c", code,
                            str(root / "checkout")], env=env, cwd=root, stdin=subprocess.DEVNULL,
                           stdout=output, stderr=output, check=True, timeout=1200)
    finally:
        value.close()
    return root


def drift_cases(observer, value, prefix):
    """Explicit fault injection in this marked fixture ONLY; restore our own bytes."""
    root = observer.scope(value)
    initial = observer.observe(value, command_prefix=prefix)
    probe = Path(initial["selected"]["site"]) / (".hg199-probe-" + uuid.uuid4().hex)
    fd = os.open(probe, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    os.write(fd, b"before\n")
    os.close(fd)
    refused = 0
    try:
        for path, change in ((probe, b"after!\n"), (Path(initial["selected"]["facts"]), None),
                             (root / "checkout/uv.lock", None)):
            before = observer.observe(value, command_prefix=prefix)
            original, info = path.read_bytes(), path.stat()
            try:
                path.write_bytes(change if change is not None else original + b"\n")
                os.utime(path, ns=(info.st_atime_ns, info.st_mtime_ns))
                try:
                    observer.revalidate(value, before, command_prefix=prefix)
                except ValueError:
                    refused += 1
                else:
                    raise ValueError("PM observation ignored changed contents")
            finally:
                path.write_bytes(original)
                os.utime(path, ns=(info.st_atime_ns, info.st_mtime_ns))
            restored = observer.observe(value, command_prefix=prefix)
            observer.revalidate(value, restored, command_prefix=prefix)
    finally:
        probe.unlink()
    return refused


def inspect(root, faults=False):
    observer = load("pm_generation_observer", PROTOTYPE / "pm_generation.py")
    observer.fixture(root)  # Refuse scope BEFORE opening control objects.
    go = load("pm_source_worker", WORKER)
    go.verify_source(root / "checkout", COMMIT, installation=True)
    value = held(root)
    try:
        prefix = sandbox(root)
        refused = drift_cases(observer, value, prefix) if faults else 0
        observed = observer.observe(value, command_prefix=prefix)
        observer.revalidate(value, observed, command_prefix=prefix)
        result = {"selectedPMGenerationObserved": True, "realSelectedInterpreterRan": True,
                "selectedPackagingImportObserved": True, "selectionAndContentsRechecked": True,
                "activationInputsCurrent": observed["activationInputsCurrent"],
                "runtimeVersion": observed["runtime"]["version"], "packagingVersion": observed["runtime"]["packaging"],
                "injectedContentDriftRefusals": refused,
                "environmentEntries": observed["environmentEntries"], "runtimeEntries": observed["runtimeEntries"],
                "observedBytes": observed["observedBytes"], "dependencyClosureCertified": False,
                "maintenanceTerminalPublished": False, "productionCompletionVerified": False,
                "durableHandoffVerified": False, "fullStartupVerified": False,
                "globalAdmissionProven": False, "certifiesUpgrade": False}
    finally:
        value.close()
    # Observe real admission after all inspector custody is released.
    lease = load("pm_admission_observation", PROTOTYPE / "maintenance_lease.py")
    try:
        writer = lease.Lease([lease.Domain(root / "control", k, root / p) for k, p in (("home", "data"), ("checkout", "checkout"))])
    except lease.Pending:
        result["ordinaryWriterBlocked"] = True
    else:
        writer.close()
        result["ordinaryWriterBlocked"] = False
    return result


def selector_matrix(source):
    """Real pinned selector + synthetic records. Never execute a fake Python."""
    observer = load("pm_generation_selector", PROTOTYPE / "pm_generation.py")
    observer.source(source)
    with tempfile.TemporaryDirectory(prefix="hg199-pm-generation-") as directory:
        root = Path(directory).resolve()
        for name in ("checkout", "user", "data", "tmp"):
            (root / name).mkdir(mode=0o700)
        for name in observer.PINNED:
            target = root / "checkout" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)
        key = hashlib.sha256(str(root / "checkout").encode()).hexdigest()[:16]
        state = root / "data/installs" / key
        venv = state / "environments/gen-fixture/venv"
        (venv / "lib/python3.11/site-packages").mkdir(parents=True)
        (venv / "bin").mkdir()
        binary = root / "data/tools/python"
        binary.parent.mkdir()
        binary.write_bytes(b"NOT AN EXECUTABLE; selector fixture only\n")
        (venv / "bin/python").symlink_to(binary)
        cfg = venv / "pyvenv.cfg"
        cfg.write_text("version = 3.11.15\n")
        facts = state / "facts.json"
        record = {"packages": {"venv": {"environment": str(venv)}}}
        facts.write_text(json.dumps(record))
        expected = observer.selection(root, ())
        if expected["environment"] != str(venv):
            raise ValueError("pinned PM selector mismatch")
        refused = 0
        for fault in ("missing", "outside", "invalid"):
            if fault == "missing":
                cfg.unlink()
            elif fault == "outside":
                facts.write_text(json.dumps({"packages": {"venv": {"environment": str(root / "user")}}}))
            else:
                facts.write_text('{"packages": {"venv": {"environment": 1}}}')
            try:
                observer.selection(root, ())
            except (ValueError, OSError, subprocess.SubprocessError):
                refused += 1
            else:
                raise ValueError("pinned PM selector accepted invalid fixture")
            cfg.write_text("version = 3.11.15\n")
            facts.write_text(json.dumps(record))
        return {"pinnedSelectionInputs": 3, "syntheticSelectedCase": 1, "syntheticRefusedCases": refused,
                "realPMProvisioned": False, "realSelectedInterpreterRan": False, "certifiesUpgrade": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--provision", action="store_true")
    mode.add_argument("--fixture-root", type=Path)
    mode.add_argument("--audit-source", type=Path)
    mode.add_argument("--selector-matrix", type=Path)
    parser.add_argument("--candidate-root", type=Path)
    parser.add_argument("--fault-matrix", action="store_true", help="tamper/restore ONLY the marked disposable fixture")
    args = parser.parse_args()
    try:
        if args.selector_matrix:
            result = selector_matrix(args.selector_matrix.resolve())
            root = None
        elif args.audit_source:
            observer = load("pm_generation_observer", PROTOTYPE / "pm_generation.py")
            observer.source(args.audit_source.resolve())
            result = {"pinnedSelectionInputs": 3, "realPMProvisioned": False, "certifiesUpgrade": False}
            root = None
        else:
            if args.provision and args.candidate_root is None:
                raise ValueError("pinned source required")
            root = provision(args.candidate_root.resolve()) if args.provision else args.fixture_root.resolve()
            result = inspect(root, faults=args.fault_matrix)
            if args.provision and not result["ordinaryWriterBlocked"]:
                raise ValueError("provisioned fixture lost persistent admission blocking")
    except (ValueError, OSError, RuntimeError, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "pm-generation-observation-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, **({"fixture": str(root)} if root is not None else {}), "observations": result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
