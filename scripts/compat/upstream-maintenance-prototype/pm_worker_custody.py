"""Proposed custody for ONE verified mutable-PM worker spawn, in joint fixtures only.

Not a global subprocess monkeypatch, a complete PM tree fence, or a production adapter.
The caller must retain the original transaction and classify every later child boundary.
"""
import os
from pathlib import Path
import subprocess

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("worker_custody_joint", Path(__file__).with_name("source_pm.py"))
joint = module_from_spec(spec)
spec.loader.exec_module(joint)
POPEN = subprocess.Popen


def spawn(held, plan, *expectations, official_fd, argv, options):
    root = joint.validate(held, plan, *expectations, official_fd, "after")
    joint.require_slots(held, joint.event(joint.planned(plan), "pm-sync-started", expectations[3]))
    if (type(argv) is not list or len(argv) != 4 or any(type(v) is not str for v in argv)
            or argv[1:3] != ["-I", "-B"] or argv[3] != str(root / "checkout/pm/worker.py")
            or type(options) is not dict
            or set(options) - {"stdin", "stdout", "stderr", "text", "encoding", "env", "cwd"}):
        raise ValueError("reviewed mutable PM worker spawn required")
    python = Path(argv[0])
    if (not python.is_absolute() or not python.resolve(strict=True).is_relative_to(root / "data/tools")
            or not os.access(python, os.X_OK)):
        raise ValueError("isolated PM interpreter required")
    # Inspect the canonical actual binary, not just a potentially reusable interpreter pathname.
    joint.pm.file(python.resolve(strict=True), [0, 0])
    env = options.get("env")
    if (type(env) is not dict or any(type(k) is not str or type(v) is not str for k, v in env.items())
            or env.get("HERMES_HOME") != str(root / "data") or env.get("HOME") != str(root / "user")
            or any(k in env for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))
            or options.get("cwd", str(root)) != str(root)):
        raise ValueError("PM worker context differs from joint scope")
    # close_fds remains true. Only the explicitly validated home/checkout and official lock
    # descriptions cross exec. No lock numbers from the environment confer authority.
    return POPEN(argv, **{**options, "cwd": str(root)}, close_fds=True,
                 pass_fds=(*held.pass_fds, official_fd))
