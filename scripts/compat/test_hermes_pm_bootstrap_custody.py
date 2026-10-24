"""Synthetic input/kernel tests; actual prepare_runtime/native work is separately opt-in."""
import copy
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


fixtures = load("bootstrap_joint_tests", HERE / "test_hermes_maintenance_source_pm.py")
policy = load("bootstrap_custody_tests", HERE / "upstream-maintenance-prototype/pm_bootstrap_custody.py")


class BootstrapStandaloneTest(unittest.TestCase):
    def test_cli_requires_opt_in_and_redacts_invalid_arguments(self):
        for args in ([], ["--provision", "--mode", "token=do-not-echo"],
                     ["--old-root", "/private/untrusted", "--candidate-root", "/private/untrusted",
                      "--prepared-root", "/private/untrusted", "--mode", "raw"]):
            process = subprocess.run([sys.executable, "-I", "-S", "-B",
                str(HERE / "hermes-maintenance-pm-bootstrap.py"), *args], capture_output=True, text=True, timeout=10)
            self.assertEqual(process.returncode, 1)
            value = json.loads(process.stdout)
            self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
            self.assertIs(value["retryable"], False)
            self.assertTrue(value["summaryChinese"])
            self.assertTrue(value["summaryEnglish"])
            self.assertNotIn("do-not-echo", process.stdout + process.stderr)

    def test_local_backend_can_expose_metadata_without_starting_the_build(self):
        actor = load("bootstrap_metadata_template", HERE / "upstream-maintenance-prototype/pm_bootstrap_actor.py")
        with tempfile.TemporaryDirectory(prefix="hg199-bootstrap-metadata-") as directory:
            root = Path(directory)
            source = actor.chain_actor.BACKEND.replace("__ROOT__", repr(str(root))).replace("__IDENTITIES__", "[]") + actor.METADATA
            (root / "backend.py").write_text(source)
            module = load("bootstrap_metadata_only", root / "backend.py")
            name = module.prepare_metadata_for_build_wheel(directory)
            self.assertIn("Name: hg199-bootstrap-build", (root / name / "METADATA").read_text())
            self.assertFalse((root / "backend-status.json").exists())


