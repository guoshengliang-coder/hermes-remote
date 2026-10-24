"""Actual prepare_runtime local-project fault probe; source and installed Hermes are untouched."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


driver = load("bootstrap_actor_driver", HERE.parent / "hermes-maintenance-source-pm.py")
policy = load("bootstrap_actor_policy", HERE / "pm_bootstrap_custody.py")
chain_actor = load("bootstrap_backend_template", HERE / "pm_chain_actor.py")

# Metadata remains available for the preliminary offline lock. Only the actual wheel
# build blocks; no arbitrary hooks or external build dependencies are installed.
METADATA = '''
def prepare_metadata_for_build_wheel(metadata_directory, config_settings=None):
    name = "hg199_bootstrap_build-0.0.1.dist-info"
    directory = Path(metadata_directory) / name
    directory.mkdir()
    (directory / "METADATA").write_text("Metadata-Version: 2.3\\nName: hg199-bootstrap-build\\nVersion: 0.0.1\\n")
    (directory / "WHEEL").write_text("Wheel-Version: 1.0\\nGenerator: HG199 fixture\\nRoot-Is-Purelib: true\\nTag: py3-none-any\\n")
    return name
'''


def run(root, candidate, manifest, events, expected, mode):
    if mode not in ("raw", "proposed"):
        raise ValueError("explicit runtime preparation mode required")
    primary = policy.joint.binding.read_context(manifest)
    os.close(manifest)
    held, official, lock = driver.held(root), None, None
    children, failures, prepare_fds = [], [], []
    environment = filesystem = original_environment = original_lock = thread = None
    try:
        lock, official = driver.official(root, candidate, expected[2])
        policy.joint.prepare(held, primary, *expected, official_fd=official)
        policy.joint.move(held, primary, *expected, official_fd=official)
        uv = root / "data/tools/uv-0.12.3-darwin-arm64/uv"
        python = root / "data/tools/python-3.14.7+20260901-darwin-arm64/bin/python3.14"
        env = driver.pm_cli.environment(root)
        for argv, expected_output in (([str(uv), "--version"], "uv 0.12.3"), ([str(python), "--version"], "Python 3.14.7")):
            checked = subprocess.run(argv, cwd=root, env=env, capture_output=True, text=True, timeout=30)
            if checked.returncode or expected_output not in checked.stdout:
                raise ValueError("actual private runtime tools differ")
        runtime_root = root / "runtime-audit"
        runtime_root.mkdir(mode=0o700)
        prepare_path = runtime_root / ".prepare.lock"
        prepare_path.touch(mode=0o600, exist_ok=False)
        identities = [(os.fstat(fd).st_dev, os.fstat(fd).st_ino) for fd in (*held.pass_fds, official)]
        identities.append(tuple(policy.prepare_identity(root)))
        backend = root / "runtime-backend"
        backend.mkdir(mode=0o700)
        (backend / "pyproject.toml").write_text('[build-system]\nrequires=[]\nbuild-backend="backend"\nbackend-path=["."]\n'
            '[project]\nname="hg199-bootstrap-build"\nversion="0.0.1"\n')
        (backend / "backend.py").write_text(chain_actor.BACKEND.replace("__ROOT__", repr(str(root)))
            .replace("__IDENTITIES__", repr(identities)) + METADATA)
        project = root / "runtime-project"
        project.mkdir(mode=0o700)
        (project / "pyproject.toml").write_text('[project]\nname="hg199-bootstrap-root"\nversion="0.0.1"\n'
            'requires-python=">=3.14"\ndependencies=["hg199-bootstrap-build"]\n'
            '[tool.uv.sources]\nhg199-bootstrap-build={path=' + json.dumps(str(backend)) + '}\n')
        checked = subprocess.run([str(uv), "lock", "--python", str(python), "--no-config", "--offline"],
            cwd=project, env=env, capture_output=True, text=True, timeout=30)
        if checked.returncode or (root / "backend-status.json").exists():
            raise ValueError("offline runtime project lock failed or prematurely built")
        plan = policy.make_plan(held, primary, expected, official, uv, python)
        policy.begin(held, primary, expected, official, plan)
        history = policy.history(primary, plan)
        os.write(events, json.dumps({"runtimePrepared": True, "runtimePlanSHA": policy.joint.binding.digest(plan),
                                    "historySHA": policy.joint.source.sha(history)}).encode() + b"\n")
        sys.path.insert(0, str(root / "checkout"))
        from pm import environment, filesystem
        from pm.runtime import prepare_runtime
        original_environment, original_lock = environment.subprocess, filesystem.lock_fd
        raw = subprocess

        def capture_lock(fd, **options):
            actual, expected_info = os.fstat(fd), prepare_path.lstat()
            if ((actual.st_dev, actual.st_ino) != (expected_info.st_dev, expected_info.st_ino)
                    or options != {"wait": True} or prepare_fds):
                raise ValueError("one actual upstream preparation lock required")
            result = original_lock(fd, **options)
            if result is not True:
                raise ValueError("actual preparation lock was not acquired")
            prepare_fds.append(fd)
            return result

        class NativeSubprocess:
            def __getattr__(self, name):
                return getattr(raw, name)

            def Popen(self, argv, *args, **options):
                if args or len(prepare_fds) != 1:
                    raise ValueError("actual locked runtime native spawn required")
                proposed = policy.native_kwargs(held, primary, expected, official, plan, prepare_fds[0], argv, options)
                child = raw.Popen(argv, **(proposed if mode == "proposed" else options))
                children.append(child)
                return child

            def run(self, *args, **kwargs):
                raise ValueError("unreviewed runtime native output branch")

        environment.subprocess, filesystem.lock_fd = NativeSubprocess(), capture_lock

        def operation():
            try:
                prepare_runtime(uv, python, runtime_root, project=project, offline=True, cache=root / "cache")
                failures.append("unexpectedSuccess")
            except BaseException as exc:
                failures.append(type(exc).__name__)
                print("BOOTSTRAP_OPERATION_STOP=" + type(exc).__name__, file=sys.stderr, flush=True)

        thread = threading.Thread(target=operation, daemon=True)
        thread.start()
        command = policy.joint.pm.support.read(sys.stdin.fileno(), timeout=90)
        active = [p for p in children if p.poll() is None]
        if command != {"killOwnedUVAndClose": True} or len(active) != 1:
            raise ValueError("one live owned runtime UV process required")
        active[0].kill()
        active[0].wait(timeout=5)
        thread.join(timeout=8)
        if thread.is_alive() or not failures or "unexpectedSuccess" in failures or (runtime_root / "selected.json").exists():
            raise ValueError("runtime preparation cleanup incomplete or unexpectedly published")
        # Upstream's with/except cleanup has finished; never close custody under active Python writes.
        held.close()
        os.close(official)
        official = None
        os.write(events, b'{"nativeUVKilled":true,"runtimeBusinessStopped":true,"callerCustodyClosed":true}\n')
        policy.joint.pm.support.read(sys.stdin.fileno(), timeout=20)
        raise ValueError("supervisor must reap this exact caller")
    finally:
        for child in children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)
        if thread is not None:
            thread.join(timeout=8)
            if thread.is_alive():
                # Stop this fixture process atomically rather than explicitly dropping
                # descriptors under a still-running preparation/cleanup thread.
                os._exit(1)
        if original_environment is not None:
            environment.subprocess = original_environment
            filesystem.lock_fd = original_lock
        if official is not None:
            os.close(official)
        held.close()


if __name__ == "__main__":
    try:
        root, candidate, manifest, events, run_id, before, after, digest, mode = sys.argv[1:]
        run(Path(root), Path(candidate), int(manifest), int(events), (run_id, before, after, digest), mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, subprocess.SubprocessError,
            policy.joint.source.worker.Refused, driver.joint.source.worker.Refused, driver.source_cli.source.worker.Refused) as exc:
        print("BOOTSTRAP_CALLER_REFUSED=" + type(exc).__name__, file=sys.stderr, flush=True)
        raise SystemExit(1)
