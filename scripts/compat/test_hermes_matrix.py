import importlib.util
import contextlib
import io
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("hermes-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

class SourceContractTest(unittest.TestCase):
    def test_cli_failures_are_structured_and_do_not_echo_arguments_or_tracebacks(self):
        script = Path(__file__).with_name("hermes-matrix.py")
        with tempfile.TemporaryDirectory(prefix="hg199-matrix-error-") as directory:
            private = str(Path(directory) / "token=do-not-echo")
            cases = [[], ["--unknown=token=do-not-echo"],
                     ["--old-root", private, "--candidate-root", private],
                     ["--old-root", str(Path.home() / ".hermes"), "--candidate-root", private]]
            for args in cases:
                process = subprocess.run([sys.executable, "-I", "-S", "-B", str(script), *args],
                                         capture_output=True, text=True, timeout=10)
                self.assertEqual(process.returncode, 1)
                result = json.loads(process.stdout)
                self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
                self.assertIs(result["retryable"], False)
                self.assertIs(result["certifiesUpgrade"], False)
                self.assertTrue(result["summaryChinese"])
                self.assertTrue(result["summaryEnglish"])
                self.assertNotIn("do-not-echo", process.stdout + process.stderr)
                self.assertNotIn("Traceback", process.stdout + process.stderr)

    def test_passing_source_and_synthetic_database_checks_never_certify_an_upgrade(self):
        with tempfile.TemporaryDirectory(prefix="hg199-matrix-output-") as directory:
            for source_only in (False, True):
                with self.subTest(source_only=source_only):
                    args = ["hermes-matrix.py", "--old-root", directory + "/old", "--candidate-root", directory + "/new"]
                    if source_only:
                        args.append("--source-only")
                    output = io.StringIO()
                    with patch.object(matrix.sys, "argv", args), contextlib.redirect_stdout(output):
                        with patch.object(matrix, "source_check", return_value={"failures": []}):
                            with patch.object(matrix, "db_roundtrip", return_value={"sampleHistoryRoundtrip": True}) as database:
                                self.assertEqual(matrix.main(), 0)
                    result = json.loads(output.getvalue())
                    self.assertIn("certifiesUpgrade", result)
                    self.assertFalse(result["certifiesUpgrade"])
                    self.assertEqual(result["sourceOnly"], source_only)
                    self.assertEqual(database.call_count, 0 if source_only else 1)

    def test_failed_synthetic_stage_is_structured_without_child_exception_text(self):
        output, errors = io.StringIO(), io.StringIO()
        for failure in (RuntimeError("token=do-not-echo"), matrix.FixtureFailure("after_upgrade", -9)):
            output, errors = io.StringIO(), io.StringIO()
            with patch.object(matrix, "main", side_effect=failure):
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    self.assertEqual(matrix.cli(), 1)
            result = json.loads(output.getvalue())
            self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(result["certifiesUpgrade"])
            self.assertFalse(result["retryable"])
            self.assertNotIn("do-not-echo", output.getvalue() + errors.getvalue())
            self.assertEqual(result["diagnostics"]["category"], type(failure).__name__)
            if isinstance(failure, matrix.FixtureFailure):
                self.assertEqual(result["diagnostics"], {"category": "FixtureFailure", "stage": "after_upgrade", "exitCode": -9})

    def test_contract_mismatch_has_a_localized_primary_failure_and_keeps_diagnostic_categories(self):
        with tempfile.TemporaryDirectory(prefix="hg199-contract-refusal-") as directory:
            for source_only in (False, True):
                output = io.StringIO()
                args = ["hermes-matrix.py", "--old-root", directory, "--candidate-root", directory]
                if source_only:
                    args.append("--source-only")
                with patch.object(matrix.sys, "argv", args), contextlib.redirect_stdout(output):
                    with patch.object(matrix, "source_check", return_value={"failures": ["missing RPC prompt.submit"]}):
                        with patch.object(matrix, "db_roundtrip", return_value={}) as database:
                            self.assertEqual(matrix.main(), 1)
                            database.assert_not_called()
                result = json.loads(output.getvalue())
                self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
                self.assertTrue(result["summaryChinese"])
                self.assertTrue(result["summaryEnglish"])
                self.assertFalse(result["certifiesUpgrade"])
                self.assertEqual(result["candidate"]["failures"], ["missing RPC prompt.submit"])

    def test_inherited_keys_and_new_required_parameters_are_distinguished(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            models = root / "tui_gateway/contracts"
            models.mkdir(parents=True)
            (root / "hermes_cli/web_routers").mkdir(parents=True)
            (models / "test.py").write_text('''
class Params: pass
class Parent(Params):
    profile: str = "default"
class Prompt(Parent):
    text: str = ""
    optional: str | None = None
    required: str
    field_required: str = Field(...)
    field_optional: str | None = Field(default=None)
method("prompt.submit", params=Prompt)
''')
            (root / "hermes_cli/web_routers/session.py").write_text('''
@router.get("/api/sessions/{session_id}/messages")
def messages(): pass
''')
            methods, routes = matrix.tree_contract(root)
            self.assertEqual(methods["prompt.submit"], {"profile": False, "text": False, "optional": False, "required": True, "field_required": True, "field_optional": False})
            self.assertIn(("GET", "/api/sessions/{id}/messages"), routes)
            (models / "test.py").write_text('class Params: pass\nmethod("different", params=Params)\n')
            changed, _ = matrix.tree_contract(root)
            self.assertNotIn("prompt.submit", changed)

if __name__ == "__main__":
    unittest.main()
