"""Kernel custody tests and synthetic bootstrap fixtures; frozen sources run separately."""
import fcntl
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

spec = importlib.util.spec_from_file_location("bootstrap_driver", Path(__file__).with_name("hermes-maintenance-bootstrap.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
spec = importlib.util.spec_from_file_location("inherited_lease", driver.entry.PROTOTYPE / "maintenance_lease.py")
lease = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lease)


class MaintenanceInheritanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-inherited-role-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.control, self.home, self.checkout = [self.root / name for name in ("control", "home", "checkout")]
        for path in (self.control, self.home, self.checkout):
            path.mkdir(mode=0o700)
        self.domains = [lease.Domain(self.control, kind, path) for kind, path in
                        (("home", self.home), ("checkout", self.checkout))]
        for domain in self.domains:
            domain.enroll()

    def maintenance(self):
        value = lease.Lease(self.domains, maintenance=True)
        self.addCleanup(value.close)
        self.assertTrue(value.drain())
        return value

    def assert_closed(self):
        for maintenance in (False, True):
            with self.assertRaises(BlockingIOError):
                lease.Lease(self.domains, maintenance=maintenance)

    def test_adopted_duplicates_remain_exclusive_after_owner_closes(self):
        parent = self.maintenance()
        adopted = lease.Lease.inherit_maintenance(list(reversed(self.domains)), parent.pass_fds)
        self.addCleanup(adopted.close)
        self.assertTrue(adopted.ready)
        self.assertTrue(adopted.maintenance)
        self.assertTrue(all(not os.get_inheritable(fd) for fd in adopted.pass_fds))
        self.assertTrue(set(adopted.pass_fds).isdisjoint(parent.pass_fds))
        parent.close()
        self.assert_closed()
        adopted.close()
        after = self.maintenance()
        self.assertTrue(after.ready)

    def test_shared_writer_cannot_be_used_as_recovery_and_blocks_its_own_drain(self):
        writer = lease.Lease(self.domains)
        self.addCleanup(writer.close)
        request = lease.Lease(self.domains, maintenance=True)
        self.addCleanup(request.close)
        self.assertFalse(request.drain())
        with self.assertRaises(lease.Rejected):
            lease.Lease.inherit_maintenance(self.domains, writer.pass_fds)
        writer.close()
        self.assertTrue(request.drain())
        adopted = lease.Lease.inherit_maintenance(self.domains, request.pass_fds)
        self.addCleanup(adopted.close)
        self.assertTrue(adopted.ready)

    def test_same_inode_fresh_descriptors_do_not_impersonate_the_held_description(self):
        parent = self.maintenance()
        fresh = [fd for domain in parent.domains for fd in domain.open()]
        self.addCleanup(lambda: [os.close(fd) for fd in fresh])
        with self.assertRaises(BlockingIOError):
            lease.Lease.inherit_maintenance(self.domains, fresh)
        self.assert_closed()
        self.assertTrue(all(fcntl.fcntl(fd, fcntl.F_GETFD) >= 0 for fd in fresh))

    def test_malformed_closed_readonly_and_foreign_descriptors_preserve_owner(self):
        parent = self.maintenance()
        descriptors = parent.pass_fds
        for invalid in ((), descriptors[:-1], (True, *descriptors[1:]), (0, *descriptors[1:]),
                        (descriptors[0],) * len(descriptors)):
            with self.assertRaises(lease.Rejected):
                lease.Lease.inherit_maintenance(self.domains, invalid)
        stale = os.dup(descriptors[0])
        os.close(stale)
        # Duplication itself can reuse the closed integer; either way the pinned
        # identities must reject it, without relying on a descriptor number alone.
        with self.assertRaises((OSError, lease.Rejected)):
            lease.Lease.inherit_maintenance(self.domains, (*descriptors[:-1], stale))
        read_only = os.open(parent.domains[0]._path("gate"), os.O_RDONLY)
        foreign = os.open(self.root / "other", os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        try:
            for fd in (read_only, foreign):
                with self.assertRaises(lease.Rejected):
                    lease.Lease.inherit_maintenance(self.domains, (fd, *descriptors[1:]))
        finally:
            os.close(read_only)
            os.close(foreign)
        self.assert_closed()
        adopted = lease.Lease.inherit_maintenance(self.domains, descriptors)
        adopted.close()
        self.assert_closed()  # Closing a rejected/adopted copy never unlocks the owner.

    def test_replaced_binding_refuses_adoption_without_closing_original_descriptors(self):
        parent = self.maintenance()
        descriptors = parent.pass_fds
        domain = parent.domains[0]
        domain._path("gate").rename(self.root / "old-gate")
        fd = os.open(domain._path("gate"), os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        os.close(fd)
        with self.assertRaises(lease.Rejected):
            lease.Lease.inherit_maintenance(self.domains, descriptors)
        self.assertTrue(all(fcntl.fcntl(fd, fcntl.F_GETFD) >= 0 for fd in descriptors))


class SharedBootstrapHarnessTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hg199-shared-fixtures-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.values = {name: b'"""Synthetic package."""\n' for name in driver.BOOTSTRAP_SHA256}
        self.values["hermes_bootstrap.py"] = b'''"""Synthetic bootstrap."""
from __future__ import annotations
import os
from pathlib import Path
def export_scratch_tmp_env():
    (Path(os.environ["HERMES_HOME"])/"cache/scratch").mkdir(parents=True)
export_scratch_tmp_env()
'''
        for module in driver.ENTRIES:
            self.values[module.replace(".", "/") + ".py"] = b'"""Synthetic entry."""\nimport hermes_bootstrap\ndef main(): pass\n'
        self.hashes = {name: hashlib.sha256(data).hexdigest() for name, data in self.values.items()}
        for name, data in self.values.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def test_shared_gate_and_recovery_role_matrix_stays_uncertified(self):
        with patch.dict(driver.BOOTSTRAP_SHA256, self.hashes, clear=True):
            value = driver.inspect(self.root)
        self.assertEqual(value["sharedEntryCases"], 12)
        self.assertEqual(value["mainOnlyMissedCases"], 9)
        self.assertEqual(value["inheritedRecoveryEntries"], 4)
        self.assertTrue(value["outsidersBlockedAcrossParentClose"])
        for name in ("nativePreloadVerified", "cronPackageBootVerified", "authenticatedHandoffVerified",
                     "fullStartupVerified", "globalAdmissionProven", "certifiesUpgrade"):
            self.assertFalse(value[name])

    def test_every_added_source_must_match_before_experiment(self):
        for name in self.values:
            target = self.root / name
            original = target.read_bytes()
            target.write_bytes(original + b"# changed\n")
            with patch.dict(driver.BOOTSTRAP_SHA256, self.hashes, clear=True):
                with self.assertRaises(ValueError):
                    driver.inspect(self.root)
            target.write_bytes(original)

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
