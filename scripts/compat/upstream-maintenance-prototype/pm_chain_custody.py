"""Same-run PM-worker/native build audit, never a sync/finish or production authority policy."""
import os
import json
from pathlib import Path
import re
import stat
import subprocess

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("pm_chain_joint", Path(__file__).with_name("source_pm.py"))
joint = module_from_spec(spec)
spec.loader.exec_module(joint)
MARKER = b"HG199 isolated same-run PM worker and native custody audit v1\n"
POLICY = "same-run-pm-worker-native-build-audit-v1"
PROJECT_FILES = ("local-build-project/pyproject.toml", "local-build-project/backend.py")


def scope(held, primary, expectations, official):
    root = joint.validate(held, primary, *expectations, official, "after")
    marker = root / ".hg199-isolated-pm-chain"
    info = marker.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) != 0o600 or marker.read_bytes() != MARKER):
        raise ValueError("marked same-run audit required")
    return root


def tool(path, root):
    path = Path(path)
    if not path.is_absolute() or not path.resolve(strict=True).is_relative_to(root / "data/tools") or not os.access(path, os.X_OK):
        raise ValueError("private actual PM tool required")
    return {"path": str(path), "sha256": joint.pm.file(path.resolve(strict=True), [0, 0])["sha256"]}


def arguments(root, python, environment):
    return {"source": str(root / "local-build-project"), "out": str(root / "local-build-output"),
                 "python": str(python), "cache": str(root / "cache"), "env": dict(environment),
                 "extras": [], "groups": [], "all_extras": False, "no_install_project": False,
                 "frozen": False, "sealed": False, "offline": True, "explicit": True, "timeout": 30}


def make_plan(held, primary, expectations, official, *, uv, python, worker, environment, package_digest):
    root = scope(held, primary, expectations, official)
    return {"schema": 1, "policy": POLICY, "jointPlanSHA": expectations[3], "operation": "build_environment",
            "packageDefinitionsSHA": package_digest,
            "arguments": arguments(root, python, environment), "projectFiles": joint.binding.snapshot(root, list(PROJECT_FILES)),
            "tools": {"uv": tool(uv, root), "python": tool(python, root), "worker": tool(worker, root)}}


def validate(held, primary, expectations, official, plan):
    root = scope(held, primary, expectations, official)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "jointPlanSHA", "operation", "arguments", "projectFiles", "tools", "packageDefinitionsSHA"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY
            or plan["jointPlanSHA"] != expectations[3] or plan["operation"] != "build_environment"
            or type(plan["packageDefinitionsSHA"]) is not str or re.fullmatch("[0-9a-f]{64}", plan["packageDefinitionsSHA"]) is None
            or type(plan["tools"]) is not dict or set(plan["tools"]) != {"uv", "python", "worker"}
            or any(type(v) is not dict or set(v) != {"path", "sha256"} for v in plan["tools"].values())
            or len(joint.binding.encode(plan)) > joint.binding.LIMIT):
        raise ValueError("independent bounded build-audit plan required")
    for value in plan["tools"].values():
        if tool(value["path"], root) != value:
            raise ValueError("planned PM tool changed")
    if Path(plan["tools"]["worker"]["path"]).resolve(strict=True) != Path(plan["tools"]["python"]["path"]).resolve(strict=True):
        raise ValueError("selected worker base Python differs")
    args = plan["arguments"]
    if (type(args) is not dict or type(args.get("env")) is not dict
            or any(type(k) is not str or type(v) is not str for k, v in args["env"].items())
            or args["env"].get("HOME") != str(root / "user") or args["env"].get("HERMES_HOME") != str(root / "data")
            or any(k in args["env"] for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))):
        raise ValueError("isolated build environment required")
    # Canonical bytes distinguish JSON Boolean/numeric substitutions too.
    if (joint.binding.encode(arguments(root, plan["tools"]["python"]["path"], args["env"])) != joint.binding.encode(args)
            or plan["projectFiles"] != joint.binding.snapshot(root, list(PROJECT_FILES))):
        raise ValueError("build operation or project differs from the plan")
    return root


def history(primary, plan):
    previous = joint.planned(primary)
    row = {"schema": 1, "kind": "pm-build-audit-started", "previous": joint.source.sha(previous),
           "jointPlanSHA": joint.binding.digest(primary), "buildPlanSHA": joint.binding.digest(plan)}
    return previous + joint.binding.encode(row) + b"\n"


