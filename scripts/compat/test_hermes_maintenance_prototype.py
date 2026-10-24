"""Real independent processes, synthetic participants only; no Hermes services."""
import importlib.util
import fcntl
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import sys
import tempfile
import unittest

MODULE = Path(__file__).with_name("upstream-maintenance-prototype") / "maintenance_lease.py"
spec = importlib.util.spec_from_file_location("maintenance_lease", MODULE)
lease = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lease)
driver_spec = importlib.util.spec_from_file_location("maintenance_driver", MODULE.parent.parent / "hermes-maintenance-prototype.py")
driver = importlib.util.module_from_spec(driver_spec)
driver_spec.loader.exec_module(driver)

# stdin/stdout is a bounded test control channel. The inherited grandchild uses a
# separate socket, keeping descriptors until explicitly released after parent death.
HARNESS = r'''
import importlib.util,json,os,socket,subprocess,sys
spec=importlib.util.spec_from_file_location("maintenance_lease",sys.argv[1])
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
domains=[m.Domain(*d) for d in json.loads(sys.argv[2])]
held=None
def emit(value): print(json.dumps(value),flush=True)
emit("ready")
for line in sys.stdin:
    command=json.loads(line)
    try:
        if command=="writer": held=m.Lease(domains);emit("held")
        elif command=="maintenance": held=m.Lease(domains,maintenance=True);emit("closed")
        elif command=="drain": emit(held.drain())
        elif command=="release": held.close();held=None;emit("released")
        elif command=="inherit":
            channel=int(sys.argv[3])
            code="""import json,os,socket,sys
s=socket.socket(fileno=int(sys.argv[1]));fds=json.loads(sys.argv[2])
s.sendall(b'ready\\n')
assert s.recv(1)==b'x'
for fd in fds: os.close(fd)
s.sendall(b'released\\n');s.close()
"""
            subprocess.Popen([sys.executable,"-I","-S","-B","-c",code,str(channel),json.dumps(held.pass_fds)],
                pass_fds=held.pass_fds+(channel,),env=os.environ.copy(),
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            emit("inherited")
        elif command=="exit": break
    except BlockingIOError: emit("blocked")
    except Exception: emit("rejected")
if held is not None: held.close()
'''


class Participant:
    def __init__(self, case, domains, inherited=False):
        self.channel = None
        child = None
        if inherited:
            self.channel, child = socket.socketpair()
            self.channel.settimeout(5)
        arguments = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in domains]
        env = {"PATH": os.defpath, "HOME": str(case.root), "HERMES_HOME": str(case.home)}
        self.process = subprocess.Popen([sys.executable, "-I", "-S", "-B", "-c", HARNESS,
                                         str(MODULE), json.dumps(arguments), str(child.fileno() if child else -1)],
                                        env=env, cwd=case.root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True,
                                        pass_fds=(child.fileno(),) if child else ())
        if child:
            child.close()
        case.addCleanup(self.close)
        case.assertEqual(self.read(), "ready")

    def read(self):
        if not select.select([self.process.stdout], [], [], 5)[0]:
            raise AssertionError("prototype participant deadline")
        return json.loads(self.process.stdout.readline())

    def ask(self, command):
        self.process.stdin.write(json.dumps(command) + "\n")
        self.process.stdin.flush()
        return self.read()

    def close(self):
        if self.channel:
            self.channel.close()
            self.channel = None
        if self.process.poll() is None:
            self.process.terminate()  # Only the exact subprocess this fixture created.
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.process.stdin.close()
        self.process.stdout.close()


class MaintenancePrototypeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="hg199-upstream-prototype-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.registry, self.home, self.checkout = [self.root / name for name in ("control", "home", "checkout")]
        for path in (self.registry, self.home, self.checkout):
            path.mkdir(mode=0o700)
        self.domains = self.enrolled(self.home, self.checkout)

    def enrolled(self, home, checkout):
        domains = [lease.Domain(self.registry, kind, path) for kind, path in (("home", home), ("checkout", checkout))]
        for domain in domains:
            if not domain._path("json").exists():
                domain.enroll()
        return domains

    def test_close_admission_then_drain_without_killing_existing_writer(self):
        first = Participant(self, self.domains)
        second = Participant(self, list(reversed(self.domains)))
        updater = Participant(self, self.domains)
        self.assertEqual(first.ask("writer"), "held")
        self.assertEqual(second.ask("writer"), "held")
        self.assertEqual(updater.ask("maintenance"), "closed")
        self.assertFalse(updater.ask("drain"))
        late = Participant(self, self.domains)
        self.assertEqual(late.ask("writer"), "blocked")
        self.assertEqual(first.ask("release"), "released")
        self.assertFalse(updater.ask("drain"))
        self.assertEqual(second.ask("release"), "released")
        self.assertTrue(updater.ask("drain"))
        self.assertEqual(late.ask("writer"), "blocked")
        self.assertEqual(updater.ask("release"), "released")
        self.assertEqual(late.ask("writer"), "held")

    def test_both_home_and_checkout_domains_coordinate_and_unrelated_install_can_run(self):
        other_home, other_checkout = self.root / "other-home", self.root / "other-checkout"
        other_home.mkdir(mode=0o700)
        other_checkout.mkdir(mode=0o700)
        updater = Participant(self, self.domains)
        self.assertEqual(updater.ask("maintenance"), "closed")
        self.assertTrue(updater.ask("drain"))
        for home, checkout in ((self.home, other_checkout), (other_home, self.checkout)):
            other = Participant(self, self.enrolled(home, checkout))
            self.assertEqual(other.ask("writer"), "blocked")
        independent = Participant(self, self.enrolled(other_home, other_checkout))
        self.assertEqual(independent.ask("writer"), "held")

    def test_cancel_while_draining_reopens_admission_and_competing_updater_is_excluded(self):
        writer, updater, rival = [Participant(self, self.domains) for _ in range(3)]
        self.assertEqual(writer.ask("writer"), "held")
        self.assertEqual(updater.ask("maintenance"), "closed")
        self.assertFalse(updater.ask("drain"))
        self.assertEqual(updater.ask("inherit"), "rejected")
        self.assertEqual(rival.ask("maintenance"), "blocked")
        self.assertEqual(updater.ask("release"), "released")
        self.assertEqual(rival.ask("writer"), "held")

    def assert_child_custody(self, maintenance, crash=True):
        parent = Participant(self, self.domains, inherited=True)
        self.assertEqual(parent.ask("maintenance" if maintenance else "writer"), "closed" if maintenance else "held")
        if maintenance:
            self.assertTrue(parent.ask("drain"))
        self.assertEqual(parent.ask("inherit"), "inherited")
        self.assertEqual(parent.channel.recv(128), b"ready\n")
        if crash:
            parent.process.kill()  # Test-owned parent, deliberately bypassing close/finally.
            parent.process.wait(timeout=5)
        else:
            self.assertEqual(parent.ask("release"), "released")
        observer = Participant(self, self.domains)
        if maintenance:
            self.assertEqual(observer.ask("writer"), "blocked")
            self.assertEqual(observer.ask("maintenance"), "blocked")
        else:
            self.assertEqual(observer.ask("maintenance"), "closed")
            self.assertFalse(observer.ask("drain"))
            self.assertEqual(observer.ask("release"), "released")
        parent.channel.sendall(b"x")
        self.assertEqual(parent.channel.recv(128), b"released\n")
        self.assertEqual(observer.ask("maintenance"), "closed")
        self.assertTrue(observer.ask("drain"))

    def test_writer_child_retains_lifetime_lease_after_parent_is_killed(self):
        self.assert_child_custody(maintenance=False)

    def test_updater_child_retains_exclusive_admission_after_owner_is_killed(self):
        self.assert_child_custody(maintenance=True)

    def test_writer_child_retains_lease_after_parent_closes_its_descriptors(self):
        self.assert_child_custody(maintenance=False, crash=False)

    def test_updater_child_retains_lease_after_parent_closes_its_descriptors(self):
        self.assert_child_custody(maintenance=True, crash=False)

    def test_nonparticipant_is_a_counterexample_not_an_upgrade_certificate(self):
        updater = lease.Lease(self.domains, maintenance=True)
        self.addCleanup(updater.close)
        self.assertTrue(updater.drain())
        # Kernel advisory locks do not prevent an old entry from ignoring them.
        result = subprocess.run([sys.executable, "-I", "-S", "-B", "-c",
                                 "from pathlib import Path;import sys;Path(sys.argv[1]).write_text('old-writer')",
                                 str(self.home / "uncooperative")], env={"PATH": os.defpath, "HOME": str(self.root)},
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertEqual((self.home / "uncooperative").read_text(), "old-writer")

    def test_replaced_root_and_lock_objects_refuse_even_with_new_domain_instance(self):
        self.home.rename(self.root / "retired-home")
        self.home.mkdir(mode=0o700)
        with self.assertRaises(lease.Rejected):
            lease.Lease([lease.Domain(self.registry, "home", self.home), self.domains[1]])
        # A lock pathname replacement must never silently form a second lock universe.
        domain = self.domains[1]
        domain._path("gate").rename(self.root / "retired-gate")
        fd = os.open(domain._path("gate"), os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        os.close(fd)
        with self.assertRaises(lease.Rejected):
            domain.open()

    def test_unenrolled_incomplete_duplicate_alias_and_unsafe_control_are_rejected(self):
        with self.assertRaises(lease.Rejected):
            lease.Lease(self.domains[:1])
        with self.assertRaises(lease.Rejected):
            lease.Lease(self.domains + self.domains)
        alias = self.root / "alias"
        alias.symlink_to(self.home)
        with self.assertRaises(lease.Rejected):
            lease.Domain(self.registry, "home", alias)
        other = self.root / "unenrolled"
        other.mkdir(mode=0o700)
        with self.assertRaises(FileNotFoundError):
            lease.Lease([lease.Domain(self.registry, "home", other), self.domains[1]])
        self.registry.chmod(0o755)
        with self.assertRaises(lease.Rejected):
            lease.Lease(self.domains)

    def test_failed_multi_domain_request_releases_earlier_gate(self):
        ordered = sorted(self.domains, key=lambda domain: domain.key)
        pairs = [domain.open() for domain in ordered]
        self.addCleanup(lambda: [os.close(fd) for pair in pairs for fd in pair])
        fcntl.flock(pairs[1][0], fcntl.LOCK_EX | fcntl.LOCK_NB)
        with self.assertRaises(BlockingIOError):
            lease.Lease(self.domains, maintenance=True)
        # The aborted request cannot strand the first gate.
        fcntl.flock(pairs[0][0], fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_lock_replacement_while_draining_refuses_and_releases_request(self):
        request = lease.Lease(self.domains, maintenance=True)
        self.addCleanup(request.close)
        domain = self.domains[0]
        domain._path("writers").rename(self.root / "retired-writers")
        fd = os.open(domain._path("writers"), os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        os.close(fd)
        with self.assertRaises(lease.Rejected):
            request.drain()
        self.assertFalse(request.ready)
        with self.assertRaises(lease.Rejected):
            _ = request.pass_fds

    def test_changed_upstream_source_is_not_executed_and_cli_failure_is_redacted(self):
        source = self.root / "source/hermes_cli"
        source.mkdir(parents=True)
        marker = self.root / "must-not-run"
        (source / "backend_retirement.py").write_text(f"from pathlib import Path;Path({str(marker)!r}).touch()")
        with self.assertRaises(ValueError):
            driver.inspect(source.parent)
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(Path(driver.__file__)),
                                 "--candidate-root", str(source.parent)],
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
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
