import importlib.util
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
import json

spec = importlib.util.spec_from_file_location("scope", Path(__file__).with_name("hermes-maintenance-scope.py"))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class MaintenanceScopeTest(unittest.TestCase):
    def test_two_process_counterexample_never_certifies_global_admission(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "hermes_cli").mkdir()
            (root / "hermes_cli/backend_retirement.py").write_text('''
class Fence:
    def __init__(self): self.closed = False
    def prepare(self): return {"ok": True, "token": "synthetic"}
    def commit(self, token): self.closed = True; return {"ok": True}
    def acquire(self): return not self.closed
retirement = Fence()
''')
            self.assertEqual(scope.inspect(root), {"committedProcessRefuses": True,
                                                  "separateProcessAdmits": True, "globalAdmissionProven": False})

    def test_unrecognized_scope_or_module_failure_does_not_create_positive_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "hermes_cli").mkdir()
            (root / "hermes_cli/backend_retirement.py").write_text('''
class Fence:
    def prepare(self): return {"ok": True, "token": "synthetic"}
    def commit(self, token): return {"ok": True}
    def acquire(self): return False
retirement = Fence()
''')
            with self.assertRaises(ValueError):
                scope.inspect(root)
            (root / "hermes_cli/backend_retirement.py").write_text('raise RuntimeError("synthetic")')
            with self.assertRaises(subprocess.CalledProcessError):
                scope.inspect(root)

    def test_cli_failure_has_registered_bilingual_code_and_no_raw_source_details(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-private-scope-") as directory:
            result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(scope.__file__)),
                                     "--old-root", directory, "--candidate-root", directory],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 1)
            value = json.loads(result.stdout)
            self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(value["retryable"])
            self.assertIn("暂时无法安全升级", value["summaryZh"])
            self.assertIn("cannot be upgraded safely", value["summaryEn"])
            self.assertNotIn(directory, result.stdout + result.stderr)
            self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
