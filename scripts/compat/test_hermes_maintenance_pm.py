"""PM observation boundaries use synthetic records/runtime reports unless stated."""
import hashlib
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("pm_driver", Path(__file__).with_name("hermes-maintenance-pm.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
observer = driver.load("pm_observation_test", driver.PROTOTYPE / "pm_generation.py")

SELECTOR = '''
import os,json,hashlib
from pathlib import Path
def runtime_facts_path(root):
    return Path(os.environ['HERMES_HOME'])/'installs'/hashlib.sha256(str(root).encode()).hexdigest()[:16]/'facts.json'
def committed_venv(root):
    return Path(json.loads(runtime_facts_path(root).read_text())['packages']['venv']['environment'])
def site_packages(root): return root/'lib/python3.11/site-packages'
def venv_python(root): return root/'bin/python'
'''


class PMGenerationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-pm-generation-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.checkout = self.root / "checkout"
        for name in ("control", "user", "data", "checkout", "tmp"):
            (self.root / name).mkdir(mode=0o700)
        self.marker = self.root / ".hg199-isolated-pm-generation"
        self.marker.write_bytes(observer.MARKER)
        self.marker.chmod(0o600)
        values = {"pm/__init__.py": b'"""Synthetic selector."""\n', "hermes_constants.py": b'"""Unused synthetic constants."""\n',
                  "pm/environments.py": SELECTOR.encode()}
        for name, data in values.items():
            path = self.checkout / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_bytes(data)
        hashes = {name: hashlib.sha256(data).hexdigest() for name, data in values.items()}
        p = patch.dict(observer.PINNED, hashes, clear=True)
        p.start()
        self.addCleanup(p.stop)
        key = hashlib.sha256(str(self.checkout).encode()).hexdigest()[:16]
        self.state = self.root / "data/installs" / key
        self.venv = self.state / "environments/gen-test/venv"
        self.site = self.venv / "lib/python3.11/site-packages"
        (self.site / "packaging").mkdir(parents=True)
        self.package = self.site / "packaging/version.py"
        self.package.write_bytes(b"# synthetic package bytes\n")
        self.base = self.root / "data/tools/python-runtime"
        (self.base / "bin").mkdir(parents=True)
        self.binary = self.base / "bin/python"
        self.binary.write_bytes(b"NOT EXECUTED: synthetic interpreter\n")
        self.binary.chmod(0o700)
        (self.venv / "bin").mkdir()
        self.python = self.venv / "bin/python"
        self.python.symlink_to(self.binary)
        (self.venv / "pyvenv.cfg").write_text("version = 3.11.15\n")
        self.facts = self.state / "facts.json"
        self.facts.write_text(json.dumps({"packages": {"venv": {"environment": str(self.venv)}}}))
        for name in observer.INPUTS:
            path, recorded = self.checkout / name, self.state / "inputs" / name
            path.parent.mkdir(exist_ok=True, parents=True)
            recorded.parent.mkdir(exist_ok=True, parents=True)
            path.write_text("synthetic locked input\n")
            recorded.write_bytes(b"")
            os.utime(recorded, ns=(path.stat().st_mtime_ns,) * 2)
        (self.state / "inputs/.project-root").write_text(str(self.checkout))
        self.held = driver.held(self.root, enroll=True)
        self.addCleanup(self.held.close)
        original = observer.child
        self.report = {"executable": str(self.python), "base": str(self.base), "version": [3, 11, 15],
                       "packaging": "test-only", "module": str(self.package), "ssl": "test-only", "sqlite": "test-only",
                       "hash": hashlib.sha256(b"HG199 runtime probe").hexdigest()}

        def child(root, executable, code, *args, **kwargs):
            if code == observer.RUNTIME:
                return dict(self.report)  # Unit fixture ONLY; opt-in driver never uses this seam.
            return original(root, executable, code, *args, **kwargs)
        p = patch.object(observer, "child", side_effect=child)
        self.calls = p.start()
        self.addCleanup(p.stop)
        self.original_child = original

    def observed(self):
        return observer.observe(self.held)

    def test_observation_rechecks_actual_files_without_publishing_or_clearing_intent(self):
        slots = [d._path("intent").read_bytes() for d in self.held.domains]
        value = self.observed()
        observer.revalidate(self.held, value)
        self.assertGreater(value["environmentEntries"], 5)
        self.assertGreater(value["runtimeEntries"], 2)
        self.assertEqual([d._path("intent").read_bytes() for d in self.held.domains], slots)
        for fake in (None, True, {"selectedPMGenerationObserved": True}):
            with self.assertRaises(ValueError):
                observer.revalidate(self.held, fake)

    def test_drift_matrix_restores_fixture_bytes_and_refuses_three_changes(self):
        saved = self.facts.read_bytes(), (self.checkout / "uv.lock").read_bytes()
        self.assertEqual(driver.drift_cases(observer, self.held, ()), 3)
        self.assertEqual((self.facts.read_bytes(), (self.checkout / "uv.lock").read_bytes()), saved)
        self.assertFalse(list(self.site.glob('.hg199-probe-*')))
        value = self.observed()
        observer.revalidate(self.held, value)

    def test_changed_dependency_same_generation_and_version_is_not_reusable(self):
        path = self.site / "unrelated_dependency.py"
        path.write_bytes(b"before\n")
        value = self.observed()
        mtime = path.stat().st_mtime_ns
        path.write_bytes(b"after!\n")
        os.utime(path, ns=(mtime, mtime))
        with self.assertRaises(ValueError):
            observer.revalidate(self.held, value)
        # Negative control: omitting content observations misses exactly this change.
        with patch.object(observer, "tree", return_value={"digest": "0" * 64, "entries": 0}):
            value = self.observed()
            path.write_bytes(b"again!\n")
            observer.revalidate(self.held, value)

    def test_changed_runtime_binary_and_selection_are_not_reusable(self):
        value = self.observed()
        data = self.binary.read_bytes()
        self.binary.write_bytes(data + b"changed")
        with self.assertRaises(ValueError):
            observer.revalidate(self.held, value)
        self.binary.write_bytes(data)
        value = self.observed()
        self.facts.write_text(self.facts.read_text() + "\n")
        with self.assertRaises(ValueError):
            observer.revalidate(self.held, value)

    def test_activation_input_hash_stamp_project_and_missing_cfg_refuse(self):
        value = self.observed()
        path = self.checkout / "uv.lock"
        old = path.read_bytes()
        mtime = path.stat().st_mtime_ns
        path.write_bytes(old + b"edit")
        os.utime(path, ns=(mtime, mtime))
        with self.assertRaises(ValueError):
            observer.revalidate(self.held, value)
        path.write_bytes(old)
        with self.assertRaises(ValueError):
            self.observed()  # Recorded nanosecond mtime is stale even after bytes restored.
        os.utime(path, ns=(mtime, mtime))
        (self.state / "inputs/.project-root").write_text("another checkout")
        with self.assertRaises(ValueError):
            self.observed()
        (self.state / "inputs/.project-root").write_text(str(self.checkout))
        (self.venv / "pyvenv.cfg").unlink()
        with self.assertRaises(FileNotFoundError):
            self.observed()

    def test_fixture_scope_marker_and_exclusive_custody_are_required(self):
        self.marker.chmod(0o644)
        with self.assertRaises(ValueError):
            self.observed()
        self.marker.chmod(0o600)
        self.held.close()
        with self.assertRaises(ValueError):
            self.observed()
        with self.assertRaises(ValueError):
            observer.fixture(self.root / "user")

    def test_unpublished_activation_stamps_are_explicit_and_never_reconstructed(self):
        shutil.rmtree(self.state / "inputs")
        value = self.observed()
        self.assertFalse(value["activationInputsCurrent"])
        self.assertFalse((self.state / "inputs").exists())
        observer.revalidate(self.held, value)
        (self.state / "inputs").mkdir()
        with self.assertRaises(FileNotFoundError):
            self.observed()  # Partial publication is not the known unstamped case.

    def test_source_drift_refuses_before_any_copied_selector_child(self):
        path = self.checkout / "pm/environments.py"
        path.write_bytes(path.read_bytes() + b"# drift\n")
        with self.assertRaises(ValueError):
            self.observed()
        self.calls.assert_not_called()

    def test_runtime_reports_must_identify_expected_interpreter_and_selected_module(self):
        for field, bad in (("executable", str(self.binary)), ("base", str(self.root / "user")),
                           ("module", str(self.binary)), ("hash", "saved-receipt"), ("version", [True, 11, 0]),
                           ("version", [3, 12, 0])):
            old = self.report[field]
            self.report[field] = bad
            with self.assertRaises(ValueError):
                self.observed()
            self.report[field] = old
        self.python.unlink()
        self.python.symlink_to(sys.executable)
        with self.assertRaises(ValueError):
            self.observed()

    def test_tree_escape_special_permissions_and_budgets_refuse(self):
        bad = self.site / "bad"
        bad.symlink_to(self.root / "user")
        with self.assertRaises(ValueError):
            self.observed()
        bad.unlink()
        os.mkfifo(bad)
        with self.assertRaises(ValueError):
            self.observed()
        bad.unlink()
        self.package.chmod(0o666)
        with self.assertRaises(ValueError):
            self.observed()
        self.package.chmod(0o644)
        for name in ("MAX_BYTES", "MAX_ENTRIES"):
            with patch.object(observer, name, 1):
                with self.assertRaises(ValueError):
                    self.observed()

    def test_uv_empty_lock_is_observed_only_under_private_fixture_root(self):
        path = self.venv / ".lock"
        path.write_bytes(b"")
        path.chmod(0o666)
        self.observed()
        path.write_bytes(b"not coordination metadata")
        with self.assertRaises(ValueError):
            self.observed()
        path.write_bytes(b"")
        self.root.chmod(0o755)
        with self.assertRaises(ValueError):
            self.observed()
        self.root.chmod(0o700)

    def test_real_child_nonzero_extra_output_and_side_effect_refuse(self):
        for code in ('print("{}",flush=True); raise SystemExit(7)',
                     'print("{}",flush=True); print("{}",flush=True)',
                     'import os,sys; ' + observer.READ_ONLY + '\nopen("unexpected", "w")'):
            with self.assertRaises(ValueError):
                self.original_child(self.root, sys.executable, code)
        self.assertFalse((self.root / "unexpected").exists())

    def test_cli_error_is_bilingual_nonretryable_and_redacted(self):
        value = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(driver.__file__)),
                                "--audit-source", str(self.checkout)], capture_output=True, text=True, timeout=5)
        self.assertEqual(value.returncode, 1)
        result = json.loads(value.stdout)
        self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
        self.assertIs(result["retryable"], False)
        self.assertEqual(result["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
        self.assertEqual(result["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
        self.assertEqual(result["reason"], "pm-generation-observation-needs-review")
        self.assertNotIn(str(self.root), value.stdout + value.stderr)
        self.assertNotIn("Traceback", value.stdout + value.stderr)
        with patch.object(driver, 'provision', return_value=self.root), \
                patch.object(driver, 'inspect', return_value={'ordinaryWriterBlocked': False}), \
                patch.object(sys, 'argv', ['probe', '--provision', '--candidate-root', str(self.checkout)]):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(driver.main(), 1)
            self.assertEqual(json.loads(output.getvalue())['code'], 'HR-HERMESUPDATE-002')

    def test_sandbox_profile_reuse_never_overwrites_changed_or_linked_files(self):
        path = driver.profile_file(self.root, 'observer.sb', b'expected')
        self.assertEqual(driver.profile_file(self.root, 'observer.sb', b'expected'), path)
        path.write_bytes(b'other agent content')
        with self.assertRaises(ValueError):
            driver.profile_file(self.root, 'observer.sb', b'expected')
        self.assertEqual(path.read_bytes(), b'other agent content')
        path.unlink()
        target = self.root / 'preserved'
        target.write_bytes(b'preserved')
        path.symlink_to(target)
        with self.assertRaises(ValueError):
            driver.profile_file(self.root, 'observer.sb', b'expected')
        self.assertEqual(target.read_bytes(), b'preserved')


if __name__ == "__main__":
    unittest.main()
