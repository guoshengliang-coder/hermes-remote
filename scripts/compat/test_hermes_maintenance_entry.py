"""Synthetic entry fixtures test the harness; frozen upstream runs separately in CI."""
import hashlib
import importlib.util
import json
import os
import select
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maintenance_entry", Path(__file__).with_name("hermes-maintenance-entry.py"))
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)


class MaintenanceEntryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-entry-unit-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.values = {
            "hermes_cli/__init__.py": b'"""Synthetic fixture package."""\n',
            "hermes_constants.py": b'"""Synthetic fixture constants."""\n',
            "hermes_cli/main.py": b'"""Synthetic entry."""\nimport hermes_bootstrap\ndef main(): pass\n',
            "hermes_bootstrap.py": b'''import os
from pathlib import Path
def export_scratch_tmp_env():
    (Path(os.environ["HERMES_HOME"])/"cache/scratch").mkdir(parents=True)
export_scratch_tmp_env()
''',
        }
        self.checksums = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}
        for name, data in self.values.items():
            target = self.root / name
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(data)

    def test_paired_placement_matrix_and_lifetime_never_certify_an_upgrade(self):
        with patch.dict(entry.ENTRY_SHA256, self.checksums, clear=True):
            value = entry.inspect(self.root)
        self.assertEqual(value["pairedEntryCases"], 12)
        self.assertTrue(value["earlyGuardBlocksBeforeBootstrap"])
        self.assertTrue(value["insideMainGuardStillWrites"])
        self.assertTrue(value["admittedPrefixRetainsWriterLease"])
        self.assertTrue(value["pmRecoveryInjected"])
        for name in ("fullStartupVerified", "profileSelectionVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[name])

    def test_every_copied_source_is_verified_before_execution(self):
        for name in self.values:
            target = self.root / name
            original = target.read_bytes()
            target.write_bytes(original + b"\n# changed\n")
            with patch.dict(entry.ENTRY_SHA256, self.checksums, clear=True):
                with self.assertRaises(ValueError):
                    entry.inspect(self.root)
            target.write_bytes(original)
        self.assertFalse((self.root / "cache").exists())

    def test_probe_refuses_outside_writes_and_process_spawning(self):
        marker = self.root / "not-owned-by-probe"
        for payload in (f"from pathlib import Path;Path({str(marker)!r}).mkdir()",
                        f"import subprocess,sys;subprocess.run([sys.executable,'-c',\"open({str(marker)!r},'w').close()\"])"):
            values = {**self.values, "hermes_bootstrap.py": payload.encode()}
            with self.assertRaises(ValueError):
                entry.experiment(values, "inside-main", "module", ["serve"])
            self.assertFalse(marker.exists())

    def test_unexpected_import_failure_cannot_be_misreported_as_blocked(self):
        values = {**self.values, "hermes_bootstrap.py": b'raise RuntimeError("synthetic-private-error")\n'}
        with self.assertRaises(ValueError) as failure:
            entry.experiment(values, "inside-main", "console-import", ["serve"])
        self.assertEqual(str(failure.exception), "startup probe failed")
        self.assertNotIn("synthetic-private-error", str(failure.exception))

    def test_unexpected_output_cannot_be_misreported_as_valid_evidence(self):
        values = {**self.values, "hermes_bootstrap.py": b'print(\'{"blocked":true}\',flush=True)\n'}
        with self.assertRaises(ValueError) as failure:
            entry.experiment(values, "inside-main", "module", ["serve"])
        self.assertEqual(str(failure.exception), "startup probe failed")

    def test_partial_output_obeys_deadline_without_waiting_for_a_newline(self):
        child = subprocess.Popen([sys.executable, "-I", "-S", "-B", "-c",
                                  "import sys,time;sys.stdout.write('{');sys.stdout.flush();time.sleep(10)"],
                                 env={"PATH": os.defpath, "HOME": str(self.root)},
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            self.assertTrue(select.select([child.stdout], [], [], 5)[0])
            with self.assertRaises(ValueError) as failure:
                entry.read_result(child, timeout=0.1)
            self.assertEqual(str(failure.exception), "startup probe deadline")
        finally:
            child.kill()
            child.wait(timeout=5)
            child.stdout.close()

    def test_cli_source_refusal_has_bilingual_registered_code_without_private_paths(self):
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(entry.__file__)),
                                 "--candidate-root", str(self.root)],
                                env={"PATH": os.defpath, "HOME": str(self.root)},
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
