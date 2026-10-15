"""Live handoff regression tests; real frozen upstream inputs run separately in CI."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("handoff_driver", Path(__file__).with_name("hermes-maintenance-handoff.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
spec = importlib.util.spec_from_file_location("bound_handoff", driver.entry.PROTOTYPE / "bound_handoff.py")
binding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding)


class LiveHandoffTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-live-binding-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.values = {name: b'"""Synthetic package."""\n' for name in driver.bootstrap.BOOTSTRAP_SHA256}
        self.values["hermes_bootstrap.py"] = b'''"""Synthetic bootstrap."""
from __future__ import annotations
import os
from pathlib import Path
def export_scratch_tmp_env():
    (Path(os.environ["HERMES_HOME"])/"cache/scratch").mkdir(parents=True)
export_scratch_tmp_env()
'''
        for module in driver.bootstrap.ENTRIES:
            self.values[module.replace(".", "/") + ".py"] = b'"""Synthetic entry."""\nimport hermes_bootstrap\ndef main(): pass\n'
        for name, data in self.values.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}

    def test_live_matrix_binds_identity_and_preserves_custody_without_certification(self):
        with patch.dict(driver.bootstrap.BOOTSTRAP_SHA256, self.hashes, clear=True):
            value = driver.inspect(self.root)
        self.assertEqual(value["boundRecoveryPrefixes"], 5)
        self.assertEqual(value["rejectedBindingCases"], 13)
        self.assertTrue(value["liveBindingChecksVerified"])
        self.assertTrue(value["rejectionsRetainParentCustody"])
        for key in ("productionAuthorityVerified", "durableHandoffVerified", "nativePreloadVerified",
                    "cronPackageBootVerified", "fullStartupVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[key])

    def test_refusals_happen_before_even_package_initialization(self):
        self.values["hermes_cli/__init__.py"] = b'import os\nfrom pathlib import Path\n(Path(os.environ["HERMES_HOME"])/"unexpected-import").touch()\n'
        for fault in driver.entry.BINDING_FAULTS:
            with self.subTest(fault=fault):
                value = driver.entry.experiment(self.values, "shared-bootstrap", "module", [],
                                                role="bound-recovery", handoff_fault=fault)
                self.assertTrue(value["identityRejected"])
                self.assertTrue(value["parentRetainedAfterRejection"])
                self.assertEqual(value["homeMutationAttempts"], 0)
                self.assertFalse(value["bootstrapEntered"])

    def test_actual_source_validation_is_required_not_just_matching_context_json(self):
        # A disposable mutant bypasses all actual-file checks, while preserving
        # channel challenges and kernel lock validation. It must expose the fault.
        prototype = self.root / "mutant"
        prototype.mkdir(mode=0o700)
        for name in ("startup_probe.py", "maintenance_lease.py", "bound_handoff.py"):
            shutil.copyfile(driver.entry.PROTOTYPE / name, prototype / name)
        target = prototype / "bound_handoff.py"
        text = target.read_text()
        start = text.index("    keys =", text.index("def validate("))
        end = text.index("\n\ndef send(", start)
        target.write_text(text[:start] + "    return\n" + text[end:])
        with patch.object(driver.entry, "PROTOTYPE", prototype):
            value = driver.entry.experiment(self.values, "shared-bootstrap", "module", [],
                                            role="bound-recovery", handoff_fault="changed-source")
        self.assertFalse(value["identityRejected"])
        self.assertTrue(value["liveHandshakeAccepted"])
        self.assertTrue(value["scratchCreated"])

    def test_context_descriptor_is_readonly_private_unlinked_and_bounded(self):
        fd = binding.private_context_fd(self.root, {"sample": "value"})
        self.addCleanup(os.close, fd)
        self.assertEqual(binding.read_context(fd), {"sample": "value"})
        self.assertFalse(os.get_inheritable(fd))
        with self.assertRaises(OSError):
            os.write(fd, b"changed")
        path = self.root / "public-context"
        path.write_bytes(binding.encode({"sample": "value"}))
        path.chmod(0o600)
        for flags in (os.O_RDONLY, os.O_RDWR):
            other = os.open(path, flags)
            try:
                with self.assertRaises(binding.Rejected):
                    binding.read_context(other)
            finally:
                os.close(other)
        with self.assertRaises(binding.Rejected):
            binding.private_context_fd(self.root, {"oversize": "x" * binding.LIMIT})
        self.assertFalse(list(self.root.glob(".live-context-*")))

    def test_context_refuses_source_change_symlink_escape_and_directory_replacement(self):
        home = self.root / "data"
        home.mkdir(mode=0o700)
        records = [[str(self.root), "home", str(home)], [str(self.root), "checkout", str(self.root)]]
        run_id = str(uuid.uuid4())
        value = binding.context(run_id, driver.entry.SOURCE_ID, self.root, home, "hermes_cli.main", records, list(self.values))
        binding.validate(value, run_id, driver.entry.SOURCE_ID, self.root, home, "hermes_cli.main", records)
        source = self.root / "hermes_constants.py"
        original = source.read_bytes()
        source.write_bytes(original + b"# changed\n")
        with self.assertRaises(binding.Rejected):
            binding.validate(value, run_id, driver.entry.SOURCE_ID, self.root, home, "hermes_cli.main", records)
        source.unlink()
        source.symlink_to(self.root / "hermes_bootstrap.py")
        with self.assertRaises(binding.Rejected):
            binding.snapshot(self.root, ["hermes_constants.py"])
        source.unlink()
        source.write_bytes(original)
        home.rename(self.root / "old-data")
        home.mkdir(mode=0o700)
        with self.assertRaises(binding.Rejected):
            binding.validate(value, run_id, driver.entry.SOURCE_ID, self.root, home, "hermes_cli.main", records)
        for names in (["../escape"], ["/absolute"], ["agent//file"], [], [True]):
            with self.assertRaises(binding.Rejected):
                binding.snapshot(self.root, names)

    def test_decode_and_packets_reject_duplicates_noncanonical_and_boolean_schema(self):
        for data in (b'{"a":1,"a":2}', b'{"a": 1}', b'{"a":NaN}', b'\xff', b'{}\n{}', b'[' * 2000):
            with self.assertRaises(binding.Rejected):
                binding.decode(data)
        expected = binding.packet("ready", "b" * 64, "c" * 64)
        self.assertFalse(binding.matches({**expected, "schema": True}, expected))
        self.assertFalse(binding.matches({**expected, "extra": 1}, expected))
        self.assertFalse(binding.matches({**expected, "challenge": "d" * 64}, expected))

    def test_channel_bounds_eof_partial_frame_deadline_and_multiple_frames(self):
        for payload in (b'{"partial":', b'{}\n{}\n', b"x" * (binding.LIMIT + 1)):
            left, right = socket.socketpair()
            sender = threading.Thread(target=right.sendall, args=(payload,), daemon=True)
            try:
                sender.start()
                with self.assertRaises((binding.Rejected, OSError)):
                    binding.receive(left, timeout=0.05)
            finally:
                sender.join(timeout=1)
                left.close()
                right.close()
            self.assertFalse(sender.is_alive())
        left, right = socket.socketpair()
        right.close()
        try:
            with self.assertRaises(binding.Rejected):
                binding.receive(left)
            with self.assertRaises(binding.Rejected):
                binding.send(left, {"oversize": "x" * binding.LIMIT})
        finally:
            left.close()

    def test_role_and_maintenance_are_explicit(self):
        for role, maintenance, fault in (("unknown", True, None), ("bound-recovery", False, None),
                                         ("writer", True, "wrong-run"), ("bound-recovery", True, "unknown")):
            with self.assertRaises(ValueError):
                driver.entry.experiment(self.values, "shared-bootstrap", "module", [],
                                        role=role, maintenance=maintenance, handoff_fault=fault)

    def test_cli_drift_error_is_bilingual_nonretryable_and_redacted(self):
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(driver.__file__)),
                                 "--candidate-root", str(self.root)], env={"PATH": os.defpath, "HOME": str(self.root)},
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
