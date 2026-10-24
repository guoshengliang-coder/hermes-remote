"""Narrow native-uv build descendant probe. Never an upgrade admission/finish policy."""
import os
from pathlib import Path
import stat

MARKER = b"HG199 isolated native PM descendant probe v1\n"


def scope(held):
    if not held.maintenance or not held.ready or len(held.domains) != 2:
        raise ValueError("drained native probe custody required")
    roots = {d.binding["kind"]: d.directory for d in held.domains}
    root = roots["home"].parent
    if (not root.name.startswith("hg199-pm-descendant-") or root.resolve(strict=True) != root
            or roots != {"home": root / "data", "checkout": root / "checkout"}
            or root.stat().st_mode & 0o077):
        raise ValueError("private native probe required")
    marker = root / ".hg199-native-descendant"
    info = marker.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) != 0o600 or marker.read_bytes() != MARKER):
        raise ValueError("marked native probe required")
    # Recheck the actual enrolled descriptions, without releasing the parent's copies.
    copy = type(held).inherit_maintenance(held.domains, held.pass_fds)
    copy.close()
    return root


def reviewed_kwargs(held, uv, argv, options):
    root = scope(held)
    if (type(argv) is not list or len(argv) != 9 or argv[-1] != "--offline"
            or argv[:5] != [str(uv), "pip", "install", "--no-config", "--python"]
            or argv[5] != str(root / "probe-venv/bin/python") or argv[6] != "--requirements"
            or type(options) is not dict
            or set(options) - {"cwd", "env", "stdout", "stderr", "text", "encoding", "errors"}):
        raise ValueError("reviewed local requirements spawn required")
    requirements = Path(argv[7])
    env = options.get("env")
    if (not requirements.is_absolute() or not requirements.resolve(strict=True).is_relative_to(root / "tmp")
            or options.get("cwd") != str(requirements.parent)
            or type(env) is not dict or env.get("HOME") != str(root / "user")
            or env.get("HERMES_HOME") != str(root / "data")
            or env.get("UV_PROJECT_ENVIRONMENT") != str(root / "probe-venv")
            or env.get("UV_CACHE_DIR") != str(root / "cache")
            or env.get("UV_PYTHON_DOWNLOADS") != "never"
            or any(k in env for k in ("PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"))):
        raise ValueError("native probe environment changed")
    return {**options, "close_fds": True, "pass_fds": held.pass_fds}


BACKEND = '''import json, os, time
from pathlib import Path
ROOT = Path(__ROOT__)
EXPECTED = __IDENTITIES__
def get_requires_for_build_wheel(config_settings=None): return []
def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    matches = True
    for fd, expected in EXPECTED:
        try:
            value = os.fstat(fd)
            matches = matches and [value.st_dev, value.st_ino] == expected
        except OSError: matches = False
    for sequence in range(400):
        value = {"backendAlive": True, "descriptorsMatch": matches,
                 "pid": os.getpid(), "sequence": sequence}
        scratch = ROOT / "backend-status.next"
        scratch.write_text(json.dumps(value))
        scratch.replace(ROOT / "backend-status.json")
        if (ROOT / "release-backend").exists(): break
        time.sleep(.05)
    raise RuntimeError("controlled local probe abort; no wheel or upgrade completion")
'''


def backend(root, held):
    identities = [[fd, [os.fstat(fd).st_dev, os.fstat(fd).st_ino]] for fd in held.pass_fds]
    return BACKEND.replace("__ROOT__", repr(str(root))).replace("__IDENTITIES__", repr(identities))
