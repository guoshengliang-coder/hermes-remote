"""Synthetic source/worker, actual inherited FD custody, parent close and SIGKILL."""
import fcntl
import contextlib
import importlib.util
import json
import io
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


fixtures = load("worker_joint_fixtures", HERE / "test_hermes_maintenance_source_pm.py")
custody = load("worker_custody_test", HERE / "upstream-maintenance-prototype/pm_worker_custody.py")
joint = custody.joint
lease = joint.source.load("maintenance_lease")


class PMWorkerCustodyTest(unittest.TestCase):
    def setUp(self):
        self.case = fixtures.JointSourcePMTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.case.prepare_move()
        self.root, self.plan, self.args = self.case.root, self.case.plan, self.case.args
        joint.append(self.case.held, joint.planned(self.plan), joint.event(joint.planned(self.plan), "pm-sync-started", self.args[3]))
        binary = self.root / "data/tools/worker-host/bin/python"
        binary.parent.mkdir(parents=True)
        # A fixture-only host launcher avoids claiming that copying a Mach-O binary also
        # copied its framework/loader closure. The worker/source/runtime here are synthetic.
        binary.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " \"$@\"\n")
        binary.chmod(0o700)
        self.python = str(binary)
        self.history = joint.source.slots(self.case.held)

    def fresh_lease(self):
        try:
            value = lease.Lease([lease.Domain(self.root / "control", k, self.root / n)
                                 for k, n in (("home", "data"), ("checkout", "checkout"))], maintenance=True)
        except BlockingIOError:
            return None
        if not value.drain():
            value.close()
            return None
        return value

    def official_available(self):
        fd = os.open(self.root / "checkout/.git/hermes-update.lock", os.O_RDWR | os.O_NOFOLLOW)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return False
            return True
        finally:
            os.close(fd)

    def observe(self, mode, death):
        self.case.held.close()
        self.case.close_official()
        manifest = joint.binding.private_context_fd(self.root / "control", self.plan)
        read_gate, write_gate = os.pipe()
        actor = None
        try:
            actor = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(HERE / "upstream-maintenance-prototype/pm_worker_parent_actor.py"),
                                      str(self.root), str(manifest), str(read_gate), *self.args, mode],
                                     env=fixtures.fixtures.driver.cli.driver.environment(self.root), stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(manifest, read_gate))
            os.close(manifest)
            manifest = None
            os.close(read_gate)
            read_gate = None
            ready = joint.pm.support.read(actor.stdout.fileno(), timeout=15)
            self.assertTrue(ready.get("workerReady"), (ready, actor.stderr.read().decode() if not ready.get("workerReady") else ""))
            self.assertEqual(ready["descriptorsMatch"], mode == "proposed")
            if death:
                actor.kill()  # The exact test-owned Popen; its worker has an independent gate.
                self.assertEqual(actor.wait(timeout=5), -signal.SIGKILL)
            else:
                actor.stdin.write(json.dumps({"closeParentCustody": True}).encode() + b"\n")
                actor.stdin.flush()
                self.assertEqual(joint.pm.support.read(actor.stdout.fileno()), {"parentCustodyClosed": True})
            value = self.fresh_lease()
            if mode == "proposed":
                if value is not None:
                    value.close()
                self.assertIsNone(value, "live worker must keep home/checkout custody after parent exit/close")
                self.assertFalse(self.official_available())
            else:
                self.assertIsNotNone(value, "negative control: raw worker lost maintenance descriptors")
                value.close()
                self.assertTrue(self.official_available())
            os.write(write_gate, b"release\n")
            os.close(write_gate)
            write_gate = None
            deadline = time.monotonic() + 5
            while True:
                value = self.fresh_lease()
                if value is not None:
                    break
                if time.monotonic() >= deadline:
                    self.fail("released worker retained custody")
                time.sleep(.02)
            try:
                self.assertTrue(self.official_available())
                official = os.open(self.root / "checkout/.git/hermes-update.lock", os.O_RDWR)
                try:
                    fcntl.flock(official, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    with patch.object(joint.pm, "_observe_root", side_effect=AssertionError("no probe for unknown sync")):
                        with self.assertRaises(ValueError):
                            joint.verify(value, self.plan, *self.args, official_fd=official,
                                         generation_sha="0" * 64, observe_prefix=())
                finally:
                    os.close(official)
                self.assertEqual(joint.source.slots(value), self.history)
            finally:
                value.close()
            if not death:
                self.assertEqual(actor.wait(timeout=5), 0, actor.stderr.read().decode())
            self.case.fixture.blocked()
        finally:
            for fd in (manifest, read_gate, write_gate):
                if fd is not None:
                    os.close(fd)
            if actor is not None:
                if actor.poll() is None:
                    actor.kill()
                actor.wait(timeout=5)
                actor.stdin.close()
                actor.stdout.close()
                actor.stderr.close()

    def test_raw_worker_loses_both_locks_after_parent_sigkill_but_intent_still_blocks(self):
        self.observe("raw", True)

    def test_proposed_worker_keeps_all_custody_after_parent_sigkill(self):
        self.observe("proposed", True)

    def test_proposed_worker_keeps_all_custody_after_active_parent_close(self):
        self.observe("proposed", False)

    def test_spawn_refuses_unreviewed_commands_environment_and_options_before_popen(self):
        argv = [self.python, "-I", "-B", str(self.root / "checkout/pm/worker.py")]
        env = fixtures.fixtures.driver.cli.driver.environment(self.root)
        for command, options in ((["/bin/sh", "-c", "true"], {"env": env}),
                                 (argv, {"env": {**env, "PYTHONPATH": "/private/injected"}}),
                                 (argv, {"env": env, "close_fds": False}),
                                 (argv, {"env": {**env, "HERMES_HOME": "/private/other"}})):
            with patch.object(custody, "POPEN", side_effect=AssertionError("must refuse before child")):
                with self.assertRaises(ValueError):
                    custody.spawn(self.case.held, self.plan, *self.args, official_fd=self.case.official,
                                  argv=command, options=options)
        with patch.object(custody, "POPEN", return_value="only-a-test-sentinel") as spawn:
            self.assertEqual(custody.spawn(self.case.held, self.plan, *self.args, official_fd=self.case.official,
                                          argv=argv, options={"env": env}), "only-a-test-sentinel")
            self.assertEqual(spawn.call_args.kwargs["cwd"], str(self.root))
            self.assertTrue(spawn.call_args.kwargs["close_fds"])
            self.assertEqual(spawn.call_args.kwargs["pass_fds"], (*self.case.held.pass_fds, self.case.official))


class PMWorkerCommandBoundaryTest(unittest.TestCase):
    def test_real_cli_opt_in_modes_and_failures_use_bilingual_redacted_refusal(self):
        driver = load("real_worker_command_test", HERE / "hermes-maintenance-pm-worker.py")
        for args in (["worker"], ["worker", "--old-root", "/private/old", "--candidate-root", "/private/new", "--mode", "raw"],
                     ["worker", "--provision", "--old-root", "/private/old", "--candidate-root", "/private/new", "--mode", "proposed"],
                     ["worker", "--provision", "--old-root", "/private/old", "--candidate-root", "/private/new", "--mode", "unsafe"]):
            output = io.StringIO()
            with patch.object(sys, "argv", args), contextlib.redirect_stdout(output), \
                    patch.object(driver, "inspect", side_effect=ValueError("TOKEN private-data")):
                self.assertEqual(driver.main(), 1)
            result = json.loads(output.getvalue())
            self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(result["retryable"])
            self.assertEqual(result["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
            self.assertEqual(result["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
            self.assertNotIn("TOKEN", output.getvalue())
            self.assertNotIn("private-data", output.getvalue())


if __name__ == "__main__":
    unittest.main()
