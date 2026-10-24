"""Real synthetic Git/locks/journals + synthetic PM/runtime; never a real installation."""
import copy
import contextlib
import fcntl
import hashlib
import importlib.util
import json
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


HERE = Path(__file__).parent
fixtures = load("joint_git_test_fixture", HERE / "test_hermes_maintenance_source.py")
pm_fixtures = load("joint_pm_test_fixture", HERE / "test_hermes_maintenance_pm.py")
joint = load("joint_test_module", HERE / "upstream-maintenance-prototype/source_pm.py")

SYNTHETIC_SYNC = r'''
import hashlib,json,os,sys
from pathlib import Path
checkout=Path(sys.argv[1]);root=checkout.parent
state=root/'data/installs'/hashlib.sha256(str(checkout).encode()).hexdigest()[:16]
venv=state/'environments/gen-test/venv';base=root/'data/tools/python-runtime'
site=venv/'lib/python3.11/site-packages'
(site/'packaging').mkdir(parents=True);(site/'packaging/version.py').write_text('# synthetic package\n')
(base/'bin').mkdir(parents=True);(base/'bin/python').write_bytes(b'NOT EXECUTED: synthetic interpreter\n')
(base/'bin/python').chmod(0o700);(venv/'bin').mkdir();(venv/'bin/python').symlink_to(base/'bin/python')
(venv/'pyvenv.cfg').write_text('version = 3.11.15\n')
(state/'facts.json').write_text(json.dumps({'packages':{'venv':{'environment':str(venv)}}}))
'''


