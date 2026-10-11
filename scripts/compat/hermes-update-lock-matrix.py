"""Actual upstream POSIX lock/child-custody tests in disposable homes, never a live runtime."""
import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import uuid

DRIVER = r'''
import hashlib, importlib.util, json, os, pathlib, sys, time
sys.path.insert(0, sys.argv[1])
from hermes_cli.update_lock import UpdateLock
root, home = map(pathlib.Path, sys.argv[2:4])
lock = UpdateLock(path=home / '.hermes-update-in-progress', install_root=root)
if not lock.acquire():
    print(json.dumps({'acquired': False}), flush=True)
    raise SystemExit(2)
try:
    if sys.argv[4] == 'child':
        from hermes_cli.update_custody import popen_post_commit
        child = popen_post_commit([sys.executable, '-I', '-S', '-B', __file__, sys.argv[1], str(root), str(home), 'hold'],
            label='isolated custody test', stdin=__import__('subprocess').DEVNULL,
            stdout=__import__('subprocess').DEVNULL, stderr=__import__('subprocess').DEVNULL,
            start_new_session=True)
        until = time.monotonic() + 10
        while not (home / 'child-ready').exists():
            if child.poll() is not None or time.monotonic() >= until: raise RuntimeError('child did not join')
            time.sleep(.02)
        print(json.dumps({'acquired': True, 'child': child.pid}), flush=True)
        sys.stdin.read(1)
        child.wait(timeout=15)
    elif sys.argv[4] == 'hold':
        (home / 'child-ready').write_text(str(os.getpid()))
        until = time.monotonic() + 15
        while not (home / 'stop').exists() and time.monotonic() < until: time.sleep(.02)
    elif sys.argv[4] == 'obligation':
        spec = importlib.util.spec_from_file_location('go_upgrade_worker', sys.argv[5])
        worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
        from hermes_cli import update_host_obligation as official
        original = official.__file__
        record = worker.arm_installation_obligation(root, home, 'a' * 40, [])
        key = hashlib.sha256(os.path.normcase(str(root.resolve())).encode()).hexdigest()[:16]
        assert record.name == 'host-update-restart-' + key + '.json', 'debt used the asset snapshot install key'
        assert official.__file__ == original, 'helper origin was not restored'
        assert json.loads(record.read_text())['expected_sha'] == 'a' * 40
        print(json.dumps({'acquired': True, 'obligation': True}), flush=True)
    else:
        print(json.dumps({'acquired': True}), flush=True)
finally:
    lock.release()
'''


def environment(user_home, hermes_home, run_id):
    return {"HOME": str(user_home), "HERMES_HOME": str(hermes_home), "PATH": "/usr/bin:/bin",
            "LANG": "C.UTF-8", "TMPDIR": str(user_home), "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1", "HERMES_UPDATE_CORRELATION_ID": run_id}


def event(process, timeout=20):
    if not select.select([process.stdout], [], [], timeout)[0]:
        raise AssertionError("control event timed out")
    line = process.stdout.readline(4097)
    assert line and len(line) <= 4096, "invalid control event"
    return json.loads(line)


def run(source):
    source = source.resolve()
    assert source.is_dir() and not source.is_relative_to(Path.home() / ".hermes"), "refuse live source"
    commit = subprocess.check_output(["/usr/bin/git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    worker = Path(__file__).resolve().parents[2] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py"
    with tempfile.TemporaryDirectory(prefix="hg199-custody-") as folder:
        base = Path(folder).resolve()
        user = base / "user"; home = user / ".hermes"; root = home / "hermes-agent"; run_dir = base / "run"
        for path in (user, home, root, root / ".git", run_dir): path.mkdir(mode=0o700)
        run_id = str(uuid.uuid4())
        env = environment(user, home, run_id)
        def fixture_git(*arguments):
            return subprocess.run(["/usr/bin/git", "-c", "core.hooksPath=/dev/null",
                "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-C", str(root), *arguments],
                env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=10, check=True)
        fixture_git("init", "-q")
        (root / "fixture.txt").write_text("isolated checkout lock fixture\n")
        fixture_git("add", "fixture.txt"); fixture_git("commit", "-qm", "fixture")
        linked = base / "linked-checkout"
        fixture_git("worktree", "add", "--detach", str(linked), "HEAD")
        driver = base / "driver.py"; driver.write_text(DRIVER)
        config = {"schema": 1, "runID": run_id, "upstreamUpdateID": str(uuid.uuid4()),
                  "sourceRoot": str(source), "checkout": str(root), "hermesHome": str(home),
                  "runDirectory": str(run_dir), "journal": str(base / "journal.json"),
                  "sourceCommit": "a" * 40, "targetCommit": commit, "targetVersion": "0.21.6", "preUpdateVersion": "0.21.3", "plan": {}}
        config_file = run_dir / "configuration.json"
        config_file.write_text(json.dumps(config)); config_file.chmod(0o600)

        def probe(probe_home=home, probe_root=root):
            completed = subprocess.run([sys.executable, "-I", "-S", "-B", str(driver), str(source), str(probe_root), str(probe_home), "probe"],
                env=environment(user, probe_home, run_id), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
            return json.loads(completed.stdout)["acquired"]

        # Run the packaged GO bridge itself against the actual official source; no PM/data changes.
        bridge = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(worker), str(config_file)], env=env,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            assert event(bridge) == {"schema": 1, "event": "locked"}
            assert (root / ".git/hermes-update.lock").exists(), "bridge did not lock the real Git common dir"
            assert not probe(), "second official updater entered under the GO bridge"
            other_home = base / "other-home"; other_home.mkdir(mode=0o700)
            assert not probe(other_home), "another home escaped the checkout kernel lock"
            assert not probe(other_home, linked), "linked worktree escaped the common-dir kernel lock"
            bridge.stdin.write((json.dumps({"command": "release", "runID": run_id}) + "\n").encode()); bridge.stdin.flush()
            assert bridge.wait(timeout=5) == 0
            assert probe() and not (home / ".hermes-update-in-progress").exists()
        finally:
            if bridge.poll() is None: bridge.kill(); bridge.wait(timeout=5)
            bridge.stdin.close(); bridge.stdout.close()

        obligation = subprocess.run([sys.executable, '-I', '-S', '-B', str(driver), str(source),
            str(root), str(home), 'obligation', str(worker)], env=env, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=10, check=True)
        assert json.loads(obligation.stdout)['obligation']

        # Kill an actual lock owner while its post-commit child runs. The child's inherited open
        # file description and delegated marker must fence the checkout until it exits.
        owner = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(driver), str(source), str(root), str(home), "child"],
                                 env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            ready = event(owner)
            assert ready["acquired"] and ready["child"] > 0
            owner.kill(); owner.wait(timeout=5)
            assert not probe(), "killed owner released the surviving child's checkout custody"
            (home / "stop").touch()
            until = time.monotonic() + 10
            while not probe():
                assert time.monotonic() < until, "child left the checkout locked after exit"
                time.sleep(.02)
            assert not (home / ".hermes-update-in-progress").exists()
        finally:
            (home / "stop").touch()
            if owner.poll() is None: owner.kill(); owner.wait(timeout=5)
            owner.stdin.close(); owner.stdout.close()
    print(json.dumps({"upstream_commit": commit, "platform": sys.platform,
                      "bridge_exclusion": "passed", "cross_home_exclusion": "passed",
                      "linked_worktree_exclusion": "passed",
                      "pre_move_installation_obligation": "passed",
                      "parent_death_child_custody": "passed", "release_and_reclaim": "passed"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-root", type=Path, required=True)
    run(parser.parse_args().candidate_root)
