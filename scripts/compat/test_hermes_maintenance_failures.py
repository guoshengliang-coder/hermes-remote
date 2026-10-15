"""Death fixtures and uncertain commit regressions; real upstream prefixes run in CI."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


driver = load("failure_driver", Path(__file__).with_name("hermes-maintenance-failures.py"))


class HandoffFailuresTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-failure-fixture-")
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
        for name, data in self.values.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}

    def test_uncertain_prefix_and_real_sigkill_matrix_preserves_explicit_limits(self):
        with patch.dict(driver.bootstrap.BOOTSTRAP_SHA256, self.hashes, clear=True):
            value = driver.inspect(self.root)
        self.assertEqual(value["uncertainFrozenPrefixes"], 8)
        self.assertEqual(value["syntheticDeathCases"], 9)
        self.assertTrue(value["uncertaintyRetainsParentCustody"])
        self.assertTrue(value["survivorCustodyVerified"])
        self.assertTrue(value["allCustodiansGoneAdmitsWriter"])
        for key in ("productionAuthorityVerified", "durableHandoffVerified", "nativePreloadVerified",
                    "cronPackageBootVerified", "fullStartupVerified",
                    "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[key])

    def test_child_receipt_cannot_release_an_uncertain_parent_even_if_child_later_exits(self):
        original = Path(driver.entry.__file__).read_text()
        needle = 'or (bound and handshake)'
        self.assertEqual(original.count(needle), 2)
        path = self.root / "mutant-entry.py"
        path.write_text(original.replace(needle, 'or (bound and value["boundIdentityVerified"])'))
        mutant = load("mutant_entry", path)
        mutant.PROTOTYPE = driver.entry.PROTOTYPE
        value = mutant.experiment(self.values, "shared-bootstrap", "module", [],
                                  role="bound-recovery", handoff_fault="lost-final-ack")
        self.assertTrue(value["boundIdentityVerified"])
        self.assertFalse(value["liveHandshakeAccepted"])
        self.assertFalse(value["uncertaintyBlocksAfterChildExit"])
        # The unmodified implementation must retain its original lease throughout.
        normal = driver.entry.experiment(self.values, "shared-bootstrap", "module", [],
                                         role="bound-recovery", handoff_fault="lost-final-ack")
        self.assertTrue(normal["uncertaintyBlocksAfterChildExit"])

    def test_commit_send_failure_is_uncertain_before_any_successful_send(self):
        binding = driver.actor.binding
        context, challenge, transfer = {"fixture": True}, "a" * 64, "b" * 64
        digest = binding.digest(context)
        replies = [binding.packet("ready", digest, challenge), binding.packet("accepted", digest, challenge, transfer)]
        sent = []

        def send(_, value):
            sent.append(value["type"])
            if value["type"] == "commit":
                raise BrokenPipeError("synthetic failure")

        with patch.object(binding, "receive", side_effect=replies), patch.object(binding, "send", side_effect=send), \
                patch.object(binding.secrets, "token_hex", return_value=transfer):
            with self.assertRaises(binding.Uncertain):
                binding.issue(None, context)
        self.assertEqual(sent, ["grant", "commit"])

    def test_precommit_transport_failure_is_not_commit_uncertainty(self):
        binding = driver.actor.binding
        with patch.object(binding, "receive", side_effect=BrokenPipeError("synthetic failure")):
            with self.assertRaises(BrokenPipeError):
                binding.issue(None, {"fixture": True})

    def test_death_scope_and_control_frame_deadline_are_bounded(self):
        for victim, phase in (("foreign", "ready"), ("parent", "unknown"), ("both", "ready")):
            with self.assertRaises(ValueError):
                driver.death_case(victim, phase)
        incoming, outgoing = os.pipe()
        try:
            os.write(outgoing, b'{"partial":')
            with self.assertRaises(ValueError):
                driver.actor.read(incoming, timeout=0.02)
        finally:
            os.close(incoming)
            os.close(outgoing)

    def test_cli_error_remains_bilingual_nonretryable_and_redacted(self):
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
