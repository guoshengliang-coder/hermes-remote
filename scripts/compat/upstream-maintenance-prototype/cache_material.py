"""Isolated cache preservation/cold-source observation; never installation or activation authority."""
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import stat
import uuid


def load(name):
    spec = importlib.util.spec_from_file_location("cache_material_" + name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


joint, pm = load("source_pm"), load("pm_generation")
binding, intent, worker = joint.binding, joint.source.intent, joint.source.worker
MARKER = b"HG199 isolated source cache material preparation v1\n"
POLICY = "source-cache-preservation-cold-material-v1"
IMPORTS = (".py", ".pyw", ".pyc", ".pyo", ".so", ".pyd", ".dylib")
PLAN_LIMIT = 1024 * 1024


def scope(held):
    if not held.maintenance or not held.ready or len(held.domains) != 2:
        raise ValueError("drained cache-material custody required")
    root = held.domains[0].registry.parent
    binding.directory(root)
    marker = root / ".hg199-isolated-cache-material"
    if (not root.name.startswith("hg199-cache-material-") or root.stat().st_mode & 0o077
            or binding.snapshot(root, [marker.name])[marker.name] != hashlib.sha256(MARKER).hexdigest()
            or marker.stat().st_mode & 0o077
            or any(d.registry != root / "control" for d in held.domains)
            or {(d.binding["kind"], str(d.directory)) for d in held.domains} != {
                ("home", str(root / "home")), ("checkout", str(root / "home/hermes-agent"))}):
        raise ValueError("marked isolated cache-material fixture required")
    duplicate = held.__class__.inherit_maintenance(held.domains, held.pass_fds)
    duplicate.close()
    return root


def record_file(path, budget=None):
    value = pm.file(path, [0, 0] if budget is None else budget)
    if value["identity"][4] != 1:
        raise ValueError("single-link source material required")
    return value


def git_material_ids(base, names):
    # Delegate Git's object format to Git; SHA-256 separately binds security-sensitive material.
    # Quote every byte with Git's C-style octal syntax, including LF/quotes/non-ASCII path bytes.
    records = b"".join(b'"' + b"".join(b"\\%03o" % byte for byte in os.fsencode(name)) + b'"\n' for name in names)
    if len(records) > 16 * 1024**2:
        raise ValueError("bounded Git material names required")
    values = worker.git(base, "hash-object", "--no-filters", "--stdin-paths", input=records).splitlines()
    if len(values) != len(names) or any(re.fullmatch(b"[0-9a-f]{40}", value) is None for value in values):
        raise ValueError("Git material observation differs")
    return dict(zip(names, (value.decode() for value in values)))


def source_inventory(root, commit):
    """Data-only verification against Git, allowing only explicitly recognized warm caches.

    It does not relax the product worker. Cold source still has to pass its strict verifier.
    Tracked regular bytes are compared against Git objects. The sole normalization is declared
    text/eol=crlf PowerShell material; importable Python/native bytes are always exact.
    """
    warm, cold = root / "home/hermes-agent", root / "cold-source"
    worker.verify_source(cold, commit)
    worker.validate_git_configuration(warm, installation=True)
    if (worker.git(warm, "rev-parse", "HEAD").decode().strip() != commit
            or worker.git(warm, "status", "--porcelain=v1", "--untracked-files=normal")
            or any(row and row[:1] != b"H" for row in worker.git(warm, "ls-files", "-v", "-z").split(b"\0"))):
        raise ValueError("warm tracked source differs")
    tracked, observations, regular, budget = {}, {}, [], [0, 0]
    for row in worker.local_source_tree(cold, commit):
        if not row:
            continue
        meta, raw_name = row.split(b"\t", 1)
        mode, kind, oid = meta.split()
        if kind == b"tree":
            continue
        if kind != b"blob" or mode not in (b"100644", b"100755", b"120000"):
            raise ValueError("reviewed Git material required")
        name = raw_name.decode()
        tracked[name] = oid.decode()
        if mode != b"120000":
            regular.append(name)
        values = []
        for base in (warm, cold):
            path = base / name
            if mode == b"120000":
                before = path.lstat()
                if not stat.S_ISLNK(before.st_mode):
                    raise ValueError("tracked material link differs")
                data = os.readlink(path).encode("utf-8", "surrogateescape")
                if pm.stamp(path.lstat()) != pm.stamp(before):
                    raise ValueError("tracked link changed")
                digest = worker.git(base, "hash-object", "--no-filters", "--stdin", input=data).decode().strip()
                if digest != oid.decode():
                    raise ValueError("tracked link differs from fixed Git")
                values.append({"identity": pm.stamp(before), "sha256": hashlib.sha256(data).hexdigest()})
            else:
                values.append(record_file(path, budget))
        observations[name] = values
    for index, base in enumerate((warm, cold)):
        actual = git_material_ids(base, regular)
        for name in regular:
            path = base / name
            if actual[name] != tracked[name]:
                # Git's committed *.ps1 text eol=crlf deliberately changes worktree bytes.
                # Never invoke a clean/smudge filter or normalize importable source artifacts.
                attributes = worker.git(base, "check-attr", "--cached", "-z", "text", "eol", "--", name)
                expected_attributes = (name.encode() + b"\0text\0set\0" + name.encode() + b"\0eol\0crlf\0")
                if not name.endswith(".ps1") or attributes != expected_attributes or path.stat().st_size > 16 * 1024**2:
                    raise ValueError("tracked material bytes differ from fixed Git")
                normalized = path.read_bytes().replace(b"\r\n", b"\n")
                if worker.git(base, "hash-object", "--no-filters", "--stdin", input=normalized).decode().strip() != tracked[name]:
                    raise ValueError("declared CRLF material differs from fixed Git")
        for name, values in observations.items():
            if pm.stamp((base / name).lstat()) != values[index]["identity"]:
                raise ValueError("tracked bytes changed during Git observation")
    caches, count = {}, 0
    for directory, children, files in os.walk(warm, followlinks=False):
        relative = Path(directory).relative_to(warm)
        if str(relative) == ".":
            children[:] = [n for n in children if n not in (".git", "venv", ".venv", ".hermes", "node_modules")]
        if str(relative) in ("ui-tui", "web", "apps/desktop"):
            children[:] = [n for n in children if n != "node_modules"]
        count += len(children) + len(files)
        if count > 65_536 or len(relative.parts) > 64:
            raise ValueError("bounded warm-source inventory required")
        if any((Path(directory) / n).is_symlink() for n in children):
            raise ValueError("source directory alias refused")
        for name in files:
            path = Path(directory) / name
            key = str(path.relative_to(warm))
            if key in tracked or not name.lower().endswith(IMPORTS):
                continue
            if path.parent.name != "__pycache__" or path.suffix != ".pyc":
                raise ValueError("unreviewed extra executable material")
            source = Path(importlib.util.source_from_cache(str(path)))
            source_name = str(source.relative_to(warm))
            if source_name not in tracked or source.suffix != ".py" or len(caches) >= 512:
                raise ValueError("bounded source-backed cache names required")
            caches[key] = {"source": source_name, **record_file(path, budget)}
    if not caches:
        raise ValueError("explicit warm cache material required")
    return {"trackedDigest": binding.digest(observations), "caches": caches,
            "tree": worker.git(cold, "rev-parse", commit + "^{tree}").decode().strip()}


def make_plan(held, run, commit):
    root = scope(held)
    if str(uuid.UUID(run)) != run or re.fullmatch("[0-9a-f]{40}", commit) is None:
        raise ValueError("independent source/cache identity required")
    observed = source_inventory(root, commit)
    names = sorted({v["source"] for v in observed["caches"].values()})[:64]
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
    context = binding.context(run, commit, root / "home/hermes-agent", root / "home",
                              "cache_material.prepare", records, names)
    return {"schema": 1, "policy": POLICY, "context": context,
            "coldIdentity": binding.directory(root / "cold-source"), **observed}


def shape(held, plan, run, commit, expected_sha):
    root = scope(held)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "context", "coldIdentity", "trackedDigest", "caches", "tree"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY
            or type(expected_sha) is not str or re.fullmatch("[0-9a-f]{64}", expected_sha) is None
            or binding.digest(plan) != expected_sha or len(binding.encode(plan)) > PLAN_LIMIT
            or type(plan["context"]) is not dict or plan["context"].get("run") != run
            or plan["context"].get("source") != commit):
        raise ValueError("bounded independent cache-material plan required")
    return root


