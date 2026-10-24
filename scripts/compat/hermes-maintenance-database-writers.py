#!/usr/bin/env python3
"""A fixed-candidate SessionDB nonparticipant counterexample, never maintenance authorization."""
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

HERE = Path(__file__).parent
COMMIT = "bf0203a794a07aac3ff764972ab9087baa4e4f4e"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


matrix = load("database_writer_matrix", HERE / "hermes-matrix.py")
lease = load("database_writer_lease", HERE / "upstream-maintenance-prototype/maintenance_lease.py")
worker = load("database_writer_source_guard", HERE.parents[1] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py")

# Tripwires for reviewed, fixed upstream code, not an OS sandbox for malicious native extensions.
# Only the temporary fixture may be mutated. The reviewed import-time version readers may
# run exact transport-denied Git commands against the verified public source, never a mutator.
AUDIT = '''
import os, pathlib, sys
_fixture_root = pathlib.Path(sys.argv[2]).resolve()
_source_root = pathlib.Path(sys.argv[1]).resolve()
_version_git = {
    ("git", "rev-parse", "HEAD"), ("git", "branch", "--show-current"),
    ("git", "log", "-1", "--format=%ct", "HEAD"),
    ("git", "--no-optional-locks", "status", "--porcelain", "-uno"),
    ("git", "tag", "--merged", "HEAD", "--list", "v[0-9]*"),
    ("git", "describe", "--tags", "--long", "--match", "v2[0-9][0-9][0-9].*", "HEAD"),
    ("git", "rev-parse", "--short=7", "HEAD"),
}
def _inside(value):
    if isinstance(value, int): return
    path = pathlib.Path(os.fsdecode(value)).resolve()
    if not path.is_relative_to(_fixture_root): raise ValueError("outside fixture mutation")
def _audit(event, args):
    if event == "subprocess.Popen":
        executable, argv, cwd, env = args
        if executable == "git" and tuple(argv) in _version_git and cwd == str(_source_root) and env is None: return
        raise ValueError("unexpected fixture subprocess")
    if event == "open" and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND): _inside(args[0])
    if event in ("os.mkdir", "os.remove", "os.rmdir", "os.chmod", "os.utime", "os.truncate"): _inside(args[0])
    if event in ("os.rename", "os.link", "os.symlink"):
        _inside(args[0]); _inside(args[1])
    if event == "sqlite3.connect" and args[0] != ":memory:": _inside(args[0])
    if event in ("os.system", "os.exec", "os.posix_spawn", "os.fork", "os.forkpty",
                 "os.kill", "os.killpg", "socket.connect", "socket.bind", "socket.getaddrinfo"):
        raise ValueError("unexpected fixture process or network operation")
sys.addaudithook(_audit)
'''

COOPERATING = '''
import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location("cooperating_writer",sys.argv[1])
lease=importlib.util.module_from_spec(spec);spec.loader.exec_module(lease)
root=Path(sys.argv[2]);source=Path(sys.argv[3])
domains=[lease.Domain(root/"control","home",root/"data"),lease.Domain(root/"control","checkout",source)]
try:
    held=lease.Lease(domains)
except BlockingIOError:
    print(json.dumps({"refused":True}))
else:
    held.close();print(json.dumps({"refused":False}))
'''


def environment(root):
    return {"PATH": os.defpath, "HOME": str(root), "HERMES_HOME": str(root / "data"),
            "XDG_CACHE_HOME": str(root / "cache"), "TMPDIR": str(root / "tmp"),
            "PYTHONDONTWRITEBYTECODE": "1", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_NO_LAZY_FETCH": "1", "GIT_ALLOW_PROTOCOL": "", "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_GRAFT_FILE": os.devnull, "GIT_OPTIONAL_LOCKS": "0", "GIT_CONFIG_COUNT": "4",
            "GIT_CONFIG_KEY_0": "core.fsmonitor", "GIT_CONFIG_VALUE_0": "false",
            "GIT_CONFIG_KEY_1": "core.untrackedCache", "GIT_CONFIG_VALUE_1": "false",
            "GIT_CONFIG_KEY_2": "core.hooksPath", "GIT_CONFIG_VALUE_2": os.devnull,
            "GIT_CONFIG_KEY_3": "credential.helper", "GIT_CONFIG_VALUE_3": ""}


def cooperating(root, source):
    result = subprocess.run([sys.executable, "-I", "-S", "-B", "-c", COOPERATING,
        str(HERE / "upstream-maintenance-prototype/maintenance_lease.py"), str(root), str(source)],
        env=environment(root), cwd=root, stdin=subprocess.DEVNULL, close_fds=True,
        capture_output=True, text=True, timeout=8, check=True)
    value = json.loads(result.stdout)
    if type(value) is not dict or set(value) != {"refused"} or type(value["refused"]) is not bool:
        raise ValueError("cooperating writer observation differs")
    return value["refused"]


def inspect(source):
    source = Path(source).resolve(strict=True)
    if source.is_relative_to(Path.home() / ".hermes"):
        raise ValueError("use an isolated fixed source snapshot")
    # Validate BEFORE enrollment or importing upstream code. Standard fully materialized partial
    # clones are admitted only after all tip objects/Python blobs pass the transport-denied guard.
    worker.verify_source(source, COMMIT, installation=True)
    with tempfile.TemporaryDirectory(prefix="hg199-database-writer-scope-") as directory:
        root = Path(directory).resolve()
        for name in ("control", "data", "cache", "tmp"):
            (root / name).mkdir(mode=0o700)
        domains = [lease.Domain(root / "control", "home", root / "data"),
                   lease.Domain(root / "control", "checkout", source)]
        for domain in domains:
            domain.enroll()  # Registry writes only; the supplied source is never modified.
        held = lease.Lease(domains, maintenance=True)
        try:
            if not held.drain() or not cooperating(root, source):
                raise ValueError("cooperating writer was not excluded")
            # No inherited authority/descriptions: this is a new ordinary upstream API process.
            result = subprocess.run([sys.executable, "-I", "-B", "-c", AUDIT + matrix.CHILD,
                str(source), str(root), "create"], env=environment(root), cwd=root,
                stdin=subprocess.DEVNULL, close_fds=True, capture_output=True, text=True, timeout=30, check=True)
            if json.loads(result.stdout) != {"stage": "create", "ok": True} or not cooperating(root, source):
                raise ValueError("nonparticipant or post-write custody observation differs")
            with sqlite3.connect("file:" + str(root / "data/state.db") + "?mode=ro", uri=True) as database:
                if database.execute("pragma quick_check").fetchone()[0] != "ok":
                    raise ValueError("synthetic database integrity differs")
                count = database.execute("select count(*) from messages where content in (?,?,?)",
                    ("before upgrade", "retained answer", "child prompt")).fetchone()[0]
                if count != 3:
                    raise ValueError("actual sample database writes not observed")
            worker.verify_source(source, COMMIT, installation=True)
        finally:
            held.close()
        if cooperating(root, source):
            raise ValueError("released no-intent fixture still blocks cooperating writer")
    return {"ok": True, "sourceCommit": COMMIT, "actualSessionDBProcessWriteSucceeded": True,
            "maintenanceHeldDuringWrite": True, "cooperativeProcessRefusedBeforeAndAfter": True,
            "syntheticMessagesObserved": 3, "durableIntentPublished": False, "officialUpdateLockTested": False,
            "readOnlyVersionGitProbesAllowed": True,
            "realHistoryUsed": False, "servicesStarted": False, "policyInstalled": False,
            "globalAdmissionProven": False, "certifiesUpgrade": False}


def main():
    parser = matrix.ContractArguments(description=__doc__)
    parser.add_argument("--candidate-root", type=Path, required=True)
    args = parser.parse_args()
    return inspect(args.candidate_root)


if __name__ == "__main__":
    try:
        value = main()
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, subprocess.SubprocessError, worker.Refused):
        value = {"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                 "summaryChinese": "无法验证隔离数据库写入的维护范围。",
                 "summaryEnglish": "Isolated database writer maintenance scope could not be verified.",
                 "globalAdmissionProven": False, "certifiesUpgrade": False}
    print(json.dumps(value, ensure_ascii=False))
    raise SystemExit(0 if value["ok"] else 1)
