"""Run the fixed PM PythonEnvironment API against a local, aborting PEP 517 backend."""
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
    spec.loader.exec_module(module)
    return module


HERE = Path(__file__).parent
driver = load("native_descendant_driver", HERE.parent / "hermes-maintenance-source-pm.py")
native = load("native_descendant_probe", HERE / "pm_native_descendant.py")


def run(root, manifest, mode):
    context = driver.joint.binding.read_context(manifest)
    os.close(manifest)
    if mode not in ("raw", "proposed") or set(context) != {"source", "uv", "python", "uvSHA", "pythonSHA"}:
        raise ValueError("explicit native probe context required")
    held = driver.held(root)
    raw, children, failures = subprocess.Popen, [], []
    try:
        native.scope(held)
        source, uv, python = (Path(context[k]) for k in ("source", "uv", "python"))
        driver.joint.source.worker.verify_source(source, driver.source_cli.NEW)
        for name, path in (("uv", uv), ("python", python)):
            if driver.joint.pm.file(path, [0, 0])["sha256"] != context[name + "SHA"]:
                raise ValueError("observed tool changed")
        version = subprocess.run([str(uv), "--version"], capture_output=True, text=True, check=True, timeout=10)
        runtime = subprocess.run([str(python), "-I", "-S", "-B", "-c",
                                  "import json,sys; print(json.dumps(list(sys.version_info[:3])))"],
                                 capture_output=True, text=True, check=True, timeout=10)
        if version.stdout.split()[:2] != ["uv", "0.12.3"] or json.loads(runtime.stdout) != [3, 14, 7]:
            raise ValueError("fixed native tool versions changed")
        project = root / "local-project"
        project.mkdir(mode=0o700)
        (project / "pyproject.toml").write_text('[build-system]\nrequires=[]\nbuild-backend="backend"\nbackend-path=["."]\n'
                                              '[project]\nname="hg199-local-custody-probe"\nversion="0.0.1"\n')
        (project / "backend.py").write_text(native.backend(root, held))
        sys.path.insert(0, str(source))
        from pm.environment import PythonEnvironment
        environment = PythonEnvironment(uv=uv, python=python, destination=root / "probe-venv",
                                        cache=root / "cache", env=dict(os.environ), offline=True)
        environment.create()  # Actual pinned native uv; no build is yet in flight.

        def capture(argv, *args, **options):
            if args or children:
                raise ValueError("unreviewed additional PM child")
            proposed = native.reviewed_kwargs(held, uv, argv, options)
            child = raw(argv, **(proposed if mode == "proposed" else options))
            children.append(child)
            return child

        def install():
            try:
                environment.install_requirements([str(project)])
                failures.append("unexpected build completion")
            except BaseException as exc:
                failures.append(type(exc).__name__)  # No private exception text on the protocol.

        subprocess.Popen = capture
        thread = threading.Thread(target=install, daemon=True)
        thread.start()
        command = driver.joint.pm.support.read(sys.stdin.fileno(), timeout=90)
        if command != {"killOwnedUVAndCloseCustody": True} or len(children) != 1:
            raise ValueError("native UV ownership missing")
        child = children[0]
        if child.poll() is not None:
            raise ValueError("native UV exited before interruption")
        child.kill()  # This actor's exact Popen, after the backend reports it is running.
        child.wait(timeout=5)
        held.close()
        print(json.dumps({"nativeUVKilled": True, "parentCustodyClosed": True}), flush=True)
        thread.join(timeout=25)
        if thread.is_alive() or not failures or failures == ["unexpected build completion"]:
            raise ValueError("local build must remain an aborted probe")
    finally:
        subprocess.Popen = raw
        for child in children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)
        held.close()


if __name__ == "__main__":
    try:
        root, manifest, mode = sys.argv[1:]
        run(Path(root), int(manifest), mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError):
        raise SystemExit(1)
