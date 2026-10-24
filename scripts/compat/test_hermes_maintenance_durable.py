"""Persisted admission tests; full services, power loss and consent recovery are excluded."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("durable_driver", Path(__file__).with_name("hermes-maintenance-durable.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DurableAdmissionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-durable-unit-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.values = {name: b'"""Synthetic package."""\n' for name in driver.bootstrap.BOOTSTRAP_SHA256}
        self.values["hermes_bootstrap.py"] = b'''"""Synthetic bootstrap."""
import os
from pathlib import Path
def export_scratch_tmp_env():
    (Path(os.environ["HERMES_HOME"])/"cache/scratch").mkdir(parents=True)
export_scratch_tmp_env()
'''
        for module in driver.bootstrap.ENTRIES:
            self.values[module.replace(".", "/") + ".py"] = b'"""Synthetic entry."""\nimport hermes_bootstrap\ndef main(): pass\n'
        self.sources = self.root / "sources"
        for name, data in self.values.items():
            target = self.sources / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}
        self.fixture = self.new_fixture()

    def new_fixture(self):
        value = driver.Fixture(self.values, "hermes_cli.main")
        self.addCleanup(value.close)
        return value

    def held(self, fixture=None):
        fixture = self.fixture if fixture is None else fixture
        value = driver.lease.Lease(fixture.domains, maintenance=True)
        self.addCleanup(value.close)
        self.assertTrue(value.drain())
        return value

    def publish(self, fixture=None):
        fixture = self.fixture if fixture is None else fixture
        held = self.held(fixture)
        driver.intent.begin(held, fixture.context)
        held.close()

    def test_process_restart_matrix_blocks_writers_and_keeps_certification_false(self):
        with patch.dict(driver.bootstrap.BOOTSTRAP_SHA256, self.hashes, clear=True):
            value = driver.inspect(self.sources)
        self.assertEqual(value["newProcessRecoveryPrefixes"], 4)
        self.assertEqual(value["rejectedRecoveryContexts"], 28)
        self.assertEqual(value["publisherSigkillCases"], 5)
        self.assertTrue(value["processRestartAdmissionVerified"])
        self.assertTrue(value["partialPublicationStaysClosed"])
        self.assertTrue(value["recoveryExitKeepsIntent"])
        for key in ("automaticIntentClearImplemented", "productionAuthorityVerified", "durableHandoffVerified",
                    "osRebootVerified", "fullStartupVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[key])

    def test_skipping_idle_check_reproduces_post_death_writer_gap(self):
        self.fixture.publish_and_die("complete")
        prototype = self.root / "mutant"
        prototype.mkdir(mode=0o700)
        for name in ("maintenance_lease.py", "startup_probe.py"):
            shutil.copyfile(driver.entry.PROTOTYPE / name, prototype / name)
        path = prototype / "maintenance_lease.py"
        text = path.read_text()
        self.assertEqual(text.count("                    domain.require_idle()"), 1)
        path.write_text(text.replace("                    domain.require_idle()", "                    pass  # disposable mutant"))
        with patch.object(driver.entry, "PROTOTYPE", prototype):
            value = self.fixture.probe()
        self.assertFalse(value["blocked"])
        self.assertTrue(value["scratchCreated"])
        self.assertGreater(value["homeMutationAttempts"], 0)
        normal = self.fixture.probe()
        self.assertTrue(normal["blocked"])
        self.assertEqual(normal["homeMutationAttempts"], 0)

    def test_begin_requires_drained_exclusion_and_resume_requires_existing_exact_intent(self):
        writer = driver.lease.Lease(self.fixture.domains)
        self.addCleanup(writer.close)
        with self.assertRaises(ValueError):
            driver.intent.begin(writer, self.fixture.context)
        request = driver.lease.Lease(self.fixture.domains, maintenance=True)
        self.addCleanup(request.close)
        self.assertFalse(request.drain())
        with self.assertRaises(ValueError):
            driver.intent.begin(request, self.fixture.context)
        writer.close()
        self.assertTrue(request.drain())
        with self.assertRaises(ValueError):
            driver.intent.resume(request, self.fixture.context)
        digest = driver.intent.begin(request, self.fixture.context)
        self.assertEqual(len(digest), 64)
        saved = self.fixture.slots()
        with self.assertRaises(ValueError):
            driver.intent.begin(request, self.fixture.context)
        request.close()
        with self.assertRaises(driver.lease.Pending):
            driver.lease.Lease(self.fixture.domains)
        resumed = self.held()
        self.assertEqual(driver.intent.resume(resumed, self.fixture.context), digest)
        resumed.close()
        self.assertEqual(self.fixture.slots(), saved)
        with self.assertRaises(driver.lease.Pending):
            driver.lease.Lease(self.fixture.domains)

    def test_empty_partial_corrupt_and_oversized_slots_never_admit_or_auto_repair(self):
        self.publish()
        domain = self.fixture.domains[0]
        path, original = domain._path("intent"), domain._path("intent").read_bytes()
        for damaged in (b"", b"{partial", original[:-1], original.replace(b"maintenance-intent", b"maintenance-xxxxx"),
                        b"x" * (driver.intent.LIMIT + 1)):
            with self.subTest(size=len(damaged)):
                path.write_bytes(damaged)  # Only the test-owned control slot.
                with self.assertRaises(driver.lease.Pending):
                    driver.lease.Lease(self.fixture.domains)
                held = self.held()
                with self.assertRaises(ValueError):
                    driver.intent.resume(held, self.fixture.context)
                held.close()
                self.assertEqual(path.read_bytes(), damaged)
        path.write_bytes(original)

    def test_missing_replaced_symlink_hardlink_and_unsafe_slots_refuse(self):
        path = self.fixture.domains[0]._path("intent")
        original = self.root / "original-intent"
        path.rename(original)
        try:
            with self.assertRaises((OSError, ValueError)):
                driver.lease.Lease(self.fixture.domains)
            for kind in ("replace", "symlink", "hardlink"):
                if kind == "replace": path.write_bytes(driver.lease.IDLE_INTENT)
                if kind == "symlink": path.symlink_to(original)
                if kind == "hardlink": os.link(original, path)
                try:
                    with self.assertRaises((OSError, ValueError)):
                        driver.lease.Lease(self.fixture.domains)
                finally:
                    path.unlink()
        finally:
            original.rename(path)
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            driver.lease.Lease(self.fixture.domains)
        path.chmod(0o600)

    def test_write_and_sync_failures_cannot_return_authority_or_clear_partial_intent(self):
        for mode in ("write", "sync"):
            with self.subTest(mode=mode):
                fixture = self.new_fixture()
                held = self.held(fixture)
                original = os.pwrite
                calls = []

                def partial(fd, data, offset):
                    if calls:
                        raise OSError("synthetic partial write")
                    calls.append(True)
                    return original(fd, data[:8], offset)

                target = "pwrite" if mode == "write" else "fsync"
                effect = partial if mode == "write" else OSError("synthetic sync failure")
                with patch.object(driver.intent.os, target, side_effect=effect):
                    with self.assertRaises(OSError):
                        driver.intent.begin(held, fixture.context)
                held.close()
                saved = fixture.slots()
                with self.assertRaises(driver.lease.Pending):
                    driver.lease.Lease(fixture.domains)
                recovered = self.held(fixture)
                with self.assertRaises(ValueError):
                    driver.intent.resume(recovered, fixture.context)
                recovered.close()
                self.assertEqual(fixture.slots(), saved)
                self.assertFalse((fixture.home / "cache").exists())

    def test_shared_home_or_checkout_stays_closed_while_unrelated_scope_enters(self):
        self.publish()
        other_home, other_checkout = self.fixture.root / "other-data", self.fixture.root / "other-code"
        for path in (other_home, other_checkout):
            path.mkdir(mode=0o700)
        home = driver.lease.Domain(self.fixture.control, "home", other_home)
        checkout = driver.lease.Domain(self.fixture.control, "checkout", other_checkout)
        home.enroll()
        checkout.enroll()
        original_home = next(d for d in self.fixture.domains if d.binding["kind"] == "home")
        original_checkout = next(d for d in self.fixture.domains if d.binding["kind"] == "checkout")
        for scope in ([original_home, checkout], [home, original_checkout]):
            with self.assertRaises(driver.lease.Pending):
                driver.lease.Lease(scope)
        unrelated = driver.lease.Lease([home, checkout])
        unrelated.close()

    def test_legacy_enrollment_cannot_silently_migrate_or_reset_intent(self):
        domain = self.fixture.domains[0]
        path = domain._path("json")
        original = json.loads(path.read_bytes())
        legacy = {**original, "schema": 1}
        del legacy["intent"]
        for value in (legacy, {**original, "schema": 2.0}):
            path.write_text(json.dumps(value))
            saved = path.read_bytes()
            with self.assertRaises(ValueError):
                driver.lease.Lease(self.fixture.domains)
            self.assertEqual(path.read_bytes(), saved)
        self.assertEqual(domain._path("intent").read_bytes(), driver.lease.IDLE_INTENT)

    def test_invalid_or_oversized_expected_context_does_not_publish(self):
        held = self.held()
        oversized = json.loads(driver.intent.binding.encode(self.fixture.context))
        oversized["entry"] = "x" * driver.intent.LIMIT
        for context in (None, {}, {"run": 1}, oversized):
            with self.assertRaises(ValueError):
                driver.intent.begin(held, context)
        held.close()
        self.assertEqual(self.fixture.slots(), [driver.lease.IDLE_INTENT] * 2)
        writer = driver.lease.Lease(self.fixture.domains)
        writer.close()

    def test_cli_failure_is_bilingual_nonretryable_and_redacted(self):
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(driver.__file__)),
                                 "--candidate-root", str(self.sources)], env={"PATH": os.defpath, "HOME": str(self.root)},
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 1)
        value = json.loads(result.stdout)
        self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
        self.assertFalse(value["retryable"])
        self.assertIn("暂时无法安全升级", value["summaryZh"])
        self.assertIn("cannot be upgraded safely", value["summaryEn"])
        self.assertNotIn(str(self.root), result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