class JointSourcePMTest(unittest.TestCase):
    def setUp(self):
        case = fixtures.SourceTransitionTest()
        case.setUp()
        self.addCleanup(case.doCleanups)
        self.candidate = case.new
        values = {"pm/__init__.py": b'"""Synthetic PM selector."""\n',
                  "pm/environments.py": pm_fixtures.SELECTOR.encode(),
                  "hermes_constants.py": b'"""Synthetic target constants."""\n',
                  "pyproject.toml": b"# synthetic lock input\n", "uv.lock": b"# synthetic lock input\n",
                  "pm/lock.json": json.dumps({"packages": {"python": {"version": "3.11.15+synthetic"}}}).encode()}
        for name, data in values.items():
            path = self.candidate / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_bytes(data)
        case.git("add", ".", cwd=self.candidate)
        case.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "synthetic locked PM target", cwd=self.candidate)
        after = case.git("rev-parse", "HEAD", cwd=self.candidate)
        self.fixture = fixtures.driver.Fixture(case.old, self.candidate, case.before, after)
        self.addCleanup(shutil.rmtree, self.fixture.root)
        self.root = self.fixture.root
        marker = self.root / ".hg199-isolated-source-pm"
        marker.write_bytes(joint.MARKER)
        marker.chmod(0o600)
        self.held = self.fixture.held()
        self.addCleanup(self.held.close)
        lock = self.root / "checkout/.git/hermes-update.lock"
        self.official = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
        self.addCleanup(os.close, self.official)
        fcntl.flock(self.official, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.plan = {"schema": 1, "policy": joint.POLICY, "source": self.fixture.plan,
                     "pmInputs": joint.binding.snapshot(self.candidate, list(joint.PM_INPUTS)), "pythonVersion": [3, 11, 15]}
        self.args = (*self.fixture.args[:3], joint.binding.digest(self.plan))
        pins = {name: hashlib.sha256(values[name]).hexdigest() for name in joint.pm.PINNED}
        p = patch.dict(joint.pm.PINNED, pins, clear=True)
        p.start()
        self.addCleanup(p.stop)
        p = patch.object(joint, "PM_CODE", SYNTHETIC_SYNC)
        p.start()
        self.addCleanup(p.stop)
        original = joint.pm.child
        self.runtime_version = [3, 11, 15]

        def child(root, executable, code, *args, **kwargs):
            if code != joint.pm.RUNTIME:
                return original(root, executable, code, *args, **kwargs)
            state = root / "data/installs" / hashlib.sha256(str(root / "checkout").encode()).hexdigest()[:16]
            venv = state / "environments/gen-test/venv"
            return {"executable": str(venv / "bin/python"), "base": str(root / "data/tools/python-runtime"),
                    "version": self.runtime_version, "packaging": "test-only",
                    "module": str(venv / "lib/python3.11/site-packages/packaging/version.py"),
                    "ssl": "synthetic", "sqlite": "synthetic",
                    "hash": hashlib.sha256(b"HG199 runtime probe").hexdigest()}
        p = patch.object(joint.pm, "child", side_effect=child)
        p.start()
        self.addCleanup(p.stop)

    def prepare_move(self):
        joint.prepare(self.held, self.plan, *self.args, official_fd=self.official)
        joint.move(self.held, self.plan, *self.args, official_fd=self.official)

    def sync(self):
        return joint.sync(self.held, self.plan, *self.args, official_fd=self.official,
                          environment=fixtures.driver.cli.driver.environment(self.root), command_prefix=(), observe_prefix=())

    def verify(self, digest):
        return joint.verify(self.held, self.plan, *self.args, official_fd=self.official,
                            generation_sha=digest, observe_prefix=())

    def test_joint_transaction_preserves_original_intent_and_leaves_admission_closed(self):
        original = joint.source.slots(self.held)
        inodes = [d._path("intent").stat().st_ino for d in self.held.domains]
        self.prepare_move()
        digest = self.sync()
        observed = self.verify(digest)
        self.assertTrue(observed["jointSourcePMPreparedObserved"])
        self.assertFalse(observed["activationInputsCurrent"])
        for key in ("updaterRecoveryVerified", "productionCompletionVerified", "durableHandoffVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(observed[key])
        history = joint.source.slots(self.held)
        self.assertTrue(all(a.startswith(b) for a, b in zip(history, original)))
        self.assertEqual([d._path("intent").stat().st_ino for d in self.held.domains], inodes)
        self.assertEqual(len(history[0].splitlines()), 5)
        self.held.close()
        self.fixture.blocked()
        self.held = self.fixture.held()
        self.addCleanup(self.held.close)
        self.assertTrue(self.verify(digest)["generationRechecked"])
        self.assertEqual(joint.source.slots(self.held), history)

    def test_future_pm_inputs_and_python_version_must_match_real_target_objects(self):
        original = joint.source.slots(self.held)
        for key, value in (("pythonVersion", [3, 14, 7]), ("pmInputs", {**self.plan["pmInputs"], "uv.lock": "0" * 64})):
            plan = copy.deepcopy(self.plan)
            plan[key] = value
            with self.assertRaises(ValueError):
                joint.prepare(self.held, plan, *self.args[:3], joint.binding.digest(plan), official_fd=self.official)
            self.assertEqual(joint.source.slots(self.held), original)

    def test_partial_plan_sync_failure_never_moves_source_or_runs_pm(self):
        def stop(point, index):
            if index == 0:
                raise OSError("injected first-slot interruption")
        with self.assertRaises(OSError):
            joint.prepare(self.held, self.plan, *self.args, official_fd=self.official, checkpoint=stop)
        history = joint.source.slots(self.held)
        with self.assertRaises(ValueError):
            joint.move(self.held, self.plan, *self.args, official_fd=self.official)
        self.assertEqual(joint.source.slots(self.held), history)
        self.assertFalse((self.root / "pm-sync.log").exists())

    def test_failed_pm_exit_keeps_started_intent_and_refuses_automatic_replay(self):
        self.prepare_move()
        with patch.object(joint, "PM_CODE", "raise SystemExit(7)"):
            with self.assertRaises(subprocess.CalledProcessError):
                self.sync()
        history = joint.source.slots(self.held)
        self.assertEqual(len(history[0].splitlines()), 4)
        with patch.object(joint.pm, "_observe_root", side_effect=AssertionError("no runtime for unknown sync")):
            with self.assertRaises(ValueError):
                self.verify("0" * 64)
        with self.assertRaises(ValueError):
            self.sync()
        self.assertEqual(joint.source.slots(self.held), history)

    def test_generation_or_actual_dependency_drift_refuses_fresh_verification(self):
        self.prepare_move()
        digest = self.sync()
        with self.assertRaises(ValueError):
            self.verify("0" * 64)
        package = next((self.root / "data/installs").rglob("packaging/version.py"))
        stamp = package.stat()
        package.write_bytes(package.read_bytes() + b"# drift\n")
        os.utime(package, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaises(ValueError):
            self.verify(digest)

    def test_changed_facts_bytes_cannot_reuse_prepared_generation_checkpoint(self):
        self.prepare_move()
        digest = self.sync()
        facts = next((self.root / "data/installs").rglob("facts.json"))
        stamp = facts.stat()
        facts.write_bytes(facts.read_bytes() + b"\n")
        os.utime(facts, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        with self.assertRaises(ValueError):
            self.verify(digest)

    def test_foreign_or_readonly_official_fd_refuses_before_plan_append(self):
        original = joint.source.slots(self.held)
        path = self.root / "foreign"
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            readonly = os.open(self.root / "checkout/.git/hermes-update.lock", os.O_RDONLY)
            self.addCleanup(os.close, readonly)
            for wrong in (None, True, fd, readonly):
                with self.assertRaises((ValueError, TypeError)):
                    joint.prepare(self.held, self.plan, *self.args, official_fd=wrong)
        finally:
            os.close(fd)
        self.assertEqual(joint.source.slots(self.held), original)

    def test_actual_runtime_version_must_match_presync_locked_plan(self):
        self.prepare_move()
        self.runtime_version = [3, 11, 14]
        with self.assertRaises(ValueError):
            self.sync()
        self.assertEqual(len(joint.source.slots(self.held)[0].splitlines()), 4)

    def test_sync_cannot_start_before_source_move_or_after_source_input_drift(self):
        joint.prepare(self.held, self.plan, *self.args, official_fd=self.official)
        history = joint.source.slots(self.held)
        with self.assertRaises(joint.source.worker.Refused):
            self.sync()
        self.assertEqual(joint.source.slots(self.held), history)
        joint.move(self.held, self.plan, *self.args, official_fd=self.official)
        lock = self.root / "checkout/uv.lock"
        lock.write_bytes(lock.read_bytes() + b"changed\n")
        with self.assertRaises(joint.source.worker.Refused):
            self.sync()
        self.assertEqual(joint.source.slots(self.held), history)
        self.assertFalse((self.root / "pm-sync.log").exists())


class JointCommandBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.driver = load("joint_command_boundary", HERE / "hermes-maintenance-source-pm.py")

    def test_cli_requires_opt_in_and_has_bilingual_redacted_registered_refusal(self):
        for args in (["joint"], ["joint", "--old-root", "/private/old", "--candidate-root", "/private/new"],
                     ["joint", "--provision", "--old-root", "/private/old", "--candidate-root", "/private/new"]):
            output = io.StringIO()
            with patch.object(sys, "argv", args), contextlib.redirect_stdout(output), \
                    patch.object(self.driver, "provision", side_effect=ValueError("TOKEN private-data")):
                self.assertEqual(self.driver.main(), 1)
            result = json.loads(output.getvalue())
            self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(result["retryable"])
            self.assertEqual(result["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
            self.assertEqual(result["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
            self.assertNotIn("TOKEN", output.getvalue())
            self.assertNotIn("private-data", output.getvalue())

    def test_official_module_is_never_imported_for_an_unmarked_or_unpinned_root(self):
        case = fixtures.SourceTransitionTest()
        case.setUp()
        self.addCleanup(case.doCleanups)
        with patch.object(self.driver, "load", side_effect=AssertionError("upstream must not import")):
            with self.assertRaises((ValueError, OSError)):
                self.driver.official(case.root, case.new, case.after)


if __name__ == "__main__":
    unittest.main()
