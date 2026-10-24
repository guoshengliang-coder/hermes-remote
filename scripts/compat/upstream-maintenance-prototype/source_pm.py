"""One disposable source/PM preparation transaction. Never finishes maintenance.

The external plan fixes source and locked PM inputs before either changes. The
post-sync generation is an observation checkpoint, not independent authorization
or proof that Hermes, its services or the entire dependency closure can run.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

from importlib.util import module_from_spec, spec_from_file_location


def load(name):
    spec = spec_from_file_location("joint_" + name, Path(__file__).with_name(name + ".py"))
    value = module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


source, pm = load("source_transition"), load("pm_generation")
binding = source.binding
POLICY = "source-pm-preparation-observation-v1"
PM_INPUTS = tuple(sorted(set(pm.PINNED) | set(pm.INPUTS)))
MARKER = b"HG199 isolated joint source and PM preparation v1\n"
PM_CODE = "import sys; sys.path.insert(0, sys.argv[1]); from pm.client import sync_venv; sync_venv([], explicit=True)"


def scope(held, official_fd):
    root = source.scope(held)
    marker = root / ".hg199-isolated-source-pm"
    info = marker.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) != 0o600 or marker.read_bytes() != MARKER
            or type(official_fd) is not int or official_fd < 3):
        raise ValueError("joint fixture and official checkout custody required")
    # Exact clone/common-dir lock. No independent source-only operation may silently omit it.
    path = root / "checkout/.git/hermes-update.lock"
    expected, actual = path.lstat(), os.fstat(official_fd)
    if (not stat.S_ISREG(expected.st_mode) or expected.st_uid != os.getuid() or expected.st_nlink != 1
            or stat.S_IMODE(expected.st_mode) & 0o022
            or (expected.st_dev, expected.st_ino) != (actual.st_dev, actual.st_ino)
            or fcntl.fcntl(official_fd, fcntl.F_GETFL) & os.O_ACCMODE != os.O_RDWR):
        raise ValueError("official checkout descriptor changed")
    fcntl.flock(official_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return root


def validate(held, plan, run, before, after, digest, official_fd, stage):
    root = scope(held, official_fd)
    if (type(plan) is not dict or set(plan) != {"schema", "policy", "source", "pmInputs", "pythonVersion"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["policy"] != POLICY
            or type(digest) is not str or re.fullmatch("[0-9a-f]{64}", digest) is None
            or binding.digest(plan) != digest or len(binding.encode(plan)) > binding.LIMIT
            or type(plan["pmInputs"]) is not dict or set(plan["pmInputs"]) != set(PM_INPUTS)
            or any(type(v) is not str or re.fullmatch("[0-9a-f]{64}", v) is None for v in plan["pmInputs"].values())
            or type(plan["pythonVersion"]) is not list or len(plan["pythonVersion"]) != 3
            or any(type(v) is not int or v < 0 for v in plan["pythonVersion"])):
        raise ValueError("explicit joint source/PM plan required")
    args = (held, plan["source"], run, before, after, binding.digest(plan["source"]))
    source.current(*args, stage)
    source.planned_target(root, plan["source"], after)
    locked = None
    for name, expected in plan["pmInputs"].items():
        data = source.worker.git(root / "checkout", "cat-file", "blob", after + ":" + name)
        if len(data) > 16 * 1024**2 or source.sha(data) != expected:
            raise ValueError("PM expectations differ from fixed target objects")
        if name == "pm/lock.json":
            locked = json.loads(data)["packages"]["python"]["version"]
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)\+[A-Za-z0-9._-]+", locked or "")
    if not match or list(map(int, match.groups())) != plan["pythonVersion"]:
        raise ValueError("planned Python version differs from fixed lock")
    return root


def planned(plan):
    previous = source.pending(plan["source"])
    # Do not duplicate the entire source context in a later row.
    row = {"schema": 1, "kind": "source-pm-plan", "previous": source.sha(previous),
           "sourcePlanSHA": binding.digest(plan["source"]), "policy": plan["policy"],
           "pmInputs": plan["pmInputs"], "pythonVersion": plan["pythonVersion"]}
    return previous + binding.encode(row) + b"\n"


def event(previous, kind, digest, generation=None):
    row = {"schema": 1, "kind": kind, "previous": source.sha(previous), "planSHA": digest}
    if generation is not None:
        row["generation"] = generation
    data = previous + binding.encode(row) + b"\n"
    if len(data) > source.LIMIT:
        raise ValueError("joint journal budget exceeded")
    return data


def require_slots(held, expected):
    if source.slots(held) != [expected] * 2:
        raise ValueError("partial or different joint history")


def append(held, previous, updated, checkpoint=None):
    if len(updated) > source.LIMIT or not updated.startswith(previous):
        raise ValueError("joint journal append required")
    require_slots(held, previous)
    for index, domain in enumerate(held.domains):
        fd = domain.intent()
        try:
            offset = len(previous)
            while offset < len(updated):
                size = os.pwrite(fd, updated[offset:], offset)
                if size <= 0:
                    raise OSError("joint history append failed")
                offset += size
            os.fsync(fd)
        finally:
            os.close(fd)
        if checkpoint:
            checkpoint("slot-synced", index)
    source.intent.sync_registry(held)
    require_slots(held, updated)


def prepare(held, plan, *expectations, official_fd, checkpoint=None):
    validate(held, plan, *expectations, official_fd, "before")
    append(held, source.intent.record(plan["source"]["before"]), planned(plan), checkpoint)
    validate(held, plan, *expectations, official_fd, "before")


def move(held, plan, *expectations, official_fd):
    root = validate(held, plan, *expectations, official_fd, "before")
    require_slots(held, planned(plan))
    source.worker.git(root / "checkout", "checkout", "--detach", expectations[2])
    validate(held, plan, *expectations, official_fd, "after")
    require_slots(held, planned(plan))


def generation(observed):
    return {"environment": observed["selected"]["environment"],
            "environmentIdentity": binding.directory(observed["selected"]["environment"]),
            "runtimeBase": observed["runtime"]["base"],
            "runtimeIdentity": binding.directory(observed["runtime"]["base"]),
            "environmentDigest": observed["environmentDigest"], "runtimeDigest": observed["runtimeDigest"],
            "pinsDigest": binding.digest(observed["pins"]),
            "runtimeVersion": observed["runtime"]["version"], "packagingVersion": observed["runtime"]["packaging"],
            "activationInputsCurrent": observed["activationInputsCurrent"]}


def sync(held, plan, *expectations, official_fd, environment, command_prefix, observe_prefix):
    root = validate(held, plan, *expectations, official_fd, "after")
    before = planned(plan)
    syncing = event(before, "pm-sync-started", expectations[3])
    append(held, before, syncing)  # Never infer that a failed/absent child had no effects.
    path = root / "pm-sync.log"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as output:
        subprocess.run([*command_prefix, sys.executable, "-I", "-S", "-B", "-c", PM_CODE, str(root / "checkout")],
                       env=environment, cwd=root, stdin=subprocess.DEVNULL, stdout=output, stderr=output,
                       pass_fds=(*held.pass_fds, official_fd), timeout=1200, check=True)
    validate(held, plan, *expectations, official_fd, "after")
    require_slots(held, syncing)
    first = pm._observe_root(root, observe_prefix)
    if first["runtime"]["version"] != plan["pythonVersion"]:
        raise ValueError("actual selected Python differs from locked plan")
    if pm._observe_root(root, observe_prefix) != first:
        raise ValueError("selected PM generation changed after sync")
    validate(held, plan, *expectations, official_fd, "after")
    selected = generation(first)
    append(held, syncing, event(syncing, "pm-prepared-observed", expectations[3], selected))
    return binding.digest(selected)


def verify(held, plan, *expectations, official_fd, generation_sha, observe_prefix):
    root = validate(held, plan, *expectations, official_fd, "after")
    syncing = event(planned(plan), "pm-sync-started", expectations[3])
    # Refuse partial/merely-started work BEFORE executing even a read-only interpreter probe.
    slots = source.slots(held)
    if (type(generation_sha) is not str or re.fullmatch("[0-9a-f]{64}", generation_sha) is None
            or slots[0] != slots[1] or not slots[0].startswith(syncing)):
        raise ValueError("complete prepared checkpoint required")
    tail = slots[0][len(syncing):]
    if not tail.endswith(b"\n") or len(tail.splitlines()) != 1:
        raise ValueError("complete prepared checkpoint required")
    saved = binding.decode(tail[:-1])
    if (type(saved) is not dict or set(saved) != {"schema", "kind", "previous", "planSHA", "generation"}
            or type(saved["generation"]) is not dict or binding.digest(saved["generation"]) != generation_sha):
        raise ValueError("independent generation checkpoint expectation changed")
    expected = event(syncing, "pm-prepared-observed", expectations[3], saved["generation"])
    require_slots(held, expected)
    observed = pm._observe_root(root, observe_prefix)
    selected = generation(observed)
    if observed["runtime"]["version"] != plan["pythonVersion"] or selected != saved["generation"]:
        raise ValueError("independent prepared generation expectation changed")
    if pm._observe_root(root, observe_prefix) != observed:
        raise ValueError("actual PM inputs changed during recheck")
    for domain in held.domains:
        fd = domain.intent()
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    source.intent.sync_registry(held)
    validate(held, plan, *expectations, official_fd, "after")
    require_slots(held, expected)
    return {"jointSourcePMPreparedObserved": True, "generationRechecked": True,
            "activationInputsCurrent": observed["activationInputsCurrent"],
            "runtimeVersion": observed["runtime"]["version"], "packagingVersion": observed["runtime"]["packaging"],
            "updaterRecoveryVerified": False, "productionCompletionVerified": False,
            "durableHandoffVerified": False, "globalAdmissionProven": False, "certifiesUpgrade": False}
