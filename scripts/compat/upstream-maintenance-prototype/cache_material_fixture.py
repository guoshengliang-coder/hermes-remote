"""Disposable source materials only: no Hermes runtime, installation or database."""
import importlib.util
from pathlib import Path
import py_compile
import subprocess
import sys
import tempfile
import uuid


def load(name):
    spec = importlib.util.spec_from_file_location("cache_fixture_" + name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


material, lease, support = load("cache_material"), load("maintenance_lease"), load("handoff_actor")


def domains(root):
    return [lease.Domain(root / "control", kind, root / name)
            for kind, name in (("home", "home"), ("checkout", "home/hermes-agent"))]


def held(root):
    value = lease.Lease(domains(root), maintenance=True)
    try:
        if not value.drain():
            raise ValueError("isolated source material busy")
        return value
    except BaseException:
        value.close()
        raise


def blocked(root):
    try:
        value = lease.Lease(domains(root))
    except lease.Pending:
        return
    value.close()
    raise ValueError("cache observation opened admission")


class Fixture:
    def __init__(self, seed, commit, cache_source="hermes_cli/backend_retirement.py"):
        # Real CLI fixes commit/source. Tests explicitly supply their synthetic Git identity.
        material.worker.verify_source(seed, commit, installation=True)
        self.root = Path(tempfile.mkdtemp(prefix="hg199-cache-material-")).resolve()
        for name in ("control", "home"):
            (self.root / name).mkdir(mode=0o700)
        marker = self.root / ".hg199-isolated-cache-material"
        marker.write_bytes(material.MARKER)
        marker.chmod(0o600)
        for name in ("home/hermes-agent", "cold-source"):
            target = self.root / name
            target.mkdir(mode=0o700)
            material.worker.git(target, "init", "--quiet")
            material.worker.git(target, "fetch", "--depth=1", "--no-tags", "--no-recurse-submodules",
                                str(seed), commit, snapshot_fetch=True)
            material.worker.git(target, "checkout", "--quiet", "--detach", commit)
            material.worker.verify_source(target, commit)
        warm = self.root / "home/hermes-agent"
        (warm / ".git/info/exclude").write_text("__pycache__/\n")
        # Compile bytes; never import/execute upstream source. This deliberately creates a normal warm cache.
        py_compile.compile(str(warm / cache_source), doraise=True)
        for domain in domains(self.root):
            domain.enroll()
        self.run, self.commit = str(uuid.uuid4()), commit
        custody = held(self.root)
        try:
            self.plan = material.make_plan(custody, self.run, commit)
            self.sha = material.binding.digest(self.plan)
            material.write_new(self.root / "control/cache-plan.json", self.plan)
            material.begin(custody, self.plan, self.run, commit, self.sha)
        finally:
            custody.close()
        self.args = (self.run, commit, self.sha)

    def child(self, action, manifest="-", phase="complete"):
        return subprocess.Popen([sys.executable, "-I", "-S", "-B", str(Path(__file__)),
            str(self.root), *self.args, action, manifest, phase], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, close_fds=True)

    def preserve_and_kill(self, phase="complete"):
        child = self.child("preserve", phase=phase)
        try:
            value = support.read(child.stdout.fileno(), timeout=120)
            if value.get("checkpoint") != phase:
                raise ValueError("material checkpoint not observed")
            child.kill()  # Exact Popen owned by this fixture, after its synchronous checkpoint.
            if child.wait(timeout=5) != -9 or child.stdout.read(1):
                raise ValueError("material coordinator death not observed")
            blocked(self.root)
            return value.get("manifestSHA")
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)
            child.stdin.close()
            child.stdout.close()

    def recheck_child(self, manifest):
        child = self.child("recheck", manifest)
        try:
            output, _ = child.communicate(timeout=120)
            if child.returncode != 0:
                raise ValueError("fresh material recheck refused")
            return material.binding.decode(output.rstrip(b"\n"))
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)


def actor():
    root, run, commit, sha, action, manifest, phase = sys.argv[1:]
    root = Path(root)
    custody = held(root)
    try:
        plan = material.private_read(root / "control/cache-plan.json")
        if action == "recheck" and phase == "complete":
            print(material.binding.encode(material.recheck(custody, plan, run, commit, sha, manifest)).decode(), flush=True)
            return
        if action != "preserve" or manifest != "-" or phase not in ("complete", "cache-copied", "slot-synced"):
            raise ValueError("explicit material action required")

        def checkpoint(name, index):
            if phase == name and index == 0:
                print(material.binding.encode({"checkpoint": phase}).decode(), flush=True)
                # Trusted supervisor owns this process and will kill it; no child/mutator is active here.
                if sys.stdin.buffer.read(1):
                    raise ValueError("unexpected material continuation")
                raise ValueError("supervisor disconnected")

        digest = material.preserve(custody, plan, run, commit, sha, checkpoint)
        if phase != "complete":
            raise ValueError("requested interruption was not reached")
        print(material.binding.encode({"checkpoint": phase, "manifestSHA": digest}).decode(), flush=True)
        sys.stdin.buffer.read(1)
    finally:
        custody.close()


if __name__ == "__main__":
    try:
        actor()
    except (ValueError, OSError, subprocess.SubprocessError, material.worker.Refused):
        raise SystemExit(1)
