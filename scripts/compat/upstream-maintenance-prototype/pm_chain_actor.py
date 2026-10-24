"""Fixed-source caller for the same-run PM worker/native build experiment."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


HERE = Path(__file__).parent
driver = load("chain_actor_driver", HERE.parent / "hermes-maintenance-source-pm.py")
chain = load("chain_actor_policy", HERE / "pm_chain_custody.py")

BACKEND = '''import json, os, time
from pathlib import Path
ROOT = Path(__ROOT__)
EXPECTED = __IDENTITIES__
def get_requires_for_build_wheel(config_settings=None): return []
def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    found = set()
    for fd in range(3,4096):
        try:
            value = os.fstat(fd)
            identity = (value.st_dev,value.st_ino)
            if identity in EXPECTED: found.add(identity)
        except OSError: pass
    matches = found == set(EXPECTED)
    for sequence in range(400):
        value = {"backendAlive":True,"descriptorsMatch":matches,"pid":os.getpid(),"sequence":sequence}
        scratch = ROOT / "backend-status.next"
        scratch.write_text(json.dumps(value))
        scratch.replace(ROOT / "backend-status.json")
        if (ROOT / "release-backend").exists(): break
        time.sleep(.05)
    raise RuntimeError("controlled same-run build probe abort; no upgrade completion")
get_requires_for_build_editable = get_requires_for_build_wheel
build_editable = build_wheel
'''


def run(root, candidate, manifest, events, worker_control, expected, mode):
    if mode not in ("worker-only", "full-chain"):
        raise ValueError("explicit chain mode required")
    primary = chain.joint.binding.read_context(manifest)
    os.close(manifest)
    held, official, context_fd, lock = driver.held(root), None, None, None
    raw, children, failures = subprocess.Popen, [], []
    try:
        lock, official = driver.official(root, candidate, expected[2])
        chain.joint.prepare(held, primary, *expected, official_fd=official)
        chain.joint.move(held, primary, *expected, official_fd=official)
        sys.path.insert(0, str(root / "checkout"))
        from pm.runtime import runtime_python
        worker_python = runtime_python(cache=root / "cache")  # Bootstrap is real, before the measured death boundary.
        from pm._uv import _toolchain
        from pm.registry import package_definitions
        uv, python = _toolchain(realize=False)
        project = root / "local-build-project"
        project.mkdir(mode=0o700)
        (project / "pyproject.toml").write_text('[build-system]\nrequires=[]\nbuild-backend="backend"\nbackend-path=["."]\n'
                                              '[project]\nname="hg199-same-run-build-probe"\nversion="0.0.1"\nrequires-python=">=3.14"\n')
        identities = [(os.fstat(fd).st_dev, os.fstat(fd).st_ino) for fd in (*held.pass_fds, official)]
        (project / "backend.py").write_text(BACKEND.replace("__ROOT__", repr(str(root)))
                                              .replace("__IDENTITIES__", repr(identities)))
        plan = chain.make_plan(held, primary, expected, official, uv=uv, python=python, worker=worker_python,
                               environment=driver.pm_cli.environment(root),
                               package_digest=chain.joint.binding.digest(package_definitions(["uv"])))
        chain.begin(held, primary, expected, official, plan)
        history = chain.joint.source.slots(held)
        if history[0] != history[1]:
            raise ValueError("partial chain history")
        context_fd = chain.joint.binding.private_context_fd(root / "control", {
            "primary": primary, "plan": plan, "expectations": list(expected)})
        os.write(events, json.dumps({"chainPrepared": True, "buildPlanSHA": chain.joint.binding.digest(plan),
                                    "historySHA": chain.joint.source.sha(history[0])}).encode() + b"\n")

        def capture(argv, *args, **options):
            nonlocal context_fd, worker_control
            worker_path = str(root / "checkout/pm/worker.py")
            if type(argv) is list and len(argv) == 4 and argv[-1] == worker_path:
                if args or children:
                    raise ValueError("one exact worker spawn required")
                proposed = chain.worker_kwargs(held, primary, expected, official, plan, argv, options)
                command = [argv[0], "-I", "-B", str(HERE / "pm_chain_worker.py"), str(root), str(context_fd),
                           json.dumps(list(held.pass_fds)), str(official), str(worker_control), str(events), mode]
                child = raw(command, **{k: v for k, v in proposed.items() if k != "pass_fds"},
                            pass_fds=(*proposed["pass_fds"], context_fd, worker_control, events))
                children.append(child)
                os.close(context_fd)
                context_fd = None
                os.close(worker_control)
                worker_control = None
                return child
            if children:
                raise ValueError("unreviewed additional caller child")
            return raw(argv, *args, **options)  # Completed private runtime probes before the measured worker.

        def operation():
            try:
                from pm.client import build_environment
                build_environment(**plan["arguments"])
                failures.append("unexpectedSuccess")
            except BaseException as exc:
                failures.append(type(exc).__name__)
                print("CHAIN_CALLER_OPERATION_STOP=" + type(exc).__name__, file=sys.stderr, flush=True)

        subprocess.Popen = capture
        thread = threading.Thread(target=operation, daemon=True)
        thread.start()
        command = chain.joint.pm.support.read(sys.stdin.fileno(), timeout=120)
        if command != {"killOwnedWorkerAndClose": True} or len(children) != 1 or children[0].poll() is not None:
            raise ValueError("live owned worker required")
        children[0].kill()  # This caller's exact Popen; its actual business already stopped.
        children[0].wait(timeout=5)
        thread.join(timeout=8)
        if thread.is_alive() or not failures or "unexpectedSuccess" in failures:
            raise ValueError("caller cleanup still owns writes or unexpected completion")
        held.close()
        os.close(official)
        official = None
        os.write(events, b'{"workerKilled":true,"callerBusinessStopped":true,"callerCustodyClosed":true}\n')
        chain.joint.pm.support.read(sys.stdin.fileno(), timeout=20)  # Supervisor must kill this caller Popen.
        raise ValueError("caller must not report upgrade completion")
    finally:
        subprocess.Popen = raw
        for child in children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)
        for fd in (context_fd, worker_control, official):
            if fd is not None:
                os.close(fd)
        # The official marker and durable intent remain diagnostic evidence; no release/finish.
        held.close()


if __name__ == "__main__":
    try:
        root, candidate, manifest, events, worker_control, run_id, before, after, digest, mode = sys.argv[1:]
        run(Path(root), Path(candidate), int(manifest), int(events), int(worker_control),
            (run_id, before, after, digest), mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, chain.joint.source.worker.Refused,
            driver.joint.source.worker.Refused, driver.source_cli.source.worker.Refused) as exc:
        print("CHAIN_CALLER_REFUSED=" + type(exc).__name__, file=sys.stderr, flush=True)
        raise SystemExit(1)
