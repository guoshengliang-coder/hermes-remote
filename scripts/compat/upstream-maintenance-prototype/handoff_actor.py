"""Synthetic, test-owned processes for live protocol death; never runs Hermes."""
import importlib.util
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import sys
import time
import uuid


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


lease, binding = load("maintenance_lease"), load("bound_handoff")
PHASES = ("ready", "accepted", "commit-received", "authorized")
SOURCE = "818c13be1dc4fd28987e1e881a9408224afd4535"


def read(fd, timeout=8):
    data, deadline = b"", time.monotonic() + timeout
    while not data.endswith(b"\n"):
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
            raise ValueError("actor deadline")
        chunk = os.read(fd, 1)  # Never consume a subsequent checkpoint frame.
        if not chunk or len(data) >= 4096:
            raise ValueError("actor frame")
        data += chunk
    value = json.loads(data)
    if type(value) is not dict:
        raise ValueError("actor frame")
    return value


def emit(value):
    print(json.dumps(value), flush=True)


def domains(root):
    return [lease.Domain(root / "control", kind, root / name)
            for kind, name in (("home", "data"), ("checkout", "checkout"))]


def child(root, phase, channel, manifest, run_id, descriptors):
    values = domains(root)
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in values]
    adopted = None

    def checkpoint(name):
        if name == phase:
            emit({"phase": name})
            command = read(sys.stdin.fileno())
            if command == {"command": "die"}:
                os.kill(os.getpid(), signal.SIGKILL)  # Only this synthetic test process.
            if command != {"command": "continue"}:
                raise ValueError("actor command")

    try:
        try:
            adopted = binding.accept(channel, manifest, run_id, SOURCE, root / "checkout", root / "data",
                                     "synthetic.entry", records,
                                     lambda: lease.Lease.inherit_maintenance(values, descriptors), checkpoint)
        except (ValueError, OSError):
            pass
        finally:
            for fd in (*descriptors, manifest):
                os.close(fd)
        if adopted is not None:
            (root / "data/authorized-marker").write_bytes(b"synthetic-prefix\n")
            checkpoint("authorized")
            emit({"status": "authorized"})
            if read(sys.stdin.fileno()) != {"command": "release"}:
                raise ValueError("actor command")
        else:
            emit({"status": "rejected"})
    finally:
        if adopted is not None:
            adopted.close()
    emit({"status": "closed"})


def parent(root, phase, victim, child_input, child_output):
    values = domains(root)
    request = lease.Lease(values, maintenance=True)
    process = None
    local, remote = socket.socketpair()
    manifest = None
    try:
        if not request.drain():
            raise ValueError("actor busy")
        records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in values]
        run_id = str(uuid.uuid4())
        context = binding.context(run_id, SOURCE, root / "checkout", root / "data", "synthetic.entry",
                                  records, ["sample.py"])
        manifest = binding.private_context_fd(root / "control", context)
        descriptors = request.pass_fds
        process = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(Path(__file__)), "child", str(root),
                                    phase, str(remote.fileno()), str(manifest), run_id, json.dumps(descriptors)],
                                   stdin=child_input, stdout=child_output, stderr=subprocess.DEVNULL,
                                   cwd=root, env={"PATH": os.defpath, "HOME": str(root)},
                                   pass_fds=(*descriptors, remote.fileno(), manifest))
        remote.close()
        os.close(manifest)
        manifest = None
        os.close(child_input)
        os.close(child_output)  # The supervisor/child now own their separate control channel.
        emit({"status": "spawned"})
        outcome = "confirmed"
        try:
            binding.issue(local, context)
        except binding.Uncertain:
            outcome = "commit-uncertain"
        except (binding.Rejected, OSError):
            outcome = "rejected-before-commit"
        finally:
            local.close()
        result = {"status": "outcome", "outcome": outcome}
        if victim in ("child", "both"):
            result["childExit"] = process.wait(timeout=5)
        emit(result)
        if read(sys.stdin.fileno()) != {"command": "finish"}:
            raise ValueError("actor command")
        process.wait(timeout=5)
    finally:
        request.close()
        local.close()
        remote.close()
        if manifest is not None:
            os.close(manifest)
    emit({"status": "closed"})


if __name__ == "__main__":
    try:
        root = Path(sys.argv[2])
        if not root.name.startswith("hg199-handoff-death-") or sys.argv[3] not in PHASES:
            raise ValueError("actor context")
        if sys.argv[1] == "parent":
            parent(root, sys.argv[3], sys.argv[4], int(sys.argv[5]), int(sys.argv[6]))
        elif sys.argv[1] == "child":
            child(root, sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), sys.argv[6], json.loads(sys.argv[7]))
        else:
            raise ValueError("actor role")
    except Exception:
        emit({"failed": True})
        raise SystemExit(1) from None
