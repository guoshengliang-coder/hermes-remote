"""Synthetic Git source commits, real checkout/intent/locks/process deaths. No PM/runtime."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("source_driver_test", Path(__file__).with_name("hermes-maintenance-source.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
source = driver.source


class SourceTransitionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-source-inputs-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.env = {"PATH": os.defpath, "HOME": str(self.root), "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
        self.old, self.new = self.root / "old", self.root / "candidate"
        self.git("init", "--quiet", str(self.old), cwd=self.root)
        for name in source.NAMES:
            path = self.old / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_text("# SYNTHETIC source, never executed\nVALUE = 1\n")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "old synthetic source")
        self.before = self.git("rev-parse", "HEAD")
        path = self.old / "hermes_constants.py"
        path.write_text("# SYNTHETIC target, never executed\nVALUE = 2\n")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-am", "target synthetic source")
        self.after = self.git("rev-parse", "HEAD")
        self.git("clone", "--quiet", "--no-hardlinks", str(self.old), str(self.new), cwd=self.root)
        self.git("checkout", "--quiet", "--detach", self.before)

    def git(self, *args, cwd=None):
        return subprocess.run(["/usr/bin/git", *args], cwd=cwd or self.old, env=self.env,
                              stdin=subprocess.DEVNULL, capture_output=True, text=True, check=True).stdout.strip()

    def fixture(self):
        value = driver.Fixture(self.old, self.new, self.before, self.after)
        self.addCleanup(shutil.rmtree, value.root)
        return value

    def test_full_real_git_matrix_preserves_history_and_blocks_writer(self):
        # The matrix retains its own roots for diagnosis, so record only this test's created roots.
        roots = []
        original = driver.Fixture

        def fixture(*args):
            value = original(*args)
            roots.append(value.root)
            return value
        try:
            with patch.object(driver, "Fixture", side_effect=fixture):
                result = driver.inspect(self.old, self.new, identities=(self.before, self.after))
            self.assertEqual(result["sourceCoordinatorSigkillCases"], 3)
            self.assertEqual(result["sourceExpectationRefusals"], 6)
            self.assertTrue(result["partialPlanPreserved"])
            self.assertTrue(result["ordinaryWriterBlocked"])
            for key in ("updaterExecuted", "pmExecuted", "runtimeVerified", "updaterRecoveryVerified", "productionCompletionVerified", "durableHandoffVerified", "certifiesUpgrade"):
                self.assertFalse(result[key])
        finally:
            for root in roots:
                shutil.rmtree(root)

    def test_plan_is_fixed_before_real_checkout_and_never_rewrites_intent(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)
        initial = source.slots(held)
        inodes = [d._path("intent").stat().st_ino for d in held.domains]
        source.prepare(held, fixture.plan, *fixture.args)
        saved = source.slots(held)
        self.assertTrue(all(a.startswith(b) for a, b in zip(saved, initial)))
        source.move(held, fixture.plan, *fixture.args)
        self.assertEqual(source.worker.git(fixture.root / "checkout", "rev-parse", "HEAD").decode().strip(), self.after)
        self.assertEqual(source.slots(held), saved)
        self.assertEqual([d._path("intent").stat().st_ino for d in held.domains], inodes)
        with self.assertRaises(source.worker.Refused):
            source.require(held, fixture.plan, *fixture.args, "before")

    def test_unplanned_move_replay_and_missing_plan_refuse(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)
        saved = source.slots(held)
        with self.assertRaises(ValueError):
            source.move(held, fixture.plan, *fixture.args)
        self.assertEqual(source.slots(held), saved)
        source.prepare(held, fixture.plan, *fixture.args)
        with self.assertRaises(ValueError):
            source.prepare(held, fixture.plan, *fixture.args)

    def test_plan_schema_policy_context_tree_and_independent_digest_refuse(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)
        for key, bad in (("schema", True), ("policy", "finish"), ("trees", {"after": "0" * 40}), ("before", {})):
            plan = copy.deepcopy(fixture.plan)
            plan[key] = bad
            with self.assertRaises(ValueError):
                source.prepare(held, plan, *fixture.args[:3], source.binding.digest(plan))
        for key, bad in (("home", str(fixture.root / "user")), ("entry", "gateway.run"), ("run", "different")):
            plan = copy.deepcopy(fixture.plan)
            plan["after"][key] = bad
            with self.assertRaises(ValueError):
                source.prepare(held, plan, *fixture.args[:3], source.binding.digest(plan))
        with self.assertRaises(ValueError):
            source.prepare(held, fixture.plan, *fixture.args[:3], "0" * 64)
        plan = copy.deepcopy(fixture.plan)
        plan["after"]["files"]["hermes_constants.py"] = "0" * 64
        saved = source.slots(held)
        with self.assertRaises(ValueError):
            source.prepare(held, plan, *fixture.args[:3], source.binding.digest(plan))
        self.assertEqual(source.slots(held), saved)

    def test_partial_append_stays_partial_and_cannot_move(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)

        def interrupt(point, index):
            if index == 0:
                raise OSError("test partial publication")
        with self.assertRaises(OSError):
            source.prepare(held, fixture.plan, *fixture.args, checkpoint=interrupt)
        saved = source.slots(held)
        self.assertNotEqual(saved[0], saved[1])
        with self.assertRaises(ValueError):
            source.move(held, fixture.plan, *fixture.args)
        self.assertEqual(source.slots(held), saved)
        held.close()
        fixture.blocked()

    def test_source_drift_is_rejected_even_when_plan_and_intent_match(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)
        source.prepare(held, fixture.plan, *fixture.args)
        source.move(held, fixture.plan, *fixture.args)
        path = fixture.root / "checkout/hermes_constants.py"
        path.write_bytes(path.read_bytes() + b"# changed\n")
        with self.assertRaises(source.worker.Refused):
            source.require(held, fixture.plan, *fixture.args, "after")
        # Negative control: journal matching alone misses changed actual source.
        with patch.object(source, "current", return_value=fixture.root):
            source.require(held, fixture.plan, *fixture.args, "after")

    def test_marked_scope_and_actual_exclusive_custody_are_required(self):
        fixture = self.fixture()
        held = fixture.held()
        self.addCleanup(held.close)
        marker = fixture.root / ".hg199-isolated-source-transition"
        marker.chmod(0o644)
        with self.assertRaises(ValueError):
            source.prepare(held, fixture.plan, *fixture.args)
        marker.chmod(0o600)
        held.close()
        with self.assertRaises(ValueError):
            source.prepare(held, fixture.plan, *fixture.args)

    def test_cli_refusal_uses_bilingual_registered_code_without_private_details(self):
        for argv in (["source"], ["source", "--old-root", "/private/old", "--candidate-root", "/private/new"]):
            output = io.StringIO()
            with patch.object(sys, "argv", argv), patch.object(driver, "inspect", side_effect=ValueError("TOKEN private-data")), contextlib.redirect_stdout(output):
                self.assertEqual(driver.main(), 1)
            value = json.loads(output.getvalue())
            self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(value["retryable"])
            self.assertEqual(value["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
            self.assertEqual(value["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
            self.assertNotIn("TOKEN", output.getvalue())
            self.assertNotIn("private-data", output.getvalue())


if __name__ == "__main__":
    unittest.main()