class BootstrapCustodyTest(unittest.TestCase):
    def setUp(self):
        self.case = fixtures.JointSourcePMTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.case.prepare_move()
        self.root, self.primary, self.expected = self.case.root, self.case.plan, self.case.args
        marker = self.root / ".hg199-isolated-pm-bootstrap"
        marker.write_bytes(policy.MARKER)
        marker.chmod(0o600)
        for name in policy.FILES:
            path = self.root / name
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            path.write_text("# synthetic project input\n")
        tools = self.root / "data/tools"
        tools.mkdir(parents=True)
        for name in ("uv", "python"):
            (tools / name).write_text("synthetic: never executed\n")
            (tools / name).chmod(0o700)
        (self.root / "runtime-audit/generations").mkdir(parents=True, mode=0o700)
        self.prepare_fd = os.open(self.root / "runtime-audit/.prepare.lock", os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
        self.addCleanup(self.close_prepare)
        fcntl.flock(self.prepare_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.plan = policy.make_plan(self.case.held, self.primary, self.expected, self.case.official,
                                     tools / "uv", tools / "python")
        policy.begin(self.case.held, self.primary, self.expected, self.case.official, self.plan)
        self.saved = policy.joint.source.slots(self.case.held)
        self.generation = self.root / "runtime-audit/generations" / ("a" * 32)

    def close_prepare(self):
        if self.prepare_fd is not None:
            os.close(self.prepare_fd)
            self.prepare_fd = None

    def command(self):
        return [self.plan["tools"]["uv"]["path"], "venv", "--relocatable", "--no-project", "--no-config",
                "--python", self.plan["tools"]["python"]["path"], str(self.generation), "--offline"]

    def options(self):
        return {"cwd": str(self.generation.parent), "stdout": subprocess.PIPE, "stderr": subprocess.STDOUT,
            "text": True, "encoding": "utf-8", "errors": "replace", "bufsize": 0,
            "env": {"HOME": str(self.root / "user"), "HERMES_HOME": str(self.root / "data"),
                    "UV_PYTHON": self.plan["tools"]["python"]["path"], "UV_PROJECT_ENVIRONMENT": str(self.generation),
                    "UV_CACHE_DIR": str(self.root / "cache"), "UV_PYTHON_DOWNLOADS": "never"}}

    def kwargs(self, command=None, options=None, prepare=None):
        return policy.native_kwargs(self.case.held, self.primary, self.expected, self.case.official, self.plan,
            self.prepare_fd if prepare is None else prepare,
            self.command() if command is None else command, self.options() if options is None else options)

    def test_plan_and_actual_prepare_description_are_bound(self):
        self.kwargs()
        for change in ({**self.plan, "schema": True}, {**self.plan, "futureAuthority": True},
                       {**self.plan, "prepareIdentity": [True, 1]}, {**self.plan, "policy": "sync"}):
            with self.assertRaises(ValueError):
                policy.validate(self.case.held, self.primary, self.expected, self.case.official, change)
        other = os.open(self.root / "unrelated.lock", os.O_RDWR | os.O_CREAT, 0o600)
        try:
            with self.assertRaises(ValueError):
                self.kwargs(prepare=other)
        finally:
            os.close(other)
        (self.root / "runtime-project/uv.lock").write_text("changed runtime lock\n")
        with self.assertRaises(ValueError):
            self.kwargs()
        self.assertEqual(policy.joint.source.slots(self.case.held), self.saved)

    def test_unknown_options_environment_and_commands_are_refused(self):
        options = self.options()
        for changed in ({**options, "shell": True}, {**options, "cwd": "/tmp"},
                        {**options, "env": {**options["env"], "PYTHONPATH": "/tmp"}},
                        {**options, "env": {**options["env"], "UV_PROJECT_ENVIRONMENT": "/tmp"}}):
            with self.assertRaises(ValueError):
                self.kwargs(options=changed)
        with self.assertRaises(ValueError):
            self.kwargs(command=[self.command()[0], "run", "unselected"])
        self.assertEqual(len(self.kwargs()["pass_fds"]), 6)
        self.assertIs(self.kwargs()["close_fds"], True)

    def test_sync_snapshot_is_checked_against_the_planned_project(self):
        scratch = self.generation.parent / "pm-project-synthetic"
        scratch.mkdir(mode=0o700)
        for name in ("pyproject.toml", "uv.lock"):
            (scratch / name).write_bytes((self.root / "runtime-project" / name).read_bytes())
        command = [self.command()[0], "sync", "--locked", "--all-packages", "--python", self.command()[6],
            "--compile-bytecode", "--no-default-groups", "--no-install-project", "--no-install-package",
            "hg199-bootstrap-root", "--no-config", "--offline"]
        options = {**self.options(), "cwd": str(scratch)}
        self.kwargs(command, options)
        (scratch / "uv.lock").write_text("unplanned dependency\n")
        with self.assertRaises(ValueError):
            self.kwargs(command, options)

    def test_partial_intent_is_not_repaired_or_used_as_sync_authority(self):
        with patch.object(policy.joint.pm, "_observe_root", side_effect=AssertionError("must not observe")) as observer:
            with self.assertRaises(ValueError):
                policy.joint.verify(self.case.held, self.primary, *self.expected,
                    official_fd=self.case.official, generation_sha="0" * 64, observe_prefix=())
            observer.assert_not_called()
        fd = self.case.held.domains[0].intent()
        try:
            os.ftruncate(fd, len(self.saved[0]) - 3)
            os.fsync(fd)
        finally:
            os.close(fd)
        partial = policy.joint.source.slots(self.case.held)
        with self.assertRaises(ValueError):
            self.kwargs()
        self.assertEqual(policy.joint.source.slots(self.case.held), partial)

    def child_custody(self, inherit):
        paths = [d._path(kind) for d in self.case.held.domains for kind in ("gate", "writers")]
        paths += [self.root / "checkout/.git/hermes-update.lock", self.root / "runtime-audit/.prepare.lock"]
        selected = self.kwargs()["pass_fds"] if inherit else ()
        child = subprocess.Popen([sys.executable, "-I", "-S", "-B", "-c",
            'import json,select,sys;print(json.dumps({"ready":True}),flush=True);select.select([sys.stdin],[],[],8)'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=selected)
        try:
            self.assertEqual(policy.joint.pm.support.read(child.stdout.fileno(), timeout=5), {"ready": True})
            self.case.held.close()
            self.case.close_official()
            self.close_prepare()
            for path in paths:
                fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
                try:
                    if inherit:
                        with self.assertRaises(BlockingIOError):
                            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    else:
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                finally:
                    os.close(fd)
        finally:
            child.stdin.close()
            child.wait(timeout=10)
            child.stdout.close()
        self.assertEqual(child.returncode, 0)
        for path in paths:
            fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(fd)
        self.case.fixture.blocked()

    def test_inherited_child_holds_maintenance_official_and_prepare_locks_after_parent_close(self):
        self.child_custody(True)

    def test_original_child_options_lose_all_six_descriptions_after_parent_close(self):
        self.child_custody(False)


if __name__ == "__main__":
    unittest.main()
