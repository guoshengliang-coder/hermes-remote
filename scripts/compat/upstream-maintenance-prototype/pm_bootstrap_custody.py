"""Fixture-only runtime preparation custody; not whole-bootstrap or recovery authority."""
import fcntl
import importlib.util
import os
from pathlib import Path
import re
import stat
import subprocess

spec = importlib.util.spec_from_file_location("bootstrap_chain_policy", Path(__file__).with_name("pm_chain_custody.py"))
chain = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chain)
joint = chain.joint
MARKER = b"HG199 isolated PM runtime preparation custody audit v1\n"
POLICY = "pm-runtime-preparation-custody-audit-v1"
FILES = ("runtime-project/pyproject.toml", "runtime-project/uv.lock",
         "runtime-backend/pyproject.toml", "runtime-backend/backend.py")


def scope(held, primary, expected, official):
    root = joint.validate(held, primary, *expected, official, "after")
    marker = root / ".hg199-isolated-pm-bootstrap"
    info = marker.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) != 0o600 or marker.read_bytes() != MARKER):
        raise ValueError("marked runtime preparation audit required")
    return root


def prepare_identity(root, fd=None):
    path = root / "runtime-audit/.prepare.lock"
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) != 0o600):
        raise ValueError("stable private preparation lock required")
    if fd is not None:
        if type(fd) is not int or fd < 3:
            raise ValueError("actual preparation descriptor required")
        actual = os.fstat(fd)
        if ((actual.st_dev, actual.st_ino) != (info.st_dev, info.st_ino)
                or fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE != os.O_RDWR):
            raise ValueError("preparation description differs")
        # The caller captures this exact fd only AFTER upstream lock_fd succeeds. Recheck
        # without unlocking; no saved integer/JSON acts as recovery authority.
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return [info.st_dev, info.st_ino]


def make_plan(held, primary, expected, official, uv, python):
    root = scope(held, primary, expected, official)
    return {"schema": 1, "policy": POLICY, "jointPlanSHA": expected[3],
            "projectFiles": joint.binding.snapshot(root, list(FILES)),
            "tools": {"uv": chain.tool(uv, root), "python": chain.tool(python, root)},
            "prepareIdentity": prepare_identity(root)}


def validate(held, primary, expected, official, plan):
    root = scope(held, primary, expected, official)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "jointPlanSHA", "projectFiles", "tools", "prepareIdentity"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY
            or plan["jointPlanSHA"] != expected[3] or type(plan["tools"]) is not dict
            or set(plan["tools"]) != {"uv", "python"}
            or any(type(v) is not dict or set(v) != {"path", "sha256"} for v in plan["tools"].values())
            or type(plan["prepareIdentity"]) is not list or len(plan["prepareIdentity"]) != 2
            or any(type(v) is not int for v in plan["prepareIdentity"])):
        raise ValueError("explicit runtime preparation plan required")
    if make_plan(held, primary, expected, official,
                 plan["tools"]["uv"]["path"], plan["tools"]["python"]["path"]) != plan:
        raise ValueError("runtime preparation inputs changed")
    return root


def history(primary, plan):
    previous = joint.planned(primary)
    return previous + joint.binding.encode({"schema": 1, "kind": "pm-runtime-audit-started",
        "previous": joint.source.sha(previous), "jointPlanSHA": joint.binding.digest(primary),
        "runtimePlanSHA": joint.binding.digest(plan)}) + b"\n"


def begin(held, primary, expected, official, plan):
    validate(held, primary, expected, official, plan)
    joint.append(held, joint.planned(primary), history(primary, plan))


def native_kwargs(held, primary, expected, official, plan, prepare_fd, argv, options):
    root = validate(held, primary, expected, official, plan)
    joint.require_slots(held, history(primary, plan))
    if prepare_identity(root, prepare_fd) != plan["prepareIdentity"]:
        raise ValueError("runtime preparation lock changed")
    if (type(options) is not dict or set(options) != {"cwd", "env", "stdout", "stderr", "text", "encoding", "errors", "bufsize"}
            or options["stdout"] != subprocess.PIPE or options["stderr"] != subprocess.STDOUT
            or options["text"] is not True or options["encoding"] != "utf-8"
            or options["errors"] != "replace" or options["bufsize"] != 0):
        raise ValueError("reviewed streamed runtime native spawn required")
    env = options["env"]
    uv, python = plan["tools"]["uv"]["path"], plan["tools"]["python"]["path"]
    if (type(env) is not dict or env.get("HOME") != str(root / "user")
            or env.get("HERMES_HOME") != str(root / "data") or env.get("UV_PYTHON") != python
            or env.get("UV_CACHE_DIR") != str(root / "cache") or env.get("UV_PYTHON_DOWNLOADS") != "never"
            or any(k in env for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))):
        raise ValueError("runtime native context differs")
    destination = Path(env.get("UV_PROJECT_ENVIRONMENT", ""))
    generations = root / "runtime-audit/generations"
    if destination.parent != generations or re.fullmatch("[0-9a-f]{32}", destination.name) is None:
        raise ValueError("new private runtime generation required")
    create = [uv, "venv", "--relocatable", "--no-project", "--no-config", "--python", python, str(destination), "--offline"]
    sync = [uv, "sync", "--locked", "--all-packages", "--python", python, "--compile-bytecode",
            "--no-default-groups", "--no-install-project", "--no-install-package", "hg199-bootstrap-root", "--no-config", "--offline"]
    if type(argv) is not list or argv not in (create, sync):
        raise ValueError("reviewed local runtime command required")
    if argv == create:
        if options["cwd"] != str(generations):
            raise ValueError("runtime creation cwd differs")
    else:
        scratch = Path(options["cwd"])
        if (scratch.parent != generations or not scratch.name.startswith("pm-project-")
                or scratch.resolve(strict=True) != scratch
                or joint.binding.snapshot(scratch, ["pyproject.toml", "uv.lock"]) != {
                    name: plan["projectFiles"]["runtime-project/" + name] for name in ("pyproject.toml", "uv.lock")}):
            raise ValueError("actual runtime scratch project differs")
    return {**options, "close_fds": True, "pass_fds": (*held.pass_fds, official, prepare_fd)}
