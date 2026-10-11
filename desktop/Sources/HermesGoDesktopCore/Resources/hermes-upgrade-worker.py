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


def private_json(path: Path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_nlink != 1 or info.st_mode & 0o077 or info.st_size > MAX_JSON):
            raise Refused("unsafe_record")
        raw = os.read(fd, MAX_JSON + 1)
        if len(raw) > MAX_JSON:
            raise Refused("oversize_record")
        return json.loads(raw, object_pairs_hook=unique_object)
    finally:
        os.close(fd)


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


def git(root: Path, *arguments, check=True):
    # No hooks, helpers, filters via global/system config, replace objects, fsmonitor or background gc.
    command = ["/usr/bin/git", "--no-replace-objects", "--no-optional-locks", "-c", "core.fsmonitor=false",
               "-c", "core.hooksPath=/dev/null", "-c", "credential.helper=", "-c", "gc.auto=0",
               "-c", "maintenance.auto=false", "-C", str(root), *arguments]
    return subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, timeout=120, check=check).stdout


def validate_git_configuration(root: Path):
    # Read without expanding includes. Reject executable filters and URL rewriting BEFORE status,
    # fetch or checkout can invoke them; disabling hooks alone does not close these paths.
    for entry in git(root, "config", "--local", "--no-includes", "--null", "--list").split(b"\0"):
        key = entry.split(b"\n", 1)[0].lower()
        if (key.startswith((b"filter.", b"include.", b"includeif.", b"url.", b"protocol.", b"alias.", b"submodule."))
                or key in (b"core.attributesfile", b"core.sshcommand", b"core.gitproxy",
                           b"extensions.partialclone", b"extensions.worktreeconfig")):
            raise Refused("unsupported_git_configuration")
        if key.startswith(b"remote.") and key.endswith((b".promisor", b".partialclonefilter")):
            raise Refused("unsupported_git_configuration")


def verify_source(root: Path, commit: str):
    validate_git_configuration(root)
    if git(root, "rev-parse", "HEAD").decode().strip() != commit:
        raise Refused("source_moved")
    if git(root, "status", "--porcelain=v1", "--untracked-files=normal"):
        raise Refused("dirty_source")
    if any(row and row[:1] != b"H" for row in git(root, "ls-files", "-v", "-z").split(b"\0")):
        raise Refused("hidden_index_edits")
    # Git status can hide assume-unchanged/skip-worktree edits. Check Python blobs directly before
    # importing the official graph, including ignored/untracked modules that could shadow it.
    expected = {}
    for row in git(root, "ls-tree", "-r", "-z", commit).split(b"\0"):
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
    actual = {str(p.relative_to(root)) for p in root.rglob("*.py")}
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


def update(config, root: Path, home: Path, run: Path):
    validate_journal(config, root)
    validate_git_configuration(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    if head == config["targetCommit"]:
        # Do not replace an existing receipt/PM context with a fresh minimal one after commit.
        # A recovery adapter must reconcile the retained official tail and receipt custody first.
        raise Refused("committed_resume_requires_reconciliation")
    if head not in (config["sourceCommit"], config["targetCommit"]) or git(root, "status", "--porcelain=v1", "--untracked-files=normal"):
        raise Refused("checkout_changed")
    if any(row and row[:1] != b"H" for row in git(root, "ls-files", "-v", "-z").split(b"\0")):
        raise Refused("hidden_index_edits")
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
            "--no-recurse-submodules", config["sourceRoot"], config["targetCommit"], tag_ref + ":" + tag_ref)
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
    result = run / "official-result.json"
    previous = {"update_id": config["upstreamUpdateID"], "correlation_id": config["runID"],
                "pre_update": {"sha": config["sourceCommit"], "version": config["preUpdateVersion"]}}
    durable_json(context, {"root": str(root), "update_id": config["upstreamUpdateID"], "receipt": previous,
                          "assume_yes": True, "gateway_mode": False, "desktop": False,
                          "pre_update_version": config["preUpdateVersion"], "plan": config["plan"]})
    with popen_post_commit([sys.executable, "-I", "-S", "-B", str(root / "hermes_cli/_update_takeover.py"),
                           str(context), str(result)], label="GO official takeover", cwd=str(root),
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
    config = private_json(Path(sys.argv[1]))
    source, root, home, run = validate_configuration(config)
    sys.path.insert(0, str(source))
    from hermes_cli.update_lock import UpdateLock
    lock = UpdateLock(path=home / ".hermes-update-in-progress", install_root=root)
    if not lock.acquire():
        raise Refused("concurrent_update")
    try:
        emit("locked")
        line = sys.stdin.buffer.readline(4097)
        if not line:
            return 0
        if len(line) > 4096:
            raise Refused("invalid_command")
        command = json.loads(line, object_pairs_hook=unique_object)
        if command == {"command": "release", "runID": config["runID"]}:
            return 0
        if command != {"command": "update", "runID": config["runID"]}:
            raise Refused("invalid_command")
        update(config, root, home, run)
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
