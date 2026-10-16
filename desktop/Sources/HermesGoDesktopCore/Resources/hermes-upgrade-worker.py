"""GO's pinned-source bridge to upstream UpdateLock and historical takeover.

No shell, pip fallback, reset, stash or moving branch target. The machine adapter must retire
all writers and commit its backup/journal before sending `update`. This bridge never certifies
service recovery: its process result still requires independent completion verification.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
import stat
import subprocess
import sys
import uuid

MAX_JSON = 64 * 1024


class Refused(Exception):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refused("duplicate_field")
        result[key] = value
    return result


def private_bytes(path: Path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_nlink != 1 or info.st_mode & 0o077 or info.st_size > MAX_JSON):
            raise Refused("unsafe_record")
        raw = os.read(fd, MAX_JSON + 1)
        if len(raw) > MAX_JSON:
            raise Refused("oversize_record")
        return raw
    finally:
        os.close(fd)


def private_json(path: Path):
    return json.loads(private_bytes(path), object_pairs_hook=unique_object)


def durable_json(path: Path, value):
    data = json.dumps(value, sort_keys=True).encode()
    if len(data) > MAX_JSON:
        raise Refused("oversize_record")
    temporary = path.with_name("." + str(uuid.uuid4()) + ".tmp")
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def canonical_directory(text: str, *, private=False) -> Path:
    path = Path(text)
    resolved = path.resolve()
    # Foundation normalizes Darwin's /private/tmp back to /tmp, while POSIX realpath does the
    # reverse. Admit only that OS alias, never a caller-created symlink alias.
    tmp_alias = (sys.platform == "darwin" and text.startswith("/tmp/")
                 and str(resolved) == "/private" + text)
    if not path.is_absolute() or (str(resolved) != text and not tmp_alias) or path == Path("/"):
        raise Refused("unsafe_path")
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or (private and info.st_mode & 0o077):
        raise Refused("unsafe_directory")
    return resolved


def object_id(text):
    return isinstance(text, str) and len(text) in (40, 64) and all(c in "0123456789abcdef" for c in text)


def git(root: Path, *arguments, check=True, input=None, snapshot_fetch=False):
    # No hooks, helpers, filters via global/system config, replace objects, fsmonitor or background gc.
    command = ["/usr/bin/git", "--no-replace-objects", "--no-optional-locks", "-c", "core.fsmonitor=false",
               "-c", "core.hooksPath=/dev/null", "-c", "credential.helper=", "-c", "gc.auto=0",
               "-c", "maintenance.auto=false", "-C", str(root), *arguments]
    if snapshot_fetch and (not arguments or arguments[0] != "fetch"):
        raise Refused("invalid_snapshot_fetch")
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update({"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                        "GIT_GRAFT_FILE": os.devnull, "GIT_NO_LAZY_FETCH": "1",
                        "GIT_ALLOW_PROTOCOL": "file" if snapshot_fetch else ""})
    # Transport denial also protects Git versions that do not understand GIT_NO_LAZY_FETCH.
    # Only the explicit fetch from an already verified local snapshot admits the file transport.
    return subprocess.run(command, stdin=subprocess.DEVNULL if input is None else None,
                          input=input, env=environment, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, timeout=120, check=check).stdout


def validate_git_configuration(root: Path, *, installation=False):
    # Read without expanding includes. Reject executable filters and URL rewriting BEFORE status,
    # fetch or checkout can invoke them; disabling hooks alone does not close these paths.
    partial = {}
    for entry in git(root, "config", "--local", "--no-includes", "--null", "--list").split(b"\0"):
        if not entry:
            continue
        key, _, value = entry.partition(b"\n")
        key = key.lower()
        if (key.startswith((b"filter.", b"include.", b"includeif.", b"url.", b"protocol.", b"alias.", b"submodule."))
                or key in (b"core.attributesfile", b"core.sshcommand", b"core.gitproxy",
                           b"extensions.worktreeconfig")):
            raise Refused("unsupported_git_configuration")
        if key.startswith(b"remote.") and key.endswith(b".vcs"):
            raise Refused("unsupported_git_configuration")
        if key == b"extensions.partialclone" or (key.startswith(b"remote.")
                and key.endswith((b".promisor", b".partialclonefilter"))):
            if key in partial:
                raise Refused("unsupported_git_configuration")
            partial[key] = value
    standard = {b"remote.origin.promisor": b"true", b"remote.origin.partialclonefilter": b"blob:none"}
    if partial and (not installation or partial not in
                    (standard, {**standard, b"extensions.partialclone": b"origin"})):
        raise Refused("unsupported_git_configuration")


def local_source_tree(root: Path, commit: str):
    """Require the pinned tip's commit, trees and ALL blobs locally, not all historical blobs.

    Promisor metadata is never proof of object availability. Reading the tree and checking objects
    uses the transport-denied Git helper; missing objects cannot start an implicit remote fetch.
    """
    try:
        rows = git(root, "ls-tree", "-r", "-t", "-z", commit).split(b"\0")
        objects = {commit: b"commit"}
        if len(rows) > 32768:
            raise Refused("source_inventory_unbounded")
        for row in rows:
            if not row:
                continue
            meta, _ = row.split(b"\t", 1)
            _, kind, oid = meta.split()
            if kind not in (b"tree", b"blob"):
                raise Refused("unsupported_source_object")
            objects[oid.decode()] = kind
        tree = git(root, "rev-parse", "--verify", commit + "^{tree}").decode().strip()
        objects[tree] = b"tree"
        response = git(root, "cat-file", "--batch-check=%(objectname) %(objecttype)",
                       input=("\n".join(objects) + "\n").encode()).splitlines()
        if response != [oid.encode() + b" " + kind for oid, kind in objects.items()]:
            raise Refused("source_objects_unavailable")
        return rows
    except subprocess.SubprocessError:
        raise Refused("source_objects_unavailable") from None


def verify_source(root: Path, commit: str, *, installation=False):
    validate_git_configuration(root, installation=installation)
    if git(root, "rev-parse", "HEAD").decode().strip() != commit:
        raise Refused("source_moved")
    rows = local_source_tree(root, commit)
    if git(root, "status", "--porcelain=v1", "--untracked-files=normal"):
        raise Refused("dirty_source")
    if any(row and row[:1] != b"H" for row in git(root, "ls-files", "-v", "-z").split(b"\0")):
        raise Refused("hidden_index_edits")
    # Git status can hide assume-unchanged/skip-worktree edits. Check Python blobs directly before
    # importing the official graph, including ignored/untracked modules that could shadow it.
    expected = {}
    for row in rows:
        if not row:
            continue
        meta, name = row.split(b"\t", 1)
        path = name.decode()
        if path.endswith(".py"):
            mode, kind, blob = meta.split()
            if kind != b"blob" or mode not in (b"100644", b"100755"):
                raise Refused("unsafe_source")
            expected[path] = blob.decode()
    # The takeover's import graph reaches other upstream packages (for example utils). Check the
    # whole snapshot so an ignored helper outside hermes_cli/pm cannot enter that graph either.
    # A live checkout additionally contains PM state and the retained legacy venv. These are
    # dependency closures, not source imports; their independent certification remains required.
    excluded = {".git", ".hermes", "venv", ".venv"} if installation else {".git"}
    actual = set()
    for directory, children, files in os.walk(root, followlinks=False):
        if Path(directory) == root:
            if any((root / name / entry).exists() for name in ("venv", ".venv")
                   for entry in ("__init__.py", "__init__.pyc")):
                raise Refused("unexpected_source")
            children[:] = [name for name in children if name not in excluded]
        if installation and str(Path(directory).relative_to(root)) in (".", "ui-tui", "web", "apps/desktop"):
            children[:] = [name for name in children if name != "node_modules"]
        for name in children:
            if (Path(directory) / name).is_symlink():
                raise Refused("unsafe_source")
        actual.update(str((Path(directory) / name).relative_to(root)) for name in files if name.endswith(".py"))
    if actual != set(expected):
        raise Refused("unexpected_source")
    for relative, blob in expected.items():
        path = root / relative
        if path.is_symlink() or path.resolve() != path:
            raise Refused("unsafe_source")
        data = path.read_bytes()
        digest = hashlib.sha1 if len(blob) == 40 else hashlib.sha256
        if digest(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() != blob:
            raise Refused("changed_source")


def emit(event: str, **values):
    try:
        print(json.dumps({"schema": 1, "event": event, **values}), flush=True)
    except BrokenPipeError:
        pass  # Desktop may exit; a committed updater must continue its tail.


def validate_configuration(config):
    keys = {"schema", "runID", "upstreamUpdateID", "sourceRoot", "checkout", "hermesHome",
            "runDirectory", "journal", "sourceCommit", "targetCommit", "targetVersion", "preUpdateVersion", "plan"}
    if not isinstance(config, dict) or set(config) != keys or type(config["schema"]) is not int or config["schema"] != 1:
        raise Refused("invalid_configuration")
    uuid.UUID(config["runID"]); uuid.UUID(config["upstreamUpdateID"])
    if not object_id(config["sourceCommit"]) or not object_id(config["targetCommit"]):
        raise Refused("invalid_commit")
    if not isinstance(config["targetVersion"], str) or not re.fullmatch(r"(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})", config["targetVersion"]):
        raise Refused("invalid_version")
    source = canonical_directory(config["sourceRoot"])
    home = canonical_directory(config["hermesHome"])
    root = canonical_directory(config["checkout"])
    run = canonical_directory(config["runDirectory"], private=True)
    if root != home / "hermes-agent" or source == root or source.is_relative_to(home) or run.is_relative_to(home):
        raise Refused("unsafe_layout")
    if (canonical_directory(os.environ.get("HERMES_HOME", "")) != home
            or os.environ.get("HERMES_UPDATE_CORRELATION_ID") != config["runID"]):
        raise Refused("wrong_environment")
    if not isinstance(config["plan"], dict) or not isinstance(config["preUpdateVersion"], str):
        raise Refused("invalid_plan")
    verify_source(source, config["targetCommit"])
    return source, root, home, run


def validate_journal(config, root: Path):
    journal = private_json(Path(config["journal"]))
    if not isinstance(journal, dict):
        raise Refused("journal_mismatch")
    target = journal.get("target", {})
    backup = journal.get("backup", {})
    if (not isinstance(target, dict) or not isinstance(backup, dict)
            or type(journal.get("schemaVersion")) is not int):
        raise Refused("journal_mismatch")
    digest = backup.get("manifestSHA256", "")
    if (journal.get("schemaVersion") != 1 or journal.get("runID") != config["runID"]
            or journal.get("upstreamUpdateID") != config["upstreamUpdateID"]
            or canonical_directory(journal.get("checkout", "")) != root
            or canonical_directory(journal.get("hermesHome", "")) != canonical_directory(config["hermesHome"])
            or journal.get("sourceCommit") != config["sourceCommit"] or journal.get("phase") != "updating"
            or target.get("commit") != config["targetCommit"] or backup.get("runID") != config["runID"]
            or target.get("version") != config["targetVersion"]
            or not isinstance(digest, str) or len(digest) != 64
            or any(c not in "0123456789abcdef" for c in digest)):
        raise Refused("journal_mismatch")


def arm_installation_obligation(root: Path, home: Path, target: str, runtimes: list):
    from hermes_cli import update_host_obligation as obligation
    # The verified helper was loaded from the asset snapshot before the live tree moved. Upstream
    # derives its install key from __file__; project that origin to the confirmed installation so
    # the pre-move debt and the later live takeover name the SAME host obligation. No source bytes
    # are changed. This private dependency must be re-reviewed for each certified upstream target.
    origin = obligation.__file__
    try:
        obligation.__file__ = str(root / "hermes_cli/update_host_obligation.py")
        if not obligation.arm_host_obligation(home / obligation.PROFILE_MARKER_NAME,
                                             expected_sha=target, runtimes=runtimes):
            raise Refused("obligation_unavailable")
        return obligation.host_obligation_path()
    finally:
        obligation.__file__ = origin


def validate_update_plan(config):
    plan = config.get("plan", {})
    if (not isinstance(plan, dict) or plan.get("install_method") != "git"
            or plan.get("updatable_in_place") is not True
            or plan.get("expected_sha") != config["sourceCommit"]
            or plan.get("expected_version") != config["preUpdateVersion"]
            or not isinstance(plan.get("profiles"), list) or "default" not in plan["profiles"]
            or len(plan["profiles"]) > 128 or not all(isinstance(p, str) for p in plan["profiles"])
            or not isinstance(plan.get("runtimes"), list) or len(plan["runtimes"]) > 128
            or not all(isinstance(r, dict) and r.get("kind") in ("gateway", "serve", "dashboard")
                       and r.get("profile") in plan["profiles"] for r in plan["runtimes"])):
        raise Refused("invalid_plan")


def validate_target_tag(source: Path, root: Path, version: str, commit: str):
    tag_ref = "refs/tags/v" + version
    pinned = git(source, "rev-parse", "--verify", tag_ref + "^{commit}", check=False).decode().strip()
    if pinned != commit:
        raise Refused("target_tag_moved")
    existing = git(root, "rev-parse", "--verify", tag_ref, check=False).strip()
    if existing and git(root, "rev-parse", "--verify", tag_ref + "^{commit}", check=False).decode().strip() != commit:
        raise Refused("target_tag_conflict")
    return tag_ref


def validate_finish_handoff(config, root: Path, home: Path, run: Path):
    """Only a completed PM handoff BEFORE finish effects can be replayed automatically.

    Read this run's unique archive, never latest.json. A terminal or advanced receipt is evidence
    to reconcile, not permission to erase debts or replay service/migration effects.
    """
    context = private_json(run / "official-context.json")
    checkpoint = private_json(run / "official-finish-state.json")
    if (not isinstance(checkpoint, dict) or type(checkpoint.get("schema")) is not int
            or checkpoint != finish_checkpoint(config, root, run, "ready")):
        raise Refused("finish_checkpoint_not_ready")
    keys = {"root", "update_id", "receipt", "assume_yes", "gateway_mode", "desktop",
            "pre_update_version", "plan", "pm_receipt"}
    if (not isinstance(context, dict) or set(context) != keys or context["root"] != str(root)
            or context["update_id"] != config["upstreamUpdateID"]
            or context["assume_yes"] is not True or context["gateway_mode"] is not False
            or context["desktop"] is not False or context["pre_update_version"] != config["preUpdateVersion"]
            or context["plan"] != config["plan"]):
        raise Refused("finish_handoff_mismatch")
    receipt, pm = context["receipt"], context["pm_receipt"]
    pre = {"sha": config["sourceCommit"], "version": config["preUpdateVersion"]}
    def valid_receipt(value):
        return (isinstance(value, dict) and type(value.get("schema")) is int and value["schema"] == 1
                and value.get("update_id") == config["upstreamUpdateID"]
                and value.get("correlation_id") == config["runID"] and value.get("pre_update") == pre
                and value.get("outcome") == "running" and value.get("finished_at") is None
                and isinstance(value.get("started_at"), str) and bool(value["started_at"])
                and type(value.get("pid")) is int and value["pid"] > 0
                and value.get("post_update") == {} and value.get("steps") == []
                and value.get("skips") == [] and value.get("stages", []) == []
                and value.get("gateway_restart") == {} and value.get("fleet") == []
                and not any(value.get(key) for key in ("followups", "user_action", "pending_manual_serves",
                                                       "carried_manual_serves", "stop_reason")))
    if (not valid_receipt(receipt) or not isinstance(pm, dict)
            or type(pm.get("schema")) is not int or pm["schema"] != 1
            or pm.get("update_id") != config["upstreamUpdateID"] or pm.get("kind") != "sync"
            or pm.get("outcome") != "ok" or type(pm.get("exit_code")) is not int or pm["exit_code"] != 0
            or not isinstance(pm.get("finished_at"), str) or not pm["finished_at"]
            or not isinstance(pm.get("venv_rebuild"), dict) or pm["venv_rebuild"].get("ok") is not True
            or pm.get("refusal")):
        raise Refused("finish_handoff_incomplete")
    # Validate every parent before opening the bounded private archive; no symlink redirects.
    canonical_directory(str(home / "logs"))
    directory = canonical_directory(str(home / "logs/update_receipts"))
    records = []
    for index, path in enumerate(directory.iterdir()):
        if index >= 512:
            raise Refused("receipt_inventory_unbounded")
        if path.name.startswith("update_") and path.name.endswith("_" + config["upstreamUpdateID"] + ".json"):
            records.append(path)
    if len(records) > 1:
        raise Refused("finish_receipt_custody_unknown")
    if records:
        archived = private_json(records[0])
        if (not valid_receipt(archived) or any(archived.get(key) != value for key, value in receipt.items())):
            raise Refused("finish_receipt_advanced")
    if (run / "official-result.json").exists() or (run / "official-result.json").is_symlink():
        raise Refused("finish_result_already_present")
    return context


def finish_checkpoint(config, root: Path, run: Path, phase: str):
    return {"schema": 1, "runID": config["runID"], "upstreamUpdateID": config["upstreamUpdateID"],
            "checkout": str(root), "targetCommit": config["targetCommit"], "phase": phase,
            "contextSHA256": hashlib.sha256(private_bytes(run / "official-context.json")).hexdigest()}


def initial_context(config, root: Path):
    previous = {"update_id": config["upstreamUpdateID"], "correlation_id": config["runID"],
                "pre_update": {"sha": config["sourceCommit"], "version": config["preUpdateVersion"]}}
    return {"root": str(root), "update_id": config["upstreamUpdateID"], "receipt": previous,
            "assume_yes": True, "gateway_mode": False, "desktop": False,
            "pre_update_version": config["preUpdateVersion"], "plan": config["plan"]}


def prepare_handoff(config, root: Path, run: Path):
    """Actual official preparation, plus GO's durable pre-finish effect boundary.

    Legacy -S interpreters can lack ruamel when upstream best-effort persists a running archive.
    A ready checkpoint therefore proves no finish was started even if that archive is absent.
    Both paths must write finishing BEFORE spawning any official finish effects.
    """
    from hermes_cli import update_receipt
    from hermes_cli import _update_takeover as official
    request = private_json(run / "official-context.json")
    if (not isinstance(request, dict) or request != initial_context(config, root)
            or request.get("assume_yes") is not True or request.get("gateway_mode") is not False
            or request.get("desktop") is not False):
        raise Refused("initial_handoff_mismatch")
    if any((run / name).exists() or (run / name).is_symlink()
           for name in ("official-result.json", "official-finish-state.json")):
        raise Refused("retained_context_present")
    durable_json(run / "official-finish-state.json", finish_checkpoint(config, root, run, "preparing"))
    update_receipt.begin_update_receipt(previous=request["receipt"], correlation_id=config["upstreamUpdateID"])
    try:
        python, environment = official.prepare(request)
        receipt = update_receipt._current.get()
        if receipt is None or request["update_id"] != config["upstreamUpdateID"]:
            raise Refused("finish_receipt_unavailable")
        request["receipt"] = receipt.data
        durable_json(run / "official-context.json", request)
        durable_json(run / "official-finish-state.json", finish_checkpoint(config, root, run, "ready"))
        return request, python, environment
    except Exception:
        official._record_failure(request, run / "official-result.json", 1, "GO official preparation did not finish")
        raise


FINISH_PROBE = r'''
import json, sys
from pathlib import Path
root = Path(sys.argv[1]); sys.path.insert(0, str(root))
from pm.client import venv_is_current
from hermes_cli._launchers import resolve_store_python
from pm.environments import activation_environment
if not venv_is_current(project_root=root): raise SystemExit(2)
python = resolve_store_python(root)
if python is None: raise SystemExit(2)
environment = activation_environment(root)
environment['HERMES_DISABLE_LAZY_INSTALLS'] = '1'
environment.pop('GIT_ALLOW_PROTOCOL', None)  # probe-only transport fence, not finish's explicit fetch
print(json.dumps({'python': str(python), 'environment': environment}), flush=True)
'''


def finish_environment(root: Path):
    # PM resolves registry/facts through module origin as well as explicit project_root. Probe in
    # an isolated child importing the verified LIVE tree, never the immutable asset snapshot.
    # venv_is_current uses bootstrap=False; this child cannot provision a cold PM runtime.
    from hermes_cli.update_custody import popen_post_commit
    with popen_post_commit([sys.executable, "-I", "-S", "-B", "-c", FINISH_PROBE, str(root)],
                          label="GO official finish currency probe", cwd=str(root),
                          env={**os.environ, "GIT_ALLOW_PROTOCOL": "", "GIT_NO_LAZY_FETCH": "1"},
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as child:
        raw = child.stdout.read(MAX_JSON + 1)
        if len(raw) > MAX_JSON:
            child.stdout.close()  # drain is bounded; do not wait with a full writer pipe
        code = child.wait()
    if code or len(raw) > MAX_JSON:
        raise Refused("finish_dependencies_changed")
    result = json.loads(raw, object_pairs_hook=unique_object)
    if (not isinstance(result, dict) or set(result) != {"python", "environment"}
            or not isinstance(result["python"], str) or not Path(result["python"]).is_absolute()
            or not isinstance(result["environment"], dict)
            or not all(isinstance(k, str) and isinstance(v, str) for k, v in result["environment"].items())
            or result["environment"].get("HERMES_DISABLE_LAZY_INSTALLS") != "1"
            or any(result["environment"].get(key) != os.environ.get(key)
                   for key in ("HOME", "HERMES_HOME", "HERMES_UPDATE_CORRELATION_ID"))):
        raise Refused("finish_environment_unavailable")
    return Path(result["python"]), result["environment"]


def resume_finish(config, root: Path, home: Path, run: Path):
    validate_journal(config, root)
    validate_update_plan(config)
    verify_source(root, config["targetCommit"], installation=True)
    source = canonical_directory(config["sourceRoot"])
    verify_source(source, config["targetCommit"])
    validate_target_tag(source, root, config["targetVersion"], config["targetCommit"])
    context = validate_finish_handoff(config, root, home, run)
    python, environment = finish_environment(root)
    # The probe must not permit a changed handoff. Keep the original bytes; finish only reads it.
    if validate_finish_handoff(config, root, home, run) != context:
        raise Refused("finish_handoff_changed")
    validate_journal(config, root)
    verify_source(root, config["targetCommit"], installation=True)
    validate_target_tag(source, root, config["targetVersion"], config["targetCommit"])
    from hermes_cli.update_custody import popen_post_commit
    durable_json(run / "official-finish-state.json", finish_checkpoint(config, root, run, "finishing"))
    with popen_post_commit([str(python), "-I", "-B", "-X", "utf8", str(root / "hermes_cli/update_finish.py"),
                           str(run / "official-context.json"), str(run / "official-result.json")],
                          label="GO official finish resume", cwd=str(root), env=environment,
                          stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          start_new_session=True) as child:
        emit("updating", pid=child.pid)
        code = child.wait()
    emit("finished", exitCode=code)


def update(config, root: Path, home: Path, run: Path, configuration_path: Path | None = None):
    validate_journal(config, root)
    validate_git_configuration(root, installation=True)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    if head == config["targetCommit"]:
        # Do not replace an existing receipt/PM context with a fresh minimal one after commit.
        # A recovery adapter must reconcile the retained official tail and receipt custody first.
        raise Refused("committed_resume_requires_reconciliation")
    if any((run / name).exists() or (run / name).is_symlink()
           for name in ("official-context.json", "official-result.json", "official-finish-state.json")):
        raise Refused("retained_context_present")
    if head != config["sourceCommit"]:
        raise Refused("checkout_changed")
    verify_source(root, head, installation=True)
    validate_update_plan(config)
    source = canonical_directory(config["sourceRoot"])
    verify_source(source, config["targetCommit"])
    tag_ref = validate_target_tag(source, root, config["targetVersion"], config["targetCommit"])
    from hermes_cli.update_custody import run_git, popen_post_commit
    if head != config["targetCommit"]:
        # Import immutable official plumbing before the first file moves. Fetch only the pinned
        # object from the previously verified official source snapshot, never a moving ref.
        from hermes_cli.update_cmd_commit import arm_tree_move, tree_whole_at
        from hermes_cli.venv_sync import arm_completion
        # Standard older installations may be depth-one clones. Extend only from the verified
        # local snapshot, with a bounded graph; missing ancestry fails closed before source moves.
        shallow = git(root, "rev-parse", "--is-shallow-repository").strip() == b"true"
        depth = ["--deepen=1024"] if shallow else []
        git(root, "fetch", "--update-shallow", *depth, "--no-tags", "--no-write-fetch-head",
            "--no-recurse-submodules", config["sourceRoot"], config["targetCommit"], tag_ref + ":" + tag_ref,
            snapshot_fetch=True)
        if git(root, "rev-parse", "--verify", tag_ref + "^{commit}").decode().strip() != config["targetCommit"]:
            raise Refused("target_tag_moved")
        git(root, "merge-base", "--is-ancestor", head, config["targetCommit"])
        arm_completion(root)
        arm_installation_obligation(root, home, config["targetCommit"], config["plan"]["runtimes"])
        marker = arm_tree_move(["/usr/bin/git"], root, pre=head, target=config["targetCommit"], stash=None)
        owned_marker = marker.read_bytes()
        named = git(root, "symbolic-ref", "-q", "HEAD", check=False).strip()
        move = ["merge", "--ff-only", "--no-edit", config["targetCommit"]] if named else ["checkout", "--detach", config["targetCommit"]]
        moved = run_git(["/usr/bin/git"], move, cwd=str(root), stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if moved.returncode or not tree_whole_at(["/usr/bin/git"], root, config["targetCommit"]):
            raise Refused("tree_move_incomplete")
        if marker.read_bytes() != owned_marker:
            raise Refused("marker_changed")
        marker.unlink()
    # The official historical takeover provisions PM and invokes update_finish in the selected
    # interpreter; both children inherit the upstream checkout lock. No DIY dependency installer.
    context = run / "official-context.json"
    durable_json(context, initial_context(config, root))
    with popen_post_commit([sys.executable, "-I", "-S", "-B", str(Path(__file__).resolve()),
                           "--official-handoff", str(configuration_path or run / "worker-configuration.json")],
                          label="GO official takeover", cwd=str(root),
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           start_new_session=True) as child:
        emit("updating", pid=child.pid)
        code = child.wait()
    emit("finished", exitCode=code)


def main():
    if sys.version_info < (3, 11) or os.name != "posix":
        raise Refused("unsupported_interpreter")
    # The official children also invoke Git; protect their commit/ancestry views, not just ours.
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    os.environ["GIT_GRAFT_FILE"] = os.devnull
    os.environ["GIT_NO_LAZY_FETCH"] = "1"
    handoff_child = len(sys.argv) == 3 and sys.argv[1] == "--official-handoff"
    config = private_json(Path(sys.argv[2] if handoff_child else sys.argv[1]))
    source, root, home, run = validate_configuration(config)
    if handoff_child:
        validate_journal(config, root)
        verify_source(root, config["targetCommit"], installation=True)
    sys.path.insert(0, str(root if handoff_child else source))
    from hermes_cli.update_lock import UpdateLock
    lock = UpdateLock(path=home / ".hermes-update-in-progress", install_root=root)
    if not lock.acquire():
        raise Refused("concurrent_update")
    try:
        if handoff_child:
            from hermes_cli import _update_takeover as official
            request, python, environment = prepare_handoff(config, root, run)
            durable_json(run / "official-finish-state.json", finish_checkpoint(config, root, run, "finishing"))
            try:
                return official._run_finish_child(request, run / "official-context.json",
                                                  run / "official-result.json", python, environment)
            except Exception:
                official._record_owed_finish(request, run / "official-result.json", "GO official finish could not run")
                return 0
        emit("locked")
        line = sys.stdin.buffer.readline(4097)
        if not line:
            return 0
        if len(line) > 4096:
            raise Refused("invalid_command")
        command = json.loads(line, object_pairs_hook=unique_object)
        if command == {"command": "release", "runID": config["runID"]}:
            return 0
        if command not in ({"command": "update", "runID": config["runID"]},
                           {"command": "resume-finish", "runID": config["runID"]}):
            raise Refused("invalid_command")
        if command["command"] == "update":
            update(config, root, home, run, Path(sys.argv[1]).resolve())
        else:
            resume_finish(config, root, home, run)
        # Do not free the checkout between PM completion and GO's independent boot/receipt checks.
        line = sys.stdin.buffer.readline(4097)
        if line and json.loads(line, object_pairs_hook=unique_object) != {"command": "release", "runID": config["runID"]}:
            raise Refused("invalid_command")
        return 0
    finally:
        lock.release()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (Refused, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Only our closed reason vocabulary is diagnostic; never expose arbitrary exception text.
        reason = str(error) if isinstance(error, Refused) else type(error).__name__
        emit("refused", reason=reason)
        raise SystemExit(1)
