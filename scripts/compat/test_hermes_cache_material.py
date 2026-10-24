"""Synthetic Git with real cache bytes, durable slots and process deaths; never a Hermes runtime."""
import copy
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

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


driver = load("cache_material_test_fixture", HERE / "upstream-maintenance-prototype/cache_material_fixture.py")
material = driver.material


class CacheMaterialTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-cache-seed-")
        self.addCleanup(temporary.cleanup)
        self.seed = Path(temporary.name).resolve()
        material.worker.git(self.seed, "init", "--quiet")
        (self.seed / "hermes_constants.py").write_text("# Synthetic source, never imported\nVALUE = 1\n")
        (self.seed / ".gitattributes").write_text("*.ps1 text eol=crlf\n")
        (self.seed / "fixture.ps1").write_bytes(b"# Synthetic PowerShell, never executed\n")
        (self.seed / 'quoted " path\n中文.txt').write_text("Synthetic Git path quoting\n")
        (self.seed / "alias.txt").symlink_to('quoted " path\n中文.txt')
        material.worker.git(self.seed, "add", ".")
        material.worker.git(self.seed, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                            "commit", "--quiet", "-m", "Synthetic cache source")
        commit = material.worker.git(self.seed, "rev-parse", "HEAD").decode().strip()
        self.case = driver.Fixture(self.seed, commit, "hermes_constants.py")
        self.addCleanup(shutil.rmtree, self.case.root)
        self.root = self.case.root
        self.held = driver.held(self.root)
        self.addCleanup(lambda: self.held.close())
        self.cache = self.root / "home/hermes-agent" / next(iter(self.case.plan["caches"]))
        self.original = self.cache.read_bytes()
        self.slots = material.joint.source.slots(self.held)

    def preserve(self):
        return material.preserve(self.held, self.case.plan, *self.case.args)

    def recheck(self, manifest):
        return material.recheck(self.held, self.case.plan, *self.case.args, manifest)

    def unchanged(self, slots=None):
        self.assertEqual(self.cache.read_bytes(), self.original)
        self.assertEqual(material.joint.source.slots(self.held), self.slots if slots is None else slots)

    def test_preserves_exact_cache_and_cold_source_without_granting_execution(self):
        with self.assertRaises(material.worker.Refused):
            material.worker.verify_source(self.root / "home/hermes-agent", self.case.commit)
        digest = self.preserve()
        observed = self.recheck(digest)
        self.assertEqual(observed["warmCachesPreserved"], 1)
        self.assertTrue(observed["coldSourceRechecked"])
        self.assertTrue(all(value is False for key, value in observed.items()
                            if key not in ("warmCachesPreserved", "coldSourceRechecked")))
        self.assertEqual((self.root / "cache-material/0.pyc").read_bytes(), self.original)
        self.assertEqual((self.root / "cold-source/fixture.ps1").read_bytes(), b"# Synthetic PowerShell, never executed\r\n")
        self.assertEqual(self.cache.read_bytes(), self.original)
        # Even after preservation the product worker still refuses original warm imports.
        with self.assertRaises(material.worker.Refused):
            material.worker.verify_source(self.root / "home/hermes-agent", self.case.commit)
        self.held.close()
        driver.blocked(self.root)

    def test_real_coordinator_death_then_fresh_process_rechecks_original_plan(self):
        self.held.close()
        digest = self.case.preserve_and_kill()
        observed = self.case.recheck_child(digest)
        self.assertTrue(observed["coldSourceRechecked"])
        self.assertFalse(observed["certifiesUpgrade"])
        driver.blocked(self.root)
        self.assertEqual(self.cache.read_bytes(), self.original)

    def test_interrupted_copy_preserves_original_and_pending_history_without_repair(self):
        self.held.close()
        self.case.preserve_and_kill("cache-copied")
        self.held = driver.held(self.root)
        self.unchanged()
        self.assertEqual((self.root / "cache-material/0.pyc").read_bytes(), self.original)
        with patch.object(material, "source_inventory", side_effect=AssertionError("must refuse history first")) as observer:
            with self.assertRaises(ValueError):
                self.recheck("0" * 64)
            observer.assert_not_called()
        with self.assertRaises(FileExistsError):
            self.preserve()
        self.unchanged()

    def test_partial_prepared_slot_after_real_death_refuses_before_git_and_never_repairs(self):
        self.held.close()
        self.case.preserve_and_kill("slot-synced")
        self.held = driver.held(self.root)
        saved = material.joint.source.slots(self.held)
        self.assertNotEqual(saved[0], saved[1])
        manifest = material.private_read(self.root / "cache-material/manifest.json")
        with patch.object(material, "source_inventory", side_effect=AssertionError("must refuse history first")) as observer:
            with self.assertRaises(ValueError):
                self.recheck(material.binding.digest(manifest))
            observer.assert_not_called()
        self.unchanged(saved)

    def test_independent_run_source_and_plan_sha_cannot_be_replaced_by_record(self):
        for args in (("00000000-0000-0000-0000-000000000000", *self.case.args[1:]),
                     (self.case.run, "0" * 40, self.case.sha), (*self.case.args[:2], "0" * 64)):
            with self.assertRaises(ValueError):
                material.preserve(self.held, self.case.plan, *args)
        changed = copy.deepcopy(self.case.plan)
        changed["schema"] = True
        with self.assertRaises(ValueError):
            material.preserve(self.held, changed, self.case.run, self.case.commit, material.binding.digest(changed))
        self.unchanged()

    def test_cache_source_and_cold_source_drift_refuse_without_overwriting_material(self):
        digest = self.preserve()
        saved = material.joint.source.slots(self.held)
        # Restoring bytes and mtime does not restore the original ctime observation.
        info = self.cache.stat()
        changed = bytearray(self.original)
        changed[-1] ^= 1
        self.cache.write_bytes(changed)
        os.utime(self.cache, ns=(info.st_atime_ns, info.st_mtime_ns))
        with self.assertRaises(ValueError):
            self.recheck(digest)
        self.assertEqual(material.joint.source.slots(self.held), saved)
        self.assertEqual((self.root / "cache-material/0.pyc").read_bytes(), self.original)

    def test_unrecognized_native_overlay_and_changed_declared_crlf_bytes_refuse(self):
        overlay = self.root / "home/hermes-agent/extra.so"
        overlay.write_bytes(b"synthetic native overlay, never loaded")
        # Ignore status for this path, as ordinary source .gitignore may already do.
        (self.root / "home/hermes-agent/.git/info/exclude").write_text("__pycache__/\nextra.so\n")
        with self.assertRaises(ValueError):
            self.preserve()
        overlay.unlink()
        powershell = self.root / "cold-source/fixture.ps1"
        powershell.write_bytes(powershell.read_bytes().replace(b"Synthetic", b"Different"))
        with self.assertRaises((ValueError, material.worker.Refused)):
            self.preserve()
        self.unchanged()

    def test_partial_started_intent_refuses_before_material_observation(self):
        fd = self.held.domains[0].intent()
        try:
            os.ftruncate(fd, len(self.slots[0]) - 1)
            os.fsync(fd)
        finally:
            os.close(fd)
        saved = material.joint.source.slots(self.held)
        with patch.object(material, "source_inventory", side_effect=AssertionError("unknown history")) as observer:
            with self.assertRaises(ValueError):
                self.preserve()
            observer.assert_not_called()
        self.unchanged(saved)

    def test_tracked_source_changes_in_warm_and_cold_copies_refuse(self):
        digest = self.preserve()
        for name in ("home/hermes-agent", "cold-source"):
            path = self.root / name / "hermes_constants.py"
            original = path.read_bytes()
            path.write_bytes(original.replace(b"VALUE = 1", b"VALUE = 2"))
            with self.assertRaises((ValueError, material.worker.Refused)):
                self.recheck(digest)
            path.write_bytes(original)
        self.assertEqual(self.cache.read_bytes(), self.original)

    def test_archive_drift_same_bytes_replacement_and_link_aliases_refuse(self):
        digest = self.preserve()
        archive = self.root / "cache-material/0.pyc"
        original = archive.read_bytes()
        archive.unlink()
        archive.write_bytes(original)
        archive.chmod(0o600)
        with self.assertRaises(ValueError):
            self.recheck(digest)
        archive.unlink()
        archive.symlink_to(self.cache)
        with self.assertRaises((ValueError, OSError)):
            self.recheck(digest)
        self.assertEqual(self.cache.read_bytes(), self.original)

    def test_unsafe_plan_records_duplicate_keys_and_cache_hardlinks_refuse(self):
        record = self.root / "control/malformed.json"
        for data in (b'{"a":1,"a":2}', b'{"a":NaN}', b' {"a":1}'):
            record.write_bytes(data)
            record.chmod(0o600)
            with self.assertRaises(ValueError):
                material.private_read(record)
        alias = self.root / "cache-alias"
        os.link(self.cache, alias)
        with self.assertRaises(ValueError):
            self.preserve()
        self.unchanged()

    def test_manifest_digest_and_plan_file_cannot_authorize_unexpected_history(self):
        digest = self.preserve()
        saved = material.joint.source.slots(self.held)
        with self.assertRaises(ValueError):
            self.recheck("0" * 64)
        manifest = self.root / "cache-material/manifest.json"
        value = material.private_read(manifest)
        value["files"][next(iter(value["files"]))]["file"] = "../../original.pyc"
        manifest.write_bytes(material.binding.encode(value))
        with self.assertRaises(ValueError):
            self.recheck(digest)
        self.unchanged(saved)


class CacheMaterialCLITest(unittest.TestCase):
    def test_cli_requires_explicit_provision_and_redacts_invalid_arguments(self):
        for args in ([], ["--candidate-root", "token=do-not-echo"],
                     ["--provision", "--unknown", "token=do-not-echo"]):
            process = subprocess.run([sys.executable, "-I", "-S", "-B",
                str(HERE / "hermes-maintenance-cache-material.py"), *args],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(process.returncode, 1)
            value = json.loads(process.stdout)
            self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
            self.assertTrue(value["summaryChinese"] and value["summaryEnglish"])
            self.assertFalse(value["retryable"] or value["certifiesUpgrade"])
            self.assertNotIn("do-not-echo", process.stdout + process.stderr)
