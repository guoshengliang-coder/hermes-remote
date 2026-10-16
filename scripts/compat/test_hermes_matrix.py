import importlib.util
from pathlib import Path
import tempfile
import unittest
spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("hermes-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

class SourceContractTest(unittest.TestCase):
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