def validate(held, plan, run, commit, expected_sha):
    root = shape(held, plan, run, commit, expected_sha)
    if binding.encode(make_plan(held, run, commit)) != binding.encode(plan):
        raise ValueError("original warm/cold source or cache material changed")
    return root


def started(plan):
    previous = intent.record(plan["context"])
    return previous + binding.encode({"schema": 1, "kind": "cache-material-started",
        "previous": joint.source.sha(previous), "planSHA": binding.digest(plan)}) + b"\n"


def prepared(plan, manifest_sha):
    previous = started(plan)
    return previous + binding.encode({"schema": 1, "kind": "cache-material-prepared",
        "previous": joint.source.sha(previous), "planSHA": binding.digest(plan), "manifestSHA": manifest_sha}) + b"\n"


def begin(held, plan, run, commit, expected_sha, checkpoint=None):
    validate(held, plan, run, commit, expected_sha)
    intent.begin(held, plan["context"])
    joint.append(held, intent.record(plan["context"]), started(plan), checkpoint)


def private_read(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1
                or stat.S_IMODE(before.st_mode) != 0o600 or not 0 < before.st_size <= PLAN_LIMIT):
            raise ValueError("private bounded material record required")
        data = os.read(fd, PLAN_LIMIT + 1)
        if (len(data) != before.st_size or pm.stamp(os.fstat(fd)) != pm.stamp(before)
                or pm.stamp(path.lstat()) != pm.stamp(before)):
            raise ValueError("material record changed during read")
        return binding.decode(data)
    finally:
        os.close(fd)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_new(path, value):
    data = binding.encode(value)
    if not 0 < len(data) <= PLAN_LIMIT:
        raise ValueError("bounded material record required")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as destination:
        destination.write(data)
        destination.flush()
        os.fsync(destination.fileno())
    sync_directory(path.parent)


