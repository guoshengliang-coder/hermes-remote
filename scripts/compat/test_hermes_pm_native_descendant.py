"""Portable custody/context tests; native UV/PEP 517 execution is a separate opt-in Mac probe."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


native = load("native_descendant_test", HERE / "upstream-maintenance-prototype/pm_native_descendant.py")
lease = load("native_descendant_lease_test", HERE / "upstream-maintenance-prototype/maintenance_lease.py")


class NativeDescendantTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="hg199-pm-descendant-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        for name in ("data", "checkout", "control", "tmp", "user", "cache", "probe-venv/bin"):
            (self.root / name).mkdir(parents=True, mode=0o700)
        self.marker = self.root / ".hg199-native-descendant"
        self.marker.write_bytes(native.MARKER)
        self.marker.chmod(0o600)
        self.domains = [lease.Domain(self.root / "control", k, self.root / n)
                        for k, n in (("home", "data"), ("checkout", "checkout"))]
        for domain in self.domains:
            domain.enroll()
        self.held = lease.Lease(self.domains, maintenance=True)
        self.addCleanup(self.held.close)
        self.assertTrue(self.held.drain())
        self.uv = self.root / "synthetic-uv"
        requirements = self.root / "tmp/requirements.txt"
        requirements.write_text("synthetic-local-only\n")
        self.argv = [str(self.uv), "pip", "install", "--no-config", "--python",
                     str(self.root / "probe-venv/bin/python"), "--requirements", str(requirements), "--offline"]
        self.options = {"cwd": str(requirements.parent), "env": {
            "HOME": str(self.root / "user"), "HERMES_HOME": str(self.root / "data"),
            "UV_PROJECT_ENVIRONMENT": str(self.root / "probe-venv"), "UV_CACHE_DIR": str(self.root / "cache"),
            "UV_PYTHON_DOWNLOADS": "never"}, "stdout": subprocess.PIPE, "stderr": subprocess.PIPE,
            "text": True, "encoding": "utf-8", "errors": "replace"}

    def test_narrow_spawn_keeps_close_fds_and_only_passes_live_enrolled_descriptions(self):
        observed = native.reviewed_kwargs(self.held, self.uv, self.argv, self.options)
        self.assertTrue(observed["close_fds"])
        self.assertEqual(observed["pass_fds"], self.held.pass_fds)
        self.assertEqual({k: v for k, v in observed.items() if k not in ("close_fds", "pass_fds")}, self.options)
        for fd in self.held.pass_fds:
            os.fstat(fd)
        # Validation's temporary duplicates must not unlock or retain the original custody.
        with self.assertRaises(BlockingIOError):
            lease.Lease(self.domains, maintenance=True)
        self.held.close()
        fresh = lease.Lease(self.domains, maintenance=True)
        fresh.close()

    def test_changed_command_environment_or_escaping_requirements_refuse_before_spawn(self):
        for index, value in [(0, "/usr/bin/uv"), (1, "run"), (5, "/tmp/outside-python"), (8, "--online")]:
            argv = self.argv.copy()
            argv[index] = value
            with self.assertRaises(ValueError):
                native.reviewed_kwargs(self.held, self.uv, argv, self.options)
        for key, value in [("cwd", "/tmp"), ("shell", True), ("pass_fds", (123,)), ("preexec_fn", lambda: None)]:
            with self.assertRaises(ValueError):
                native.reviewed_kwargs(self.held, self.uv, self.argv, {**self.options, key: value})
        for key, value in [("HOME", "/tmp"), ("UV_PROJECT_ENVIRONMENT", "/tmp"), ("UV_CACHE_DIR", "/tmp"),
                           ("UV_PYTHON_DOWNLOADS", "automatic"), ("PYTHONPATH", "untrusted")]:
            options = copy.deepcopy(self.options)
            options["env"][key] = value
            with self.assertRaises(ValueError):
                native.reviewed_kwargs(self.held, self.uv, self.argv, options)
        outside = self.root / "outside-requirements"
        outside.write_text("not-in-selected-tmp")
        selected = Path(self.argv[7])
        selected.unlink()
        selected.symlink_to(outside)
        with self.assertRaises(ValueError):
            native.reviewed_kwargs(self.held, self.uv, self.argv, self.options)

    def test_unmarked_or_closed_custody_is_not_authority(self):
        self.marker.write_bytes(b"different scope\n")
        with self.assertRaises(ValueError):
            native.scope(self.held)
        self.marker.write_bytes(native.MARKER)
        self.marker.chmod(0o644)
        with self.assertRaises(ValueError):
            native.scope(self.held)
        self.marker.chmod(0o600)
        self.held.close()
        with self.assertRaises(ValueError):
            native.scope(self.held)

    def test_cli_requires_opt_in_and_maps_parser_failures_without_echoing_input(self):
        script = HERE / "hermes-maintenance-pm-native.py"
        for args in [[], ["--source-root", "secret=not-display", "--prepared-root", "never-read", "--mode", "raw"],
                     ["--source-root", "never-read", "--prepared-root", "never-read", "--mode", "token=not-display"]]:
            process = subprocess.run([sys.executable, "-I", "-S", "-B", str(script), *args], capture_output=True, text=True)
            self.assertEqual(process.returncode, 1)
            result = json.loads(process.stdout)
            self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(result["retryable"])
            self.assertTrue(result["summaryChinese"])
            self.assertTrue(result["summaryEnglish"])
            self.assertNotIn("not-display", process.stdout + process.stderr)

    def test_only_a_new_post_death_heartbeat_proves_backend_liveness(self):
        cli = load("native_descendant_cli_test", HERE / "hermes-maintenance-pm-native.py")
        prior = {"pid": 123, "descriptorsMatch": True, "sequence": 0, "backendAlive": True}
        post_death = {**prior, "sequence": 10}
        with patch.object(cli, "status", side_effect=[post_death, post_death, {**prior, "sequence": 11}]) as read:
            with patch.object(cli.time, "sleep"):
                self.assertEqual(cli.fresh_heartbeat(self.root, prior)["sequence"], 11)
            self.assertEqual(read.call_count, 3)
        # Old sequence 10 exceeds the pre-death 0, but a stalled file after death is not live.
        with patch.object(cli, "status", return_value=post_death):
            with patch.object(cli.time, "monotonic", side_effect=[0, 3]):
                with self.assertRaises(ValueError):
                    cli.fresh_heartbeat(self.root, prior)
        with patch.object(cli, "status", side_effect=[post_death, {**post_death, "pid": 456, "sequence": 11}]):
            with self.assertRaises(ValueError):
                cli.fresh_heartbeat(self.root, prior)


if __name__ == "__main__":
    unittest.main()
