"""FIXTURE ONLY: append a planned generation and observed scratch-prefix terminal.

This policy cannot authorize production completion. It is limited to disposable
hg199-durable-* roots, a synthetic dependency marker and copied startup prefixes.
No intent is cleared/reset, and partial appends never authorize admission.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
from types import SimpleNamespace

HERE = Path(__file__).parent
POLICY = "scratch-prefix-fixture-v1"
DEPENDENCY = "fixture_dependency_generation.txt"
LIMIT = 65_536
_KEY = object()


def load(name):
    spec = importlib.util.spec_from_file_location("transition_" + name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


intent, binding, support = load("durable_intent"), load("bound_handoff"), load("handoff_actor")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def frame(kind, **fields):
    data = binding.encode({"schema": 1, "kind": kind, **fields}) + b"\n"
    if len(data) > binding.LIMIT:
        raise ValueError("fixture journal limit")
    return data


def scope(domains, plan):
    if (len(domains) != 2 or type(plan) is not dict or set(plan) != {"schema", "policy", "before", "after"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY):
        raise ValueError("fixture policy required")
    root = domains[0].registry.parent
    if (not root.name.startswith("hg199-durable-") or any(d.registry != root / "control" for d in domains)
            or {str(d.directory) for d in domains} != {str(root / "data"), str(root / "checkout")}):
        raise ValueError("disposable fixture required")
    before, after = plan["before"], plan["after"]
    keys = {"schema", "role", "run", "source", "checkout", "checkoutIdentity", "home", "homeIdentity", "entry", "domains", "files"}
    if (type(before) is not dict or type(after) is not dict or set(before) != set(after)
            or set(before) != keys or type(before["files"]) is not dict
            or {k: v for k, v in before.items() if k not in ("source", "files")}
            != {k: v for k, v in after.items() if k not in ("source", "files")}
            or type(after.get("source")) is not str or re.fullmatch("[0-9a-f]{40}", after["source"]) is None
            or before["source"] == after["source"] or type(after.get("files")) is not dict
            or set(before["files"]) != set(after["files"]) or DEPENDENCY not in after["files"]
            or any(type(v) is not str or re.fullmatch("[0-9a-f]{64}", v) is None for v in after["files"].values())
            or before["files"][DEPENDENCY] == after["files"][DEPENDENCY]
            or before["files"].get("hermes_constants.py") == after["files"].get("hermes_constants.py")):
        raise ValueError("fixture generation plan mismatch")
    return root


def pending(plan):
    base = intent.record(plan["before"])
    value = base + frame("fixture-generation-plan", previous=sha(base), plan=plan)
    if len(value) > LIMIT:
        raise ValueError("fixture journal limit")
    return value


def slots(domains):
    result = []
    for domain in domains:
        fd = domain.intent()
        try:
            result.append(os.pread(fd, LIMIT + 1, 0))
        finally:
            os.close(fd)
    return result


def require(held, plan, state):
    scope(held.domains, plan)
    intent.validate(held, plan[state])
    if slots(held.domains) != [pending(plan)] * 2:
        raise ValueError("fixture transition is incomplete or different")


def append(held, previous, updated, checkpoint=None):
    if len(updated) > LIMIT or not updated.startswith(previous) or updated == previous:
        raise ValueError("fixture append only")
    if slots(held.domains) != [previous] * 2:
        raise ValueError("fixture journal changed")
    for index, domain in enumerate(held.domains):
        fd = domain.intent()
        try:
            offset = len(previous)
            while offset < len(updated):
                count = os.pwrite(fd, updated[offset:], offset)
                if count <= 0:
                    raise OSError("fixture append failed")
                offset += count
            os.fsync(fd)
        finally:
            os.close(fd)
        if checkpoint:
            checkpoint("slot-synced", index)
    intent.sync_registry(held)
    if slots(held.domains) != [updated] * 2:
        raise ValueError("fixture append changed")
    if checkpoint:
        checkpoint("all-synced", 2)


def prepare(held, plan, checkpoint=None):
    scope(held.domains, plan)
    intent.validate(held, plan["before"])
    append(held, intent.record(plan["before"]), pending(plan), checkpoint)


def resume_before_update(held, plan):
    require(held, plan, "before")
    intent.sync_registry(held)


def target(held, plan):
    require(held, plan, "after")
    intent.sync_registry(held)


class _Witness:
    __slots__ = ("held", "digest", "created", "nonce", "used")

    def __init__(self, key, held, plan):
        if key is not _KEY:
            raise ValueError("live fixture observation required")
        self.held, self.digest, self.created = held, sha(pending(plan)), time.monotonic()
        self.nonce, self.used = secrets.token_hex(32), False


def check_fixture_prefix(held, plan):
    """Own and observe a real test child through live handshake AND normal exit."""
    root = scope(held.domains, plan)
    target(held, plan)
    context = plan["after"]
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
    local, remote = socket.socketpair()
    manifest, expected, child = None, None, None
    try:
        manifest = binding.private_context_fd(held.domains[0].registry, context)
        expected = binding.private_context_fd(held.domains[0].registry, plan)
        args = {"context": manifest, "channel": remote.fileno(), "run": context["run"], "source": context["source"],
                "transition": expected}
        child = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(HERE / "startup_probe.py"),
                                  context["checkout"], json.dumps(records), "module", json.dumps(["--help"]),
                                  context["entry"], "bound-recovery", json.dumps(held.pass_fds), json.dumps(args)],
                                 cwd=root, env={"PATH": os.defpath, "HOME": str(root), "HERMES_HOME": context["home"]},
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 pass_fds=(*held.pass_fds, remote.fileno(), manifest, expected))
        remote.close()
        os.close(manifest)
        manifest = None
        os.close(expected)
        expected = None
        binding.issue(local, context)
        value = support.read(child.stdout.fileno())
        shape = {"blocked": bool, "bootstrapEntered": bool, "prefixReached": bool, "admissionCalls": int,
                 "homeMutationAttempts": int, "pmRecoveryInjected": bool, "boundIdentityVerified": bool,
                 "identityRejected": bool, "scratchCreated": bool}
        if (set(value) != set(shape) or any(type(value[k]) is not t for k, t in shape.items())
                or value["blocked"] or value["identityRejected"] or not value["boundIdentityVerified"]
                or not value["bootstrapEntered"] or not value["prefixReached"] or not value["scratchCreated"]
                or value["admissionCalls"] != 1 or value["homeMutationAttempts"] <= 0
                or not value["pmRecoveryInjected"]):
            raise ValueError("fixture prefix did not verify")
        child.stdin.write(b"release\n")
        child.stdin.flush()
        if child.wait(timeout=5) != 0:
            raise ValueError("fixture child did not exit normally")
        target(held, plan)
        return _Witness(_KEY, held, plan)
    finally:
        local.close()
        remote.close()
        for fd in (manifest, expected):
            if fd is not None:
                os.close(fd)
        if child is not None:
            if child.poll() is None:
                child.kill()  # Only this test-owned Popen; never a discovered PID.
            child.wait(timeout=5)
            child.stdin.close()
            child.stdout.close()


def terminal(plan, nonce):
    return pending(plan) + frame("fixture-prefix-complete", previous=sha(pending(plan)), policy=POLICY,
                                 context=binding.digest(plan["after"]), checks=["scratch-prefix", "child-exit-zero", "target-inputs"],
                                 nonce=nonce)


def finish_fixture(held, plan, witness, checkpoint=None):
    target(held, plan)
    if (type(witness) is not _Witness or witness.held is not held or witness.used
            or witness.digest != sha(pending(plan)) or not 0 <= time.monotonic() - witness.created <= 5):
        raise ValueError("fresh live fixture witness required")
    witness.used = True  # Even an uncertain append cannot replay an old observation.
    append(held, pending(plan), terminal(plan, witness.nonce), checkpoint)


def can_enter_fixture(domains):
    """Called only under ALL shared admission gates; never accepts a bare receipt."""
    try:
        values = slots(domains)
        if len(values) != 2 or values[0] != values[1] or len(values[0]) > LIMIT:
            return False
        rows = values[0].splitlines(keepends=True)
        if len(rows) != 3 or any(not row.endswith(b"\n") for row in rows):
            return False
        planned, done = binding.decode(rows[1][:-1]), binding.decode(rows[2][:-1])
        plan = planned["plan"]
        scope(domains, plan)
        nonce = done["nonce"]
        if type(nonce) is not str or re.fullmatch("[0-9a-f]{64}", nonce) is None or terminal(plan, nonce) != values[0]:
            return False
        context = plan["after"]
        records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in domains]
        binding.validate(context, context["run"], context["source"], Path(context["checkout"]), Path(context["home"]),
                         context["entry"], records)
        # A visible complete journal does not prove the dead publisher returned
        # from fsync. Establish synchronization under the current shared gates.
        for domain in domains:
            fd = domain.intent()
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        intent.sync_registry(SimpleNamespace(domains=domains))
        return slots(domains) == values
    except (ValueError, OSError, KeyError, TypeError):
        return False
