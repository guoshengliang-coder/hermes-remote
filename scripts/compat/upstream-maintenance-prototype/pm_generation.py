"""Read-only isolated PM selection/runtime observations, never finish authority.

Only disposable marked fixtures and drained exclusive prototype leases are accepted.
No saved observation can open admission, clear intent or certify a dependency closure.
"""
import hashlib
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys


def load(name):
    spec = importlib.util.spec_from_file_location("pm_observe_" + name, Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


binding, support = load("bound_handoff"), load("handoff_actor")
MARKER = b"HG199 isolated PM generation observation v1\n"
PINNED = {"pm/__init__.py": "67adcba1f0e4db2b2967d07e6b630aca4214b1fac9bd8649dcf221b4bf3fcfac",
          "pm/environments.py": "36f1365f0691d8108114642bfb9aa7f10bbfb28853f5202b3b06f3e4f6e10953",
          "hermes_constants.py": "5d43e6102c69748a2e64fc91623a49e1b0d76623416ce4ec37f23f216c3ce91f"}
INPUTS = ("uv.lock", "pyproject.toml", "pm/lock.json")
MAX_ENTRIES, MAX_BYTES = 65_536, 2 * 1024**3

READ_ONLY = '''
def audit(event, args):
    if event == "open" and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND):
        raise ValueError("observer write refused")
    if event in ("os.mkdir", "os.remove", "os.rmdir", "os.rename", "os.link", "os.symlink", "os.chmod", "os.utime",
                 "os.truncate", "subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.fork", "os.forkpty", "os.kill", "os.killpg",
                 "socket.connect", "socket.bind", "socket.getaddrinfo"):
        raise ValueError("observer side effect refused")
sys.addaudithook(audit)
'''
SELECT = '''
import argparse, collections, contextlib, contextvars, hashlib, json, os, pathlib, re, shlex, shutil, stat, sys
''' + READ_ONLY + '''
root = pathlib.Path(sys.argv[1]); sys.path.insert(0, str(root))
from pm.environments import committed_venv, runtime_facts_path, site_packages, venv_python
v = committed_venv(root)
if v is None: raise ValueError("no committed environment")
print(json.dumps({"environment": str(v), "python": str(venv_python(v)), "site": str(site_packages(v)),
                  "facts": str(runtime_facts_path(root))}), flush=True)
'''
RUNTIME = '''
import json, os, pathlib, sys
''' + READ_ONLY + '''
# Deliberately bypass .pth/site startup; exercise one explicit selected dependency.
sys.path.append(sys.argv[1])
import hashlib, importlib.metadata, sqlite3, ssl
import packaging.version
assert str(packaging.version.Version("0.21.6")) == "0.21.6"
print(json.dumps({"executable": sys.executable, "base": sys.base_prefix, "version": list(sys.version_info[:3]),
                  "packaging": importlib.metadata.version("packaging"), "module": packaging.version.__file__,
                  "ssl": ssl.OPENSSL_VERSION, "sqlite": sqlite3.sqlite_version,
                  "hash": hashlib.sha256(b"HG199 runtime probe").hexdigest()}), flush=True)
'''


def fixture(root):
    root = Path(root)
    binding.directory(root)
    if not root.name.startswith("hg199-pm-generation-") or stat.S_IMODE(root.stat().st_mode) != 0o700:
        raise ValueError("explicit isolated PM fixture required")
    path = root / ".hg199-isolated-pm-generation"
    value = path.lstat()
    if (not stat.S_ISREG(value.st_mode) or stat.S_IMODE(value.st_mode) != 0o600
            or value.st_uid != os.getuid() or value.st_nlink != 1 or path.read_bytes() != MARKER):
        raise ValueError("explicit isolated PM fixture required")
    return root


def source(root):
    if binding.snapshot(root, PINNED) != PINNED:
        raise ValueError("PM selection source requires review")


def stamp(info):
    return [info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns]


def file(path, budget, limit=512 * 1024**2, uv_lock=False):
    if path.resolve(strict=True) != path:
        raise ValueError("noncanonical observation input")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        shared_empty_lock = (uv_lock and before.st_size == 0 and before.st_nlink == 1
                             and stat.S_IMODE(before.st_mode) == 0o666)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid()
                or (before.st_mode & 0o022 and not shared_empty_lock)
                or before.st_size > limit):
            raise ValueError("unsafe observation input")
        budget[0] += 1
        budget[1] += before.st_size
        if budget[0] > MAX_ENTRIES or budget[1] > MAX_BYTES:
            raise ValueError("observation budget exceeded")
        digest, size = hashlib.sha256(), 0
        while chunk := os.read(fd, 65_536):
            size += len(chunk)
            if size > before.st_size:
                raise ValueError("observation input changed")
            digest.update(chunk)
        if (size != before.st_size or stamp(os.fstat(fd)) != stamp(before)
                or stamp(path.lstat()) != stamp(before)):
            raise ValueError("observation input changed")
        return {"identity": stamp(before), "sha256": digest.hexdigest()}
    finally:
        os.close(fd)