def begin(held, primary, expectations, official, plan):
    validate(held, primary, expectations, official, plan)
    joint.append(held, joint.planned(primary), history(primary, plan))


def require(held, primary, expectations, official, plan):
    root = validate(held, primary, expectations, official, plan)
    joint.require_slots(held, history(primary, plan))
    return root


def request_matches(request, root, plan):
    if (type(request) is not dict or set(request) != {"id", "cancel", "operation", "arguments", "update_id", "callbacks", "packages", "context"}
            or type(request["id"]) is not str or re.fullmatch("[0-9a-f]{32}", request["id"]) is None
            or request["operation"] != plan["operation"] or request["callbacks"] != []
            or request["cancel"] is not False
            or request["update_id"] is not None or joint.binding.digest(request["packages"]) != plan["packageDefinitionsSHA"]
            or request["context"] != {"repo": str(root / "checkout"), "lockfile": str(root / "checkout/pm/lock.json")}
            or joint.binding.encode(request["arguments"]) != joint.binding.encode(plan["arguments"])):
        raise ValueError("actual PM worker request differs from the build audit")


def packet(data, root, plan, request_id=None):
    if not isinstance(data, (str, bytes)) or len(data.encode("utf-8") if isinstance(data, str) else data) > 65_536:
        raise ValueError("bounded PM wire packet required")

    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError("duplicate PM wire key")
            result[key] = value
        return result

    def constant(_):
        raise ValueError("non-JSON PM wire constant")

    value = json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
    if request_id is None:
        request_matches(value, root, plan)
    elif (type(value) is not dict or set(value) - {"id", "type", "cancel"}
          or value.get("id") != request_id or value.get("cancel") is not True
          or value.get("type", "cancel") != "cancel"):
        raise ValueError("unreviewed PM control packet")
    return value


def worker_kwargs(held, primary, expectations, official, plan, argv, options):
    root = require(held, primary, expectations, official, plan)
    if (argv != [plan["tools"]["worker"]["path"], "-I", "-B", str(root / "checkout/pm/worker.py")]
            or type(options) is not dict or set(options) - {"stdin", "stdout", "stderr", "text", "encoding", "env", "cwd"}
            or type(options.get("env")) is not dict or options["env"].get("HOME") != str(root / "user")
            or options["env"].get("HERMES_HOME") != str(root / "data")
            or any(k in options["env"] for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))
            or options.get("cwd", str(root)) != str(root)):
        raise ValueError("reviewed actual worker spawn required")
    return {**options, "cwd": str(root), "close_fds": True, "pass_fds": (*held.pass_fds, official)}


def native_kwargs(held, primary, expectations, official, plan, argv, options):
    root = require(held, primary, expectations, official, plan)
    uv, python = plan["tools"]["uv"]["path"], plan["tools"]["python"]["path"]
    out, project = plan["arguments"]["out"], plan["arguments"]["source"]
    allowed = ([uv, "venv", "--relocatable", "--no-project", "--no-config", "--python", python, out, "--offline"],
               [uv, "lock", "--python", python, "--offline"],
               [uv, "sync", "--frozen", "--all-packages", "--python", python, "--compile-bytecode", "--offline"])
    if (type(argv) is not list or argv not in allowed or type(options) is not dict
            or set(options) != {"cwd", "env", "stdout", "stderr", "text", "encoding", "errors", "bufsize"}
            or options["cwd"] != (str(root) if argv[1] == "venv" else project)
            or options["stdout"] != subprocess.PIPE or options["stderr"] != subprocess.STDOUT
            or options["text"] is not True or options["encoding"] != "utf-8" or options["errors"] != "replace" or options["bufsize"] != 0):
        raise ValueError("reviewed quiet native PM spawn required")
    env = options["env"]
    if (type(env) is not dict or env.get("HOME") != str(root / "user") or env.get("HERMES_HOME") != str(root / "data")
            or env.get("UV_PYTHON") != python or env.get("UV_PROJECT_ENVIRONMENT") != out
            or env.get("UV_CACHE_DIR") != str(root / "cache") or env.get("UV_PYTHON_DOWNLOADS") != "never"
            or any(k in env for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))):
        raise ValueError("native build context differs")
    return {**options, "close_fds": True, "pass_fds": (*held.pass_fds, official)}
