"""Fixture transition policy, live child evidence and fail-closed terminal publication."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("transition_driver", Path(__file__).with_name("hermes-maintenance-transition.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
policy = driver.transition


class FixtureTransitionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-transition-unit-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.values = {name: b'"""Synthetic package."""\n' for name in driver.durable.bootstrap.BOOTSTRAP_SHA256}
        self.values["hermes_bootstrap.py"] = b'''"""Synthetic bootstrap."""
import os
from pathlib import Path
def export_scratch_tmp_env():
    (Path(os.environ["HERMES_HOME"])/"cache/scratch").mkdir(parents=True)
export_scratch_tmp_env()
'''
        for module in driver.durable.bootstrap.ENTRIES:
            self.values[module.replace(".", "/") + ".py"] = b'"""Synthetic entry."""\nimport hermes_bootstrap\ndef main(): pass\n'
        self.sources = self.root / "sources"
        for name, data in self.values.items():
            path = self.sources / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}
        self.fixture = driver.Fixture(self.values, "hermes_cli.main")
        self.addCleanup(self.fixture.close)

    def prepared(self):
        held = self.fixture.held()
        try:
            policy.prepare(held, self.fixture.plan)
        finally:
            held.close()
        self.fixture.stage_target()
        value = self.fixture.held()
        self.addCleanup(value.close)
        return value

    def clone_policy(self, name):
        path = self.root / name
        path.mkdir(mode=0o700)
        for item in ("generation_transition.py", "durable_intent.py", "bound_handoff.py", "handoff_actor.py",
                     "maintenance_lease.py", "startup_probe.py"):
            shutil.copyfile(driver.durable.entry.PROTOTYPE / item, path / item)
        return path

    def test_generation_matrix_and_killed_coordinators_keep_production_flags_false(self):
        with patch.dict(driver.durable.bootstrap.BOOTSTRAP_SHA256, self.hashes, clear=True):
            value = driver.inspect(self.sources)
        self.assertEqual(value["completedFixtureWriterPrefixes"], 4)
        self.assertEqual(value["staleGenerationRefusals"], 8)
        self.assertEqual(value["coordinatorSigkillCases"], 10)
        self.assertTrue(value["partialPlanAndTerminalStayClosed"])
        self.assertTrue(value["intentHistoryPreserved"])
        for name in ("realDependencyGenerationVerified", "productionCompletionVerified", "productionAuthorityVerified",
                     "durableHandoffVerified", "osRebootVerified", "fullStartupVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[name])

    def test_plan_is_bound_before_mutation_and_unplanned_target_stays_closed(self):
        held = self.fixture.held()
        self.addCleanup(held.close)
        saved = self.fixture.base.slots()
        for field in ("run", "home", "entry", "role"):
            plan = json.loads(policy.binding.encode(self.fixture.plan))
            plan["after"][field] = "unrequested"
            with self.assertRaises(ValueError):
                policy.prepare(held, plan)
            self.assertEqual(self.fixture.base.slots(), saved)
        policy.prepare(held, self.fixture.plan)
        with self.assertRaises(ValueError):
            policy.target(held, self.fixture.plan)  # The approved target is not present yet.
        held.close()
        self.fixture.stage_target()
        path = self.fixture.base.checkout / "hermes_constants.py"
        path.write_bytes(path.read_bytes() + b"# unplanned target movement\n")
        request = self.fixture.held()
        try:
            with self.assertRaises(ValueError):
                policy.target(request, self.fixture.plan)
        finally:
            request.close()
        self.assertTrue(self.fixture.base.probe()["blocked"])

    def test_boolean_saved_receipt_and_missing_witness_cannot_finish(self):
        held = self.prepared()
        saved = self.fixture.base.slots()
        for invalid in (None, True, {"ok": True, "prefixReached": True, "exit": 0}):
            with self.assertRaises(ValueError):
                policy.finish_fixture(held, self.fixture.plan, invalid)
        self.assertEqual(self.fixture.base.slots(), saved)
        held.close()
        self.assertTrue(self.fixture.base.probe()["blocked"])

    def test_witness_is_fresh_one_use_and_bound_to_current_custodian(self):
        held = self.prepared()
        witness = policy.check_fixture_prefix(held, self.fixture.plan)
        with patch.object(policy.time, "monotonic", return_value=witness.created + 6):
            with self.assertRaises(ValueError):
                policy.finish_fixture(held, self.fixture.plan, witness)
        held.close()
        other = self.fixture.held()
        self.addCleanup(other.close)
        with self.assertRaises(ValueError):
            policy.finish_fixture(other, self.fixture.plan, witness)
        fresh = policy.check_fixture_prefix(other, self.fixture.plan)
        policy.finish_fixture(other, self.fixture.plan, fresh)
        saved = self.fixture.base.slots()
        self.assertTrue(fresh.used)
        with self.assertRaises(ValueError):
            policy.finish_fixture(other, self.fixture.plan, fresh)
        self.assertEqual(self.fixture.base.slots(), saved)

    def test_incomplete_prefix_or_nonzero_child_exit_cannot_create_witness(self):
        held = self.prepared()
        prototype = self.clone_policy("bad-child")
        path = prototype / "startup_probe.py"
        original = path.read_text()
        variants = [original.replace('print(json.dumps(result), flush=True)',
                                     f'result["{field}"] = {value}\n    print(json.dumps(result), flush=True)')
                    for field, value in (("prefixReached", "False"), ("bootstrapEntered", "False"),
                                         ("homeMutationAttempts", "0"))]
        variants.append(original.replace('    for value in held:\n        value.close()',
                                         '    for value in held:\n        value.close()\n    raise SystemExit(9)'))
        for text in variants:
            path.write_text(text)
            with patch.object(policy, "HERE", prototype):
                with self.assertRaises(ValueError):
                    policy.check_fixture_prefix(held, self.fixture.plan)
            self.assertTrue(all(len(value.splitlines()) == 2 for value in self.fixture.base.slots()))

    def test_partial_terminal_and_overlap_cannot_open_admission(self):
        held = self.prepared()
        witness = policy.check_fixture_prefix(held, self.fixture.plan)

        def stop(point, index):
            if point == "slot-synced" and index == 0:
                raise OSError("synthetic append interruption")

        with self.assertRaises(OSError):
            policy.finish_fixture(held, self.fixture.plan, witness, stop)
        self.assertTrue(witness.used)
        held.close()
        self.assertTrue(self.fixture.base.probe()["blocked"])
        other_code = self.fixture.base.root / "other-code"
        other_code.mkdir(mode=0o700)
        domain = driver.durable.lease.Domain(self.fixture.base.control, "checkout", other_code)
        domain.enroll()
        home = next(d for d in self.fixture.base.domains if d.binding["kind"] == "home")
        with self.assertRaises(driver.durable.lease.Pending):
            driver.durable.lease.Lease([home, domain])
        recovered = self.fixture.held()
        try:
            with self.assertRaises(ValueError):
                policy.target(recovered, self.fixture.plan)
        finally:
            recovered.close()

    def test_completed_history_corruption_and_changed_generation_refuse(self):
        held = self.prepared()
        policy.finish_fixture(held, self.fixture.plan, policy.check_fixture_prefix(held, self.fixture.plan))
        held.close()
        saved = self.fixture.base.slots()
        self.assertFalse(self.fixture.base.probe()["blocked"])
        with patch.object(policy.os, "fsync", side_effect=OSError("synthetic writer sync failure")):
            with self.assertRaises(driver.durable.lease.Pending):
                driver.durable.lease.Lease(self.fixture.base.domains)
        self.assertEqual(self.fixture.base.slots(), saved)
        for damaged in (saved[0][:-1], saved[0] + b"{}\n", saved[0].replace(b"child-exit-zero", b"child-exit-xxxx")):
            for domain in self.fixture.base.domains:
                domain._path("intent").write_bytes(damaged)
            self.assertTrue(self.fixture.base.probe()["blocked"])
        for domain, data in zip(self.fixture.base.domains, saved):
            domain._path("intent").write_bytes(data)
        for path, data in self.fixture.before_bytes.items():
            path.write_bytes(data)
            self.assertTrue(self.fixture.base.probe()["blocked"])
            path.write_bytes(self.fixture.after_bytes[path])

    def test_omitting_live_witness_check_reproduces_premature_fixture_admission(self):
        held = self.prepared()
        fake = types.SimpleNamespace(held=held, digest=policy.sha(policy.pending(self.fixture.plan)),
                                     created=policy.time.monotonic(), nonce="0" * 64, used=False)
        with self.assertRaises(ValueError):
            policy.finish_fixture(held, self.fixture.plan, fake)
        prototype = self.clone_policy("bad-witness")
        path = prototype / "generation_transition.py"
        text = path.read_text()
        start = text.index("    if (type(witness)", text.index("def finish_fixture("))
        end = text.index("    witness.used = True", start)
        path.write_text(text[:start] + text[end:])
        mutant = driver.load("witness_mutant", path)
        mutant.finish_fixture(held, self.fixture.plan, fake)
        held.close()
        # No live prefix witness existed before this writer was allowed to run.
        self.assertFalse(self.fixture.base.probe()["blocked"])

    def test_policy_cannot_apply_outside_explicit_disposable_fixture(self):
        domains = [types.SimpleNamespace(registry=self.root / "control", directory=self.root / name)
                   for name in ("data", "checkout")]
        with self.assertRaises(ValueError):
            policy.scope(domains, self.fixture.plan)
        for plan in (None, {}, {**self.fixture.plan, "schema": True}, {**self.fixture.plan, "policy": "production"}):
            with self.assertRaises(ValueError):
                policy.scope(self.fixture.base.domains, plan)

    def test_cli_error_is_bilingual_nonretryable_and_redacted(self):
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(driver.__file__)),
                                 "--candidate-root", str(self.sources)], env={"PATH": os.defpath, "HOME": str(self.root)},
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        value = json.loads(result.stdout)
        self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
        self.assertFalse(value["retryable"])
        self.assertEqual(value["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
        self.assertEqual(value["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
        self.assertEqual(value["reason"], "fixture-transition-needs-review")
        self.assertNotIn(str(self.root), result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