def preserve(held, plan, run, commit, expected_sha, checkpoint=None):
    root = shape(held, plan, run, commit, expected_sha)
    joint.require_slots(held, started(plan))  # Refuse before Git/data probes on unknown history.
    validate(held, plan, run, commit, expected_sha)
    destination = root / "cache-material"
    destination.mkdir(mode=0o700)  # Never reuse/overwrite an incomplete archive.
    archived = {}
    for index, (name, expected) in enumerate(sorted(plan["caches"].items())):
        original = root / "home/hermes-agent" / name
        output = destination / (str(index) + ".pyc")
        source_fd = os.open(original, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        target_fd = None
        try:
            target_fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            if pm.stamp(os.fstat(source_fd)) != expected["identity"]:
                raise ValueError("original cache identity changed")
            h, size = hashlib.sha256(), 0
            with os.fdopen(target_fd, "wb") as target:
                target_fd = None
                while chunk := os.read(source_fd, 65_536):
                    size += len(chunk)
                    if size > expected["identity"][5]:
                        raise ValueError("cache changed during preservation")
                    h.update(chunk)
                    target.write(chunk)
                target.flush()
                os.fsync(target.fileno())
            if (h.hexdigest() != expected["sha256"] or size != expected["identity"][5]
                    or pm.stamp(os.fstat(source_fd)) != expected["identity"]
                    or pm.stamp(original.lstat()) != expected["identity"]):
                raise ValueError("cache preservation differs")
        finally:
            os.close(source_fd)
            if target_fd is not None:
                os.close(target_fd)
        archived[name] = {"file": output.name, **record_file(output)}
        sync_directory(destination)
        if checkpoint:
            checkpoint("cache-copied", index)
    validate(held, plan, run, commit, expected_sha)
    manifest = {"schema": 1, "policy": POLICY, "run": run, "source": commit,
                "planSHA": expected_sha, "files": archived}
    write_new(destination / "manifest.json", manifest)
    manifest_sha = binding.digest(manifest)
    joint.append(held, started(plan), prepared(plan, manifest_sha), checkpoint)
    return manifest_sha


def recheck(held, plan, run, commit, expected_sha, manifest_sha):
    root = shape(held, plan, run, commit, expected_sha)
    if type(manifest_sha) is not str or re.fullmatch("[0-9a-f]{64}", manifest_sha) is None:
        raise ValueError("independent manifest digest required")
    joint.require_slots(held, prepared(plan, manifest_sha))
    validate(held, plan, run, commit, expected_sha)
    manifest = private_read(root / "cache-material/manifest.json")
    if (binding.digest(manifest) != manifest_sha or type(manifest) is not dict
            or set(manifest) != {"schema", "policy", "run", "source", "planSHA", "files"}
            or type(manifest["schema"]) is not int or manifest["schema"] != 1
            or manifest["policy"] != POLICY or manifest["run"] != run or manifest["source"] != commit
            or manifest["planSHA"] != expected_sha or type(manifest["files"]) is not dict
            or set(manifest["files"]) != set(plan["caches"])):
        raise ValueError("material manifest differs")
    for index, (name, expected) in enumerate(sorted(plan["caches"].items())):
        observed = {"file": str(index) + ".pyc", **record_file(root / "cache-material" / (str(index) + ".pyc"))}
        if observed != manifest["files"][name] or observed["sha256"] != expected["sha256"]:
            raise ValueError("preserved cache material changed")
    intent.sync_registry(held)
    joint.require_slots(held, prepared(plan, manifest_sha))
    return {"warmCachesPreserved": len(plan["caches"]), "coldSourceRechecked": True,
            "sourceActivationVerified": False, "cacheExecutionVerified": False,
            "globalAdmissionProven": False, "updaterRecoveryVerified": False, "certifiesUpgrade": False}
