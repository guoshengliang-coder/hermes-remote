"""Instrument the actual fixed PM worker's quiet native branch in disposable fixtures only."""
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
chain = load("chain_worker_policy", HERE / "pm_chain_custody.py")
lease = chain.joint.source.load("maintenance_lease")


def run(root, context_fd, descriptors, official_fd, control, events, mode):
    if mode not in ("worker-only", "full-chain") or official_fd < 3:
        raise ValueError("explicit chain observation required")
    context = chain.joint.binding.read_context(context_fd)
    os.close(context_fd)
    if type(context) is not dict or set(context) != {"primary", "plan", "expectations"}:
        raise ValueError("private bounded chain context required")
    held = lease.Lease.inherit_maintenance([lease.Domain(root / "control", k, root / n)
        for k, n in (("home", "data"), ("checkout", "checkout"))], descriptors)
    official = os.dup(official_fd)
    for fd in (*descriptors, official_fd):
        os.close(fd)
    try:
        primary, plan, expected = context["primary"], context["plan"], tuple(context["expectations"])
        chain.require(held, primary, expected, official, plan)
        if max(*held.pass_fds, official) >= 4096:
            raise ValueError("bounded backend descriptor observation required")
        sys.path.insert(0, str(root / "checkout"))
        from pm import environment
        if environment.verbose_output():
            raise ValueError("only the actual quiet PM branch is reviewed")
        raw = environment.subprocess
        children = []

        class NativeSubprocess:
            def __getattr__(self, name):
                return getattr(raw, name)

            def Popen(self, argv, *args, **options):
                if args:
                    raise ValueError("unreviewed native positional options")
                try:
                    proposed = chain.native_kwargs(held, primary, expected, official, plan, argv, options)
                except ValueError:
                    # Private diagnostic vocabulary only: flags/key names, never argument values.
                    shape = [value if value.startswith("--") or value in ("venv", "lock", "sync") else "<value>" for value in argv]
                    print("CHAIN_NATIVE_REJECT=" + json.dumps({"shape": shape, "keys": sorted(options)}), file=sys.stderr, flush=True)
                    raise
                child = raw.Popen(argv, **(proposed if mode == "full-chain" else options))
                children.append(child)
                return child

            def run(self, *args, **kwargs):
                raise ValueError("unreviewed native output branch")

        environment.subprocess = NativeSubprocess()
        worker = load("hg199_actual_chain_worker", root / "checkout/pm/worker.py")
        original_json = worker.json

        class WireJSON:
            request_id = None

            def __getattr__(self, name):
                return getattr(original_json, name)

            def loads(self, data, *args, **kwargs):
                if args or kwargs:
                    raise ValueError("unreviewed wire decoder options")
                try:
                    value = chain.packet(data, root, plan, self.request_id)
                except ValueError:
                    print("CHAIN_WIRE_REJECT=closed-request-shape", file=sys.stderr, flush=True)
                    raise
                if self.request_id is None:
                    self.request_id = value["id"]
                return value

        worker.json = WireJSON()
        failures = []

        def operation():
            try:
                worker.main()
            except BaseException as exc:
                failures.append(type(exc).__name__)
                print("CHAIN_WORKER_OPERATION_STOP=" + type(exc).__name__, file=sys.stderr, flush=True)

        thread = threading.Thread(target=operation, daemon=True)
        thread.start()
        command = chain.joint.pm.support.read(control, timeout=120)
        active = [child for child in children if child.poll() is None]
        if command != {"killOwnedUV": True} or len(active) != 1:
            raise ValueError("one live owned UV process required")
        active[0].kill()  # The exact native Popen created by this shim; no external PID input.
        active[0].wait(timeout=5)
        # Keep every description until the actual worker business/cleanup has stopped. The
        # caller then kills this still-custodied shim, rather than closing locks under writers.
        thread.join(timeout=8)
        if thread.is_alive():
            raise ValueError("worker cleanup still owns writes")
        os.write(events, b'{"nativeUVKilled":true,"workerBusinessStopped":true}\n')
        chain.joint.pm.support.read(control, timeout=20)  # The owning caller must kill this Popen.
        raise ValueError("worker must not report upgrade completion")
    finally:
        os.close(official)
        held.close()


if __name__ == "__main__":
    try:
        root, context, descriptors, official, control, events, mode = sys.argv[1:]
        run(Path(root), int(context), json.loads(descriptors), int(official), int(control), int(events), mode)
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, chain.joint.source.worker.Refused) as exc:
        print("CHAIN_WORKER_REFUSED=" + type(exc).__name__, file=sys.stderr, flush=True)
        raise SystemExit(1)
