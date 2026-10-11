"""Opt-in macOS PM-handoff/finish rehearsal, NOT an upgrade or service certification.

The caller supplies a marked disposable tool/cache seed. Journal/backup are injected fixtures:
no backup acceptance, GO service or user conversation is tested. Resume mode kills actual official
PM preparation after its handoff. Update mode also exercises the ordinary source move/child entry.
Every official process inherits a kernel deny-write/deny-signal/service-exec sandbox.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import uuid

COMMIT = "818c13be1dc4fd28987e1e881a9408224afd4535"
OLD = "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121"
WORKER = Path(__file__).resolve().parents[2] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py"
PREPARE = r'''
import json, os, pathlib, sys, time
cfg = json.loads(pathlib.Path(sys.argv[1]).read_text())
root = pathlib.Path(cfg['checkout']); run = pathlib.Path(cfg['runDirectory'])
sys.path.insert(0, str(root))
from hermes_cli import _update_takeover, update_receipt
from hermes_cli.update_lock import UpdateLock
spec = __import__('importlib.util', fromlist=['']).spec_from_file_location('go_worker', sys.argv[2])
go = __import__('importlib.util', fromlist=['']).module_from_spec(spec); spec.loader.exec_module(go)
with UpdateLock(install_root=root) as lock:
    assert lock.acquired
    previous = {'update_id': cfg['upstreamUpdateID'], 'correlation_id': cfg['runID'],
                'pre_update': {'sha': cfg['sourceCommit'], 'version': cfg['preUpdateVersion']}}
    request = {'root': str(root), 'update_id': cfg['upstreamUpdateID'], 'receipt': previous,
               'assume_yes': True, 'gateway_mode': False, 'desktop': False,
               'pre_update_version': cfg['preUpdateVersion'], 'plan': cfg['plan']}
    go.durable_json(run / 'official-context.json', request)
    request, python, env = go.prepare_handoff(cfg, root, run)
    print('HG199_HANDOFF_READY', flush=True)
    while True: time.sleep(1)
'''


def private_seed(text):
    path = Path(text).resolve()
    if (not str(path).startswith("/private/tmp/hg199-") or path.is_relative_to(Path.home())
            or not (path / ".hg199-isolated-rehearsal").is_file()
            or path.stat().st_uid != os.getuid()):
        raise ValueError("a marked disposable macOS seed is required")
    return path


def event(child, timeout=30):
    if not select.select([child.stdout], [], [], timeout)[0]:
        raise AssertionError("worker event timeout")
    raw = child.stdout.readline(4097)
    assert raw and len(raw) <= 4096
    return json.loads(raw)


def main(source, seed, mode):
    if sys.platform != "darwin" or sys.version_info < (3, 11):
        raise ValueError("macOS and Python 3.11+ required")
    source = source.resolve(); seed = private_seed(seed)
    assert not source.is_relative_to(Path.home() / ".hermes")
    spec = importlib.util.spec_from_file_location("go_worker", WORKER)
    go = importlib.util.module_from_spec(spec); spec.loader.exec_module(go)
    go.verify_source(source, COMMIT)
    # Retain evidence for inspection. This is private temporary fixture storage only.
    base = Path(tempfile.mkdtemp(prefix="hg199-finish-resume-", dir="/private/tmp")).resolve()
    user = base / "home"; home = user / ".hermes"; root = home / "hermes-agent"; run = base / "run"
    for directory in (user, home, run, base / "tmp"):
        directory.mkdir(mode=0o700)
    (base / ".hg199-isolated-rehearsal").write_text("synthetic PM handoff only\n")
    run_id, update_id = str(uuid.uuid4()), str(uuid.uuid4())
    env = {"HOME": str(user), "HERMES_HOME": str(home), "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
           "TMPDIR": str(base / "tmp"), "XDG_CACHE_HOME": str(base / "cache"),
           "UV_CACHE_DIR": str(base / "cache/uv"), "GIT_CONFIG_GLOBAL": "/dev/null",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": "/dev/null",
           "NO_COLOR": "1", "NONINTERACTIVE": "1", "HERMES_UPDATE_UI_ACTIVE": "1",
           "HERMES_UPDATE_CORRELATION_ID": run_id}
    subprocess.run(["/usr/bin/git", "-c", "core.hooksPath=/dev/null", "clone", "--shared", "-q", str(source), str(root)],
                   env=env, check=True, stdin=subprocess.DEVNULL)
    if mode == "update":
        subprocess.run(["/usr/bin/git", "-c", "core.hooksPath=/dev/null", "-C", str(root), "checkout", "-q", "--detach", OLD],
                       env=env, check=True, stdin=subprocess.DEVNULL)
    for old, new in ((seed / "home/.hermes/tools", home / "tools"), (seed / "cache", base / "cache")):
        assert old.resolve() == old and old.is_dir()
        subprocess.run(["/bin/cp", "-cR", str(old), str(new)], env=env, check=True)
    profile = base / "sandbox.sb"
    profile.write_text('(version 1)\n(allow default)\n(deny signal)\n'
        '(deny process-exec (literal "/bin/launchctl") (literal "/usr/bin/launchctl") '
        '(literal "/usr/bin/osascript") (literal "/usr/bin/sudo") (literal "/opt/homebrew/bin/brew"))\n'
        '(deny file-write* (require-all (require-not (subpath ' + json.dumps(str(base)) + ')) '
        '(require-not (literal "/dev/null"))))\n')
    plan = {"install_method": "git", "updatable_in_place": True, "update_mechanism": "hermes update",
            "expected_sha": OLD, "expected_version": "0.21.3", "profiles": ["default"], "runtimes": []}
    cfg = {"schema": 1, "runID": run_id, "upstreamUpdateID": update_id, "sourceRoot": str(source),
           "checkout": str(root), "hermesHome": str(home), "runDirectory": str(run),
           "journal": str(run / "journal.json"), "sourceCommit": OLD, "targetCommit": COMMIT,
           "targetVersion": "0.21.6", "preUpdateVersion": "0.21.3", "plan": plan}
    configuration = run / "configuration.json"; go.durable_json(configuration, cfg)
    go.durable_json(Path(cfg["journal"]), {"schemaVersion": 1, "runID": run_id, "upstreamUpdateID": update_id,
        "checkout": str(root), "hermesHome": str(home), "sourceCommit": OLD, "phase": "updating",
        "target": {"version": "0.21.6", "commit": COMMIT},
        "backup": {"runID": run_id, "manifestSHA256": "c" * 64}})  # injected, not accepted backup evidence
    driver = base / "prepare.py"; driver.write_text(PREPARE)
    def sandbox(script, *arguments):
        return ["/usr/bin/sandbox-exec", "-f", str(profile), sys.executable, "-I", "-S", "-B", str(script), *map(str, arguments)]
    print(json.dumps({"fixture": str(base), "mode": mode, "scope": "synthetic journal/backup; no genuine old venv, history or service acceptance"}), flush=True)
    context = run / "official-context.json"; before = None
    if mode == "resume":
        with (base / "prepare.log").open("wb") as log:
            preparer = subprocess.Popen(sandbox(driver, configuration, WORKER), env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
            try:
                until = time.monotonic() + 600
                while b"HG199_HANDOFF_READY" not in (base / "prepare.log").read_bytes():
                    assert preparer.poll() is None and time.monotonic() < until, "official prepare failed/timed out; inspect private log"
                    time.sleep(.2)
                preparer.kill(); assert preparer.wait(timeout=10) != 0
            finally:
                if preparer.poll() is None: preparer.kill(); preparer.wait(timeout=10)
        before = context.read_bytes()
    def command(name):
        child = subprocess.Popen(sandbox(WORKER, configuration), env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            assert event(child)["event"] == "locked"
            child.stdin.write((json.dumps({"command": name, "runID": run_id}) + "\n").encode()); child.stdin.flush()
            value = event(child, 600)
            while value["event"] == "updating": value = event(child, 600)
            (base / (name + "-event.json")).write_text(json.dumps(value))
            if value["event"] == "finished":
                child.stdin.write((json.dumps({"command": "release", "runID": run_id}) + "\n").encode()); child.stdin.flush()
            child.wait(timeout=10)
            return value
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=10)
            child.stdin.close(); child.stdout.close()
    assert command("resume-finish" if mode == "resume" else "update") == {"schema": 1, "event": "finished", "exitCode": 0}
    if mode == "update": before = context.read_bytes()
    assert context.read_bytes() == before, "original handoff was replaced"
    assert subprocess.check_output(["/usr/bin/git", "-C", str(root), "rev-parse", "HEAD"], env=env).decode().strip() == COMMIT
    archived = list((home / "logs/update_receipts").glob("update_*_" + update_id + ".json"))
    assert len(archived) == 1
    receipt = go.private_json(archived[0]); saved_receipt = archived[0].read_bytes()
    assert receipt["update_id"] == update_id and receipt["correlation_id"] == run_id
    assert receipt["pm_sync_outcome"] == "ok" and receipt["post_update"]["sha"] == COMMIT
    assert receipt["post_update"]["version"] == "0.21.6" and receipt["outcome"] == "success"
    assert receipt["gateway_restart"]["incomplete"] and any(row["step"] == "gateway_restart" for row in receipt["followups"])
    try:
        go.validate_finish_handoff(cfg, root, home, run)
    except go.Refused as error:
        assert str(error) == "finish_checkpoint_not_ready"
    else:
        raise AssertionError("finishing checkpoint allowed a second effect")
    refused = command("resume-finish")
    assert refused["event"] == "refused", "terminal service debt was replayed"
    assert context.read_bytes() == before and archived[0].read_bytes() == saved_receipt
    print(json.dumps({"fixture": str(base), "mode": mode,
        "official_prepare_killed": "passed" if mode == "resume" else "not exercised",
        "official_finish_resumed": "passed" if mode == "resume" else "not exercised",
        "normal_update_entry": "passed" if mode == "update" else "not exercised",
        "original_handoff_and_ids": "passed", "terminal_debt_not_replayed": "passed",
        "checkpoint_replay_guard": "passed", "worker_repeat_refusal": refused.get("reason"),
        "go_completion": "blocked: sandbox-denied service recovery", "backup": "injected fixture; not verified"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--seed-root", required=True)
    parser.add_argument("--mode", choices=("resume", "update"), default="resume")
    arguments = parser.parse_args()
    main(arguments.candidate_root, arguments.seed_root, arguments.mode)
