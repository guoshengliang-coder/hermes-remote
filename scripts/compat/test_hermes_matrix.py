import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("hermes-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

class SourceContractTest(unittest.TestCase):
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
