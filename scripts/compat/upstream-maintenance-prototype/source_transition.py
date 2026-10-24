"""Disposable, append-only source transition observation. Never opens admission."""
import hashlib
import importlib.util
import os
from pathlib import Path
import re


def load(name, path=None):
    spec = importlib.util.spec_from_file_location("source_transition_" + name, path or Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


intent, binding = load("durable_intent"), load("bound_handoff")
worker = load("worker", Path(__file__).parents[3] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py")
POLICY = "source-transition-observation-v1"
NAMES = ("hermes_cli/main.py", "hermes_cli/__init__.py", "hermes_bootstrap.py", "hermes_constants.py")
MARKER = b"HG199 isolated source transition v1\n"
LIMIT = 16_384


def sha(data):
    return hashlib.sha256(data).hexdigest()


def scope(held):
    if not held.maintenance or not held.ready or len(held.domains) != 2:
        raise ValueError("drained exclusive custody required")
    root = held.domains[0].registry.parent
    binding.directory(root)
    marker = root / ".hg199-isolated-source-transition"
    data = binding.snapshot(root, [marker.name])
    if (not root.name.startswith("hg199-source-transition-") or root.stat().st_mode & 0o077
            or marker.stat().st_mode & 0o077 or data[marker.name] != sha(MARKER)
            or any(d.registry != root / "control" for d in held.domains)
            or {(d.binding["kind"], str(d.directory)) for d in held.domains}
            != {("home", str(root / "data")), ("checkout", str(root / "checkout"))}):
        raise ValueError("marked disposable source scope required")
    duplicate = held.__class__.inherit_maintenance(held.domains, held.pass_fds)
    duplicate.close()
    return root


def shape(held, plan, run, before_source, after_source, expected_sha):
    root = scope(held)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "before", "after", "trees"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY
            or type(expected_sha) is not str or re.fullmatch("[0-9a-f]{64}", expected_sha) is None
            or len(binding.encode(plan)) > LIMIT or binding.digest(plan) != expected_sha):
        raise ValueError("independent source plan required")
    before, after = plan["before"], plan["after"]
    keys = {"schema", "role", "run", "source", "checkout", "checkoutIdentity", "home", "homeIdentity", "entry", "domains", "files"}
    if (type(before) is not dict or type(after) is not dict or set(before) != keys or set(after) != keys
            or type(before["files"]) is not dict or type(after["files"]) is not dict
            or set(before["files"]) != set(NAMES) or set(after["files"]) != set(NAMES)
            or {k: v for k, v in before.items() if k not in ("source", "files")}
            != {k: v for k, v in after.items() if k not in ("source", "files")}
            or before_source == after_source or before["source"] != before_source or after["source"] != after_source
            or before["run"] != run or type(plan["trees"]) is not dict or set(plan["trees"]) != {"before", "after"}):
        raise ValueError("source transition identity changed")
    for context in (before, after):
        if (type(context["schema"]) is not int or context["schema"] != 1
                or context["role"] != binding.ROLE or context["entry"] != "hermes_cli.main"
                or type(context["source"]) is not str or re.fullmatch("[0-9a-f]{40}", context["source"]) is None
                or any(type(v) is not str or re.fullmatch("[0-9a-f]{64}", v) is None for v in context["files"].values())):
            raise ValueError("source context shape changed")
    if any(type(v) is not str or re.fullmatch("[0-9a-f]{40}", v) is None for v in plan["trees"].values()):
        raise ValueError("source tree shape changed")
    return root


def current(held, plan, run, before_source, after_source, expected_sha, state):
    root = shape(held, plan, run, before_source, after_source, expected_sha)
    context = plan[state]
    worker.verify_source(root / "checkout", context["source"])
    if worker.git(root / "checkout", "rev-parse", context["source"] + "^{tree}").decode().strip() != plan["trees"][state]:
        raise ValueError("planned Git tree changed")
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
    binding.validate(context, run, context["source"], root / "checkout", root / "data", "hermes_cli.main", records)
    return root


def pending(plan):
    base = intent.record(plan["before"])
    result = base + binding.encode({"schema": 1, "kind": "source-transition-plan", "previous": sha(base), "plan": plan}) + b"\n"
    if len(result) > LIMIT:
        raise ValueError("source journal limit")
    return result


def slots(held):
    values = []
    for domain in held.domains:
        fd = domain.intent()
        try:
            values.append(os.pread(fd, LIMIT + 1, 0))
        finally:
            os.close(fd)
    return values


def planned_target(root, plan, after_source):
    # Verify future file expectations against the actual target objects before any append/move.
    worker.local_source_tree(root / "checkout", after_source)
    if worker.git(root / "checkout", "rev-parse", after_source + "^{tree}").decode().strip() != plan["trees"]["after"]:
        raise ValueError("target tree unavailable")
    for name, expected in plan["after"]["files"].items():
        data = worker.git(root / "checkout", "cat-file", "blob", after_source + ":" + name)
        if len(data) > 2 * 1024**2 or sha(data) != expected:
            raise ValueError("planned target file does not match target Git object")


def prepare(held, plan, run, before_source, after_source, expected_sha, checkpoint=None):
    root = current(held, plan, run, before_source, after_source, expected_sha, "before")
    planned_target(root, plan, after_source)
    previous, updated = intent.record(plan["before"]), pending(plan)
    if slots(held) != [previous] * 2:
        raise ValueError("source plan does not extend original intent")
    for index, domain in enumerate(held.domains):
        fd = domain.intent()
        try:
            offset = len(previous)
            while offset < len(updated):
                count = os.pwrite(fd, updated[offset:], offset)
                if count <= 0:
                    raise OSError("source plan append failed")
                offset += count
            os.fsync(fd)
        finally:
            os.close(fd)
        if checkpoint:
            checkpoint("slot-synced", index)
    intent.sync_registry(held)
    require(held, plan, run, before_source, after_source, expected_sha, "before")


def require(held, plan, run, before_source, after_source, expected_sha, state):
    if state not in ("before", "after"):
        raise ValueError("explicit source stage required")
    current(held, plan, run, before_source, after_source, expected_sha, state)
    expected = pending(plan)
    if slots(held) != [expected] * 2:
        raise ValueError("partial or different source transition plan")
    for domain in held.domains:
        fd = domain.intent()
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    intent.sync_registry(held)
    current(held, plan, run, before_source, after_source, expected_sha, state)
    if slots(held) != [expected] * 2:
        raise ValueError("source plan changed during recheck")


def move(held, plan, run, before_source, after_source, expected_sha):
    require(held, plan, run, before_source, after_source, expected_sha, "before")
    root = scope(held)
    planned_target(root, plan, after_source)
    worker.git(root / "checkout", "checkout", "--detach", after_source)
    require(held, plan, run, before_source, after_source, expected_sha, "after")