def tree(root, allowed, budget, uv_lock=None):
    entries = {}

    def walk(path, depth):
        if depth > 64:
            raise ValueError("observation depth exceeded")
        before = path.lstat()
        key = str(path.relative_to(root))
        if stat.S_ISLNK(before.st_mode):
            target = path.resolve(strict=True)
            if not any(target == p or p in target.parents for p in allowed):
                raise ValueError("observation link escapes selected roots")
            entries[key] = {"identity": stamp(before), "link": os.readlink(path)}
        elif stat.S_ISDIR(before.st_mode):
            binding.directory(path)
            entries[key] = {"identity": stamp(before)}
            for child in sorted(path.iterdir()):
                walk(child, depth + 1)
        else:
            entries[key] = file(path, budget, uv_lock=path == uv_lock)
        if not stat.S_ISREG(before.st_mode):
            budget[0] += 1
            if budget[0] > MAX_ENTRIES:
                raise ValueError("observation budget exceeded")
        if stamp(path.lstat()) != stamp(before):
            raise ValueError("observation tree changed")
    walk(root, 0)
    return {"digest": binding.digest(entries), "entries": len(entries)}


def child(root, executable, code, *args, command_prefix=()):
    env = {"HOME": str(root / "user"), "HERMES_HOME": str(root / "data"), "PATH": os.defpath,
           "TMPDIR": str(root / "tmp"), "HERMES_DISABLE_LAZY_INSTALLS": "1"}
    process = subprocess.Popen([*command_prefix, str(executable), "-I", "-S", "-B", "-c", code, *map(str, args)],
                               env=env, cwd=root, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL)
    try:
        value = support.read(process.stdout.fileno())
        if process.wait(timeout=10) != 0 or process.stdout.read(1):
            raise ValueError("observation child failed")
        return value
    finally:
        if process.poll() is None:
            process.kill()  # Only the child just created by this observer.
        process.wait(timeout=5)
        process.stdout.close()


def scope(held):
    if not held.maintenance or not held.ready or len(held.domains) != 2:
        raise ValueError("drained exclusive fixture custody required")
    root = fixture(held.domains[0].registry.parent)
    if ({(d.binding["kind"], str(d.directory)) for d in held.domains}
            != {("home", str(root / "data")), ("checkout", str(root / "checkout"))}
            or any(d.registry != root / "control" for d in held.domains)):
        raise ValueError("wrong PM fixture scope")
    duplicate = held.__class__.inherit_maintenance(held.domains, held.pass_fds)
    duplicate.close()
    return root


def pins(root, facts, budget):
    checkout = root / "checkout"
    state = facts.parent
    values = {"facts": file(facts, budget, 65_536)}
    stamps = state / "inputs"
    published = stamps.exists() or stamps.is_symlink()
    values["activationInputsCurrent"] = published
    for name in INPUTS:
        path, recorded = checkout / name, state / "inputs" / name
        values[name] = file(path, budget, 16 * 1024**2)
        if published:
            values["stamp:" + name] = file(recorded, budget, 65_536)
            if path.stat().st_mtime_ns != recorded.stat().st_mtime_ns:
                raise ValueError("activation input stamp changed")
    if published:
        project = state / "inputs/.project-root"
        values["project"] = file(project, budget, 4096)
        if project.read_text() != str(checkout):
            raise ValueError("activation input belongs to another checkout")
    return values


