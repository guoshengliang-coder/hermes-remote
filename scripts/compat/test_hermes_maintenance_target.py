"""Prepared-target binding tests; runtime reports are SYNTHETIC, never certification."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import sys
import unittest
from unittest.mock import patch
import uuid

import importlib.util


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


HERE = Path(__file__).parent
fixtures = load("pm_target_fixture", HERE / "test_hermes_maintenance_pm.py")
cli = load("pm_target_cli_test", HERE / "hermes-maintenance-target.py")
target = cli.target


class PMTargetTest(unittest.TestCase):
    def setUp(self):
        self.case = fixtures.PMGenerationTest(methodName="runTest")
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        p = patch.object(target, "pm", fixtures.observer)
        p.start()
        self.addCleanup(p.stop)
        c = self.case
        for name in target.BOOTSTRAP.BOOTSTRAP_SHA256:
            path = c.checkout / name
            if not path.exists():
                path.parent.mkdir(exist_ok=True, parents=True)
                path.write_bytes(b"# synthetic bootstrap source\n")
        records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in c.held.domains]
        self.context = target.binding.context(str(uuid.uuid4()), fixtures.driver.COMMIT, c.checkout,
                                              c.root / "data", "hermes_cli.main", records,
                                              list(target.BOOTSTRAP.BOOTSTRAP_SHA256))
        target.intent.begin(c.held, self.context)
        # Explicit fixture expectations, established without invoking observe/runtime.
        budget = [0, 0]
        trees = [target.pm.tree(p, (c.venv, c.base), budget, uv_lock=c.venv / ".lock")
                 for p in (c.venv, c.base)]
        self.plan = {"schema": 1, "policy": target.POLICIES[0], "context": self.context,
                     "inputs": target.binding.snapshot(c.checkout, [*target.pm.PINNED, *target.pm.INPUTS]),
                     "generation": {"environment": str(c.venv), "environmentIdentity": target.binding.directory(c.venv),
                                    "runtimeBase": str(c.base), "runtimeIdentity": target.binding.directory(c.base),
                                    "environmentDigest": trees[0]["digest"], "runtimeDigest": trees[1]["digest"],
                                    "runtimeVersion": [3, 11, 15], "packagingVersion": "test-only"}}
        c.calls.assert_not_called()
        self.slots = [d._path("intent").read_bytes() for d in c.held.domains]

    def verify(self, plan=None, **kwargs):
        value = self.plan if plan is None else plan
        args = {"expected_run": self.context["run"], "expected_source": fixtures.driver.COMMIT,
                "expected_sha": target.binding.digest(value)}
        args.update(kwargs)
        return target.verify(self.case.held, value, **args)

    def test_both_fixed_policies_match_without_granting_authority_or_clearing_intent(self):
        for policy in target.POLICIES:
            self.plan["policy"] = policy
            result = self.verify()
            self.assertTrue(result["preparedTargetMatched"])
            self.assertTrue(result["activationInputsCurrent"])
            for key in ("targetAuthorityVerified", "durableHandoffVerified", "productionCompletionVerified", "certifiesUpgrade"):
                self.assertFalse(result[key])
        self.assertEqual([d._path("intent").read_bytes() for d in self.case.held.domains], self.slots)
        self.case.held.close()
        lease = fixtures.driver.load("target_admission_test", fixtures.driver.PROTOTYPE / "maintenance_lease.py")
        domains = [lease.Domain(self.case.root / "control", kind, self.case.root / name)
                   for kind, name in (("home", "data"), ("checkout", "checkout"))]
        with self.assertRaises(lease.Pending):
            lease.Lease(domains)

    def test_missing_observation_and_boolean_receipts_are_not_plans(self):
        for value in (None, True, {}, {"preparedTargetMatched": True}):
            with self.assertRaises(ValueError):
                target.verify(self.case.held, value, self.context["run"], fixtures.driver.COMMIT, "0" * 64)
        self.case.calls.assert_not_called()

    def test_independent_run_source_and_digest_are_required_before_child_execution(self):
        for args in ({"expected_run": str(uuid.uuid4())}, {"expected_source": "0" * 40},
                     {"expected_sha": "0" * 64}, {"expected_sha": True}):
            with self.assertRaises(ValueError):
                self.verify(**args)
        self.case.calls.assert_not_called()

    def test_strict_schema_policy_and_runtime_shapes(self):
        for key, value in (("schema", True), ("policy", "allow-current"), ("generation", True),
                           ("inputs", {}), ("context", {})):
            plan = copy.deepcopy(self.plan)
            plan[key] = value
            with self.assertRaises(ValueError):
                self.verify(plan)
        for key, value in (("runtimeVersion", [True, 11, 15]), ("runtimeIdentity", [True, 0]),
                           ("packagingVersion", "x\nprivate-details"), ("environmentDigest", "saved-observation")):
            plan = copy.deepcopy(self.plan)
            plan["generation"][key] = value
            with self.assertRaises(ValueError):
                self.verify(plan)
        self.case.calls.assert_not_called()

    def test_wrong_exact_generation_rejected_even_with_matching_plan_digest(self):
        for key, value in (("environment", str(self.case.base)), ("environmentIdentity", [0, 0]),
                           ("runtimeBase", str(self.case.venv)), ("runtimeIdentity", [0, 0]),
                           ("runtimeVersion", [3, 12, 0]), ("packagingVersion", "different"),
                           ("environmentDigest", "0" * 64), ("runtimeDigest", "0" * 64)):
            plan = copy.deepcopy(self.plan)
            plan["generation"][key] = value
            with self.assertRaises(ValueError):
                self.verify(plan)

    def test_new_transaction_cannot_borrow_existing_intent(self):
        plan = copy.deepcopy(self.plan)
        plan["context"]["run"] = str(uuid.uuid4())
        with self.assertRaises(ValueError):
            self.verify(plan, expected_run=plan["context"]["run"])
        self.case.calls.assert_not_called()

    def test_context_scope_and_changed_bootstrap_or_lock_input_refuse(self):
        for key, value in (("home", str(self.case.root / "user")), ("entry", "gateway.run"), ("domains", [])):
            plan = copy.deepcopy(self.plan)
            plan["context"][key] = value
            with self.assertRaises(ValueError):
                self.verify(plan)
        for name in ("agent/legacy_cli.py", "uv.lock"):
            path = self.case.checkout / name
            data = path.read_bytes()
            path.write_bytes(data + b"# drift\n")
            try:
                with self.assertRaises(ValueError):
                    self.verify()
            finally:
                path.write_bytes(data)
        self.case.calls.assert_not_called()

    def test_unpublished_activation_only_matches_prepared_policy_without_repair(self):
        shutil.rmtree(self.case.state / "inputs")
        self.assertFalse(self.verify()["activationInputsCurrent"])
        self.plan["policy"] = target.POLICIES[1]
        with self.assertRaises(ValueError):
            self.verify()
        self.assertFalse((self.case.state / "inputs").exists())

    def test_dependency_drift_between_observations_refuses_with_negative_control(self):
        original = target.pm.observe

        def drift(*args, **kwargs):
            value = original(*args, **kwargs)
            self.case.package.write_bytes(b"# different dependency\n")
            return value
        with patch.object(target.pm, "observe", side_effect=drift):
            with self.assertRaises(ValueError):
                self.verify()
            # Negative control: a single observation misses this exact change.
            self.case.package.write_bytes(b"# synthetic package bytes\n")
            # Tree identity includes ctime, so baseline the explicitly restored fixture anew.
            budget = [0, 0]
            self.plan["generation"]["environmentDigest"] = target.pm.tree(
                self.case.venv, (self.case.venv, self.case.base), budget)["digest"]
            with patch.object(target.pm, "revalidate"):
                self.assertTrue(self.verify()["preparedTargetMatched"])

    def test_idle_or_partial_intent_cannot_be_used_as_transaction_evidence(self):
        path = self.case.held.domains[0]._path("intent")
        path.write_bytes(target.intent.IDLE)  # Synthetic fault injection ONLY.
        with self.assertRaises(ValueError):
            self.verify()
        self.case.calls.assert_not_called()

    def test_transaction_is_rechecked_after_runtime_observations(self):
        original = target.pm.revalidate

        def changed_intent(*args, **kwargs):
            original(*args, **kwargs)
            self.case.held.domains[1]._path("intent").write_bytes(target.intent.IDLE)
        with patch.object(target.pm, "revalidate", side_effect=changed_intent):
            with self.assertRaises(ValueError):
                self.verify()

    def test_plan_file_is_bounded_private_canonical_and_not_a_symlink(self):
        path = self.case.root / "control/explicit-plan.json"
        path.write_bytes(target.binding.encode(self.plan))
        path.chmod(0o600)
        self.assertEqual(cli.read_plan(path), self.plan)
        for mode in (0o644, 0o666):
            path.chmod(mode)
            with self.assertRaises(ValueError):
                cli.read_plan(path)
        path.chmod(0o600)
        path.write_bytes(b'{"schema":1,"schema":1}')
        with self.assertRaises(ValueError):
            cli.read_plan(path)
        path.unlink()
        path.symlink_to(self.case.facts)
        with self.assertRaises(OSError):
            cli.read_plan(path)

    def test_cli_error_has_registered_bilingual_code_without_private_diagnostics(self):
        args = ["target", "--fixture-root", str(self.case.root), "--plan-file", "/private/secret-plan",
                "--expected-run", self.context["run"], "--expected-source", fixtures.driver.COMMIT,
                "--expected-plan-sha", "0" * 64]
        output = io.StringIO()
        with patch.object(sys, "argv", args), patch.object(cli, "read_plan", side_effect=ValueError("TOKEN private-path")), contextlib.redirect_stdout(output):
            self.assertEqual(cli.main(), 1)
        value = json.loads(output.getvalue())
        self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
        self.assertFalse(value["retryable"])
        self.assertEqual(value["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
        self.assertEqual(value["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
        for private in ("TOKEN", "private-path", "secret-plan", str(self.case.root)):
            self.assertNotIn(private, output.getvalue())
        missing = io.StringIO()
        with patch.object(sys, "argv", ["target"]), contextlib.redirect_stdout(missing):
            self.assertEqual(cli.main(), 1)
        self.assertEqual(json.loads(missing.getvalue())["code"], "HR-HERMESUPDATE-002")


if __name__ == "__main__":
    unittest.main()