def selection(root, command_prefix):
    value = child(root, sys.executable, SELECT, root / "checkout", command_prefix=command_prefix)
    if (type(value) is not dict or set(value) != {"environment", "python", "site", "facts"}
            or any(type(v) is not str for v in value.values())):
        raise ValueError("PM selection shape changed")
    checkout = root / "checkout"
    state = root / "data/installs" / hashlib.sha256(str(checkout).encode()).hexdigest()[:16]
    environment, python, site, facts = (Path(value[k]) for k in ("environment", "python", "site", "facts"))
    if (facts != state / "facts.json" or not environment.is_relative_to(state / "environments")
            or environment.resolve(strict=True) != environment or python != environment / "bin/python"
            or site.resolve(strict=True) != site or not site.is_relative_to(environment / "lib")
            or not python.resolve(strict=True).is_relative_to(root / "data/tools")):
        raise ValueError("PM selection escaped fixture")
    binding.directory(environment)
    return value


def observe(held, command_prefix=()):
    root = scope(held)
    observed = _observe_root(root, command_prefix)
    scope(held)
    return observed


def _observe_root(root, command_prefix=()):
    """Shared observation engine; callers must independently validate their fixture/lease scope."""
    source(root / "checkout")
    selected = selection(root, command_prefix)
    budget = [0, 0]
    before = pins(root, Path(selected["facts"]), budget)
    cfg = file(Path(selected["environment"]) / "pyvenv.cfg", budget, 65_536)
    binary = file(Path(selected["python"]).resolve(strict=True), budget)
    runtime = child(root, selected["python"], RUNTIME, selected["site"], command_prefix=command_prefix)
    keys = {"executable", "base", "version", "packaging", "module", "ssl", "sqlite", "hash"}
    if (type(runtime) is not dict or set(runtime) != keys or runtime["executable"] != selected["python"]
            or any(type(runtime[k]) is not str or not runtime[k] for k in keys - {"version"})
            or type(runtime["version"]) is not list or len(runtime["version"]) != 3
            or any(type(v) is not int or v < 0 for v in runtime["version"])
            or runtime["version"][:2] < [3, 11]
            or runtime["hash"] != hashlib.sha256(b"HG199 runtime probe").hexdigest()):
        raise ValueError("selected runtime did not verify")
    base, module = Path(runtime["base"]), Path(runtime["module"])
    expected_site = Path(selected["environment"]) / "lib" / ("python%d.%d" % tuple(runtime["version"][:2])) / "site-packages"
    if (not base.is_relative_to(root / "data/tools") or base.resolve(strict=True) != base
            or Path(selected["site"]) != expected_site
            or not module.is_relative_to(Path(selected["site"])) or module.resolve(strict=True) != module):
        raise ValueError("selected runtime inputs did not match")
    environment = Path(selected["environment"])
    trees = [tree(p, (environment, base), budget, uv_lock=environment / ".lock") for p in (environment, base)]
    after = pins(root, Path(selected["facts"]), [0, 0])
    if (after != before or selection(root, command_prefix) != selected
            or file(Path(selected["python"]).resolve(strict=True), [0, 0]) != binary):
        raise ValueError("PM inputs changed during observation")
    source(root / "checkout")
    return {"schema": 1, "root": str(root), "rootIdentity": binding.directory(root), "selected": selected,
            "pins": before, "cfg": cfg, "binary": binary, "runtime": runtime,
            "activationInputsCurrent": before["activationInputsCurrent"],
            "environmentDigest": trees[0]["digest"], "runtimeDigest": trees[1]["digest"],
            "environmentEntries": trees[0]["entries"], "runtimeEntries": trees[1]["entries"],
            "observedBytes": budget[1]}


def revalidate(held, expected, command_prefix=()):
    if type(expected) is not dict or observe(held, command_prefix) != expected:
        raise ValueError("PM generation observation changed")
