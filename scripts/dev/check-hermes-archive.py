#!/usr/bin/env python3
"""Exercise the installed upstream archive handlers on disposable data, never a user's store.

Use the installed Hermes Python (>=3.11); --site-packages may name its existing application
dependency directory. No installer/launcher, credentials, inference or production endpoint is used.
"""
from contextlib import contextmanager
import argparse
import asyncio
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
from urllib.parse import unquote, urlsplit


@contextmanager
def disposable_home():
    root = Path(tempfile.mkdtemp(prefix="hermes-go-archive-contract-")).resolve()
    try:
        yield str(root)
    finally:
        # Absolute cleanup paths keep the write audit unambiguous (no dir_fd-relative deletes).
        for current, directories, files in os.walk(root, topdown=False):
            for name in files:
                (Path(current) / name).unlink()
            for name in directories:
                path = Path(current) / name
                if path.is_symlink(): path.unlink()
                else: path.rmdir()
        root.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hermes-root", required=True, type=Path)
    parser.add_argument("--site-packages", action="append", default=[], type=Path)
    args = parser.parse_args()
    root = args.hermes_root.resolve(strict=True)
    if not (root / "tui_gateway/methods_session.py").is_file():
        parser.error("--hermes-root must be an existing upstream checkout")
    commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    output = sys.stdout
    sys.dont_write_bytecode = True
    sys.path[:0] = [str(root), *[str(p.resolve(strict=True)) for p in args.site_packages]]
    with disposable_home() as temporary:
        home = Path(temporary).resolve()
        os.environ["HERMES_HOME"] = str(home)
        os.environ["HERMES_TEST_ISOLATION"] = "1"
        os.environ["HERMES_RUNTIME_DIR"] = str(home / "runtime")
        # Import-only path: suppress bootstrap's dependency sync. No repair main is invoked.
        sys.argv = ["hermes", "pm", "repair"]

        def inside(value):
            return isinstance(value, (str, bytes, os.PathLike)) and Path(os.fsdecode(value)).resolve().is_relative_to(home)

        def audit(event, values):
            if event in ("socket.connect", "subprocess.Popen"):
                raise OSError("archive contract probe forbids network and child processes")
            if event == "sqlite3.connect":
                database = values[0]
                if isinstance(database, str) and database.startswith("file:"):
                    uri = urlsplit(database)
                    database = unquote(uri.path) if uri.netloc in ("", "localhost") else None
                if database != ":memory:" and not inside(database):
                    raise PermissionError("archive contract probe forbids external SQLite stores")
            if event == "open":
                path, mode, flags = values
                if isinstance(path, int):
                    return
                if Path(os.fsdecode(path)).name == ".env":
                    raise OSError("archive contract probe never reads dotenv credentials")
                if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND) and not inside(path):
                    raise PermissionError("archive contract probe forbids writes outside its temporary home")
            if event in ("os.mkdir", "os.remove", "os.rmdir", "os.chmod", "os.utime") and not inside(values[0]):
                raise PermissionError("archive contract probe forbids external filesystem mutations")
            if event in ("os.rename", "os.link", "os.symlink") and not all(inside(v) for v in values[:2]):
                raise PermissionError("archive contract probe forbids external filesystem mutations")

        sys.addaudithook(audit)
        from hermes_state import SessionDB
        from hermes_state_runtime import begin_runtime_epoch
        from gateway.session_authority import LiveSession, SessionAuthority
        from gateway.session_controls import AuthorityConnection
        from hermes_cli.web_server_sessions import _mutate_session_request
        from fastapi import HTTPException
        # The test must not read the checkout's or user's .env files on module import.
        from hermes_cli import env_loader, profiles
        env_loader.load_hermes_dotenv = lambda **_kwargs: None
        import tui_gateway.server as server
        import tui_gateway.methods_session  # noqa: F401 -- registers the real handler
        import tui_gateway.ws as ws
        profiles._get_default_hermes_home = lambda: home
        profiles._get_profiles_root = lambda: home / "profiles"
        checks = []

        def check(name, condition, details=None):
            checks.append({"name": name, "passed": bool(condition), "details": details})

        try:
            # Invoke the guard directly: never actually open a real external database.
            audit("sqlite3.connect", (root / "state.db",))
        except PermissionError:
            check("SQLite guard rejects an external store", True)
        else:
            check("SQLite guard rejects an external store", False)

        class Sink:
            async def send_text(self, _line):
                pass

        async def exercise():
            with SessionDB(db_path=home / "state.db") as db:
                server._get_db = lambda: db
                authority = SessionAuthority(SimpleNamespace(_draining=False), profile_id=str(home),
                    instance_id="fixture", db=db, epoch=begin_runtime_epoch(db, instance_id="fixture"))
                request = SimpleNamespace(state=SimpleNamespace(native_http_principal={
                    "subject": "fixture-owner", "profile_id": str(home), "instance_id": "fixture"}),
                    app=SimpleNamespace(state=SimpleNamespace(session_authority=authority, auth_required=False)))
                transport = ws.WSTransport(Sink(), asyncio.get_running_loop(), auth_identity={"user_id": "fixture-owner"})
                for source in ("hermes_remote", "desktop", "tui"):
                    sid = "rest-" + source
                    db.create_session(sid, source=source)
                    try:
                        await _mutate_session_request(request, None, sid, request_id="edit-" + source,
                            expected_revision=0, operation="sidebar", payload={"archived": True})
                        status, reason = 200, None
                    except HTTPException as failure:
                        status, reason = failure.status_code, failure.detail
                    # Record the upstream semantic limitation; it may legitimately be fixed later.
                    check("REST baseline:" + source, status == 200 if source != "hermes_remote" else status in (200, 404),
                        {"status": status, "reason": reason})
                    for operation, payload in (("sidebar", {"title": "REST rename " + source}), ("delete", {})):
                        target = "rest-" + operation + "-" + source
                        db.create_session(target, source=source)
                        try:
                            await _mutate_session_request(request, None, target, request_id="edit-" + target,
                                expected_revision=0, expected_generation=0 if operation == "delete" else None, operation=operation, payload=payload)
                            status, reason = 200, None
                        except HTTPException as failure:
                            status, reason = failure.status_code, failure.detail
                        check("REST " + operation + ":" + source, status == 200 if source != "hermes_remote" else status in (200, 404),
                            {"status": status, "reason": reason})
                    sid = "rpc-" + source
                    db.create_session(sid, source=source)
                    db.append_message(sid, role="user", content="synthetic retained message")
                    for archived in (True, False):
                        req = {"id": 1, "method": "session.archive", "params": {
                            "session_id": sid, "profile": "default", "archived": archived}}
                        reply = await ws._dispatch_request(None, req, "session.archive", transport)
                        check("RPC:" + source + ":" + str(archived), reply.get("result", {}).get("archived") == archived
                            and reply["result"].get("session_key") == sid and db.get_session(sid)["archived"] == int(archived), reply)
                    check("messages retained:" + source, db.get_messages(sid)[0]["content"] == "synthetic retained message")
                sid = "rpc-hermes_remote"
                db.end_session(sid, "compression")
                db.create_session("compressed-child", source="hermes_remote", parent_session_id=sid)
                for archived in (True, False):
                    server.handle_request({"id": 1, "method": "session.archive", "params": {"session_id": sid, "archived": archived}})
                    check("compression chain:" + str(archived), all(db.get_session(s)["archived"] == int(archived)
                        for s in (sid, "compressed-child")))
                secondary = home / "profiles" / "secondary"
                secondary.mkdir(parents=True)
                with SessionDB(db_path=secondary / "state.db") as other:
                    other.create_session(sid, source="hermes_remote")
                    reply = server.handle_request({"id": 1, "method": "session.archive", "params": {
                        "session_id": sid, "profile": "secondary", "archived": True}})
                    check("same-id profile isolation", other.get_session(sid)["archived"] == 1 and db.get_session(sid)["archived"] == 0, reply)
                for target, profile, code in (("missing", "default", 4001), (sid, "nonexistent", 4064)):
                    reply = server.handle_request({"id": 1, "method": "session.archive", "params": {
                        "session_id": target, "profile": profile, "archived": True}})
                    check("refuse:" + target + ":" + profile, reply.get("error", {}).get("code") == code, reply)
                original_archive = db.set_session_archived
                def fail_after_write(target, archived):
                    original_archive(target, archived)
                    raise RuntimeError("synthetic acknowledgement failure after commit")
                db.set_session_archived = fail_after_write
                try:
                    reply = server.handle_request({"id": 1, "method": "session.archive", "params": {
                        "session_id": sid, "profile": "default", "archived": True}})
                    check("RPC storage error may follow a committed write", reply.get("error", {}).get("code") == 5007
                        and db.get_session(sid)["archived"] == 1, reply)
                finally:
                    db.set_session_archived = original_archive
                    original_archive(sid, False)
                # Exercise the curated official command; do not synthesize a live conversation.
                import shlex
                from contextlib import contextmanager
                from hermes_cli import console_engine
                @contextmanager
                def same_fixture_store(*_args, **_kwargs):
                    yield db
                console_engine._session_db = same_fixture_store
                engine = console_engine.HermesConsoleEngine()
                unusual_title = "名字 ' 和双引号 \" 标题"
                command = "sessions rename " + shlex.quote(sid) + " " + shlex.quote(unusual_title)
                before_messages = db.get_messages(sid)
                # HermesConsoleEngine runs curated commands, not SQLAlchemy queries.
                # nosemgrep: python.sqlalchemy.security.sqlalchemy-execute-raw-query.sqlalchemy-execute-raw-query
                result = engine.execute(command, confirmed=True)
                check("official curated rename accepts only quoted title", result.status == "ok"
                    and db.get_session(sid)["title"] == unusual_title and db.get_messages(sid) == before_messages,
                    {"status": result.status, "output": result.output, "command_identity_preserved": result.command == command})
                check("console command identity preserved", result.command == command)
                check("console output confirms exact target and title",
                    result.output.rstrip() == f"Session '{sid}' renamed to: {unusual_title}")
                alias_target = "audit-rename-prefix-full"
                db.create_session(alias_target, source="hermes_remote")
                alias_command = "sessions rename audit-rename-prefix " + shlex.quote("prefix fixture")
                # HermesConsoleEngine runs curated commands, not SQLAlchemy queries.
                # nosemgrep: python.sqlalchemy.security.sqlalchemy-execute-raw-query.sqlalchemy-execute-raw-query
                alias_result = engine.execute(alias_command, confirmed=True)
                check("curated rename may resolve a prefix to a different stored id", alias_result.status == "ok"
                    and alias_result.output.rstrip() == f"Session '{alias_target}' renamed to: prefix fixture")
                from hermes_cli.web_routers.sessions import get_session_mutation_snapshot
                alias_snapshot = await get_session_mutation_snapshot("audit-rename-prefix", request, None)
                check("exact snapshot rejects a prefix alias before mutation", alias_snapshot["session_id"] == "audit-rename-prefix"
                    and alias_snapshot["exists"] is False)

                # HermesConsoleEngine runs curated commands, not SQLAlchemy queries.
                # nosemgrep: python.sqlalchemy.security.sqlalchemy-execute-raw-query.sqlalchemy-execute-raw-query
                refused = engine.execute("sessions rename " + shlex.quote(sid) + " " + shlex.quote("$(cmd); delete all"), confirmed=True)
                check("console rejects shell syntax without changing title", refused.status == "error"
                    and db.get_session(sid)["title"] == unusual_title)

                for source in ("hermes_remote", "desktop", "tui"):
                    target = "delete-" + source
                    db.create_session(target, source=source)
                    db.append_message(target, role="user", content="synthetic delete")
                    reply = await ws._dispatch_request(None, {"id": 2, "method": "session.delete", "params": {
                        "session_id": target, "profile": "default"}}, "session.delete", transport)
                    check("official delete:" + source, reply.get("result", {}).get("deleted") == target
                        and db.get_session(target) is None, reply)
                server._sessions["delete-live-guard"] = {"session_key": sid}
                try:
                    reply = server.handle_request({"id": 4, "method": "session.delete", "params": {"session_id": sid, "profile": "default"}})
                    check("delete refuses a live session", reply.get("error", {}).get("code") == 4023 and db.get_session(sid) is not None, reply)
                finally:
                    server._sessions.pop("delete-live-guard", None)
                reply = server.handle_request({"id": 5, "method": "session.delete", "params": {"session_id": sid, "profile": "secondary"}})
                with SessionDB(db_path=secondary / "state.db") as other:
                    check("delete same-id profile isolation", reply.get("result", {}).get("deleted") == sid
                        and other.get_session(sid) is None and db.get_session(sid) is not None, reply)
                target_dir = home / "new-workspace"
                target_dir.mkdir()
                server.git_probe = SimpleNamespace(branch=lambda _path: None, common_repo_root=lambda _path: None)
                reply = server.handle_request({"id": 3, "method": "session.workspace.move", "params": {
                    "session_key": sid, "cwd": str(target_dir), "profile": "default"}})
                check("official stored workspace move", reply.get("result", {}).get("cwd") == str(target_dir)
                    and db.get_session(sid)["cwd"] == str(target_dir), reply)
                authority.sessions[sid] = LiveSession(None, "fixture-route")
                connection = AuthorityConnection(authority, transport, {"user_id": "fixture-owner"})
                try:
                    req = {"id": 1, "method": "session.archive", "params": {"session_id": sid, "archived": True}}
                    reply = await ws._dispatch_request(connection, req, "session.archive", transport)
                    check("authority forbids legacy fallback", reply.get("error", {}).get("code") == -32601
                        and db.get_session(sid)["archived"] == 0, reply)
                finally:
                    await connection.close()

        asyncio.run(exercise())
        # Bind the semantic result to the interpreter and actual imported code, without exposing
        # machine-specific paths. Version labels alone cannot identify a dependency environment.
        imported = []
        for name, module in sorted(sys.modules.items()):
            raw = getattr(module, "__file__", None)
            if not isinstance(raw, str):
                continue
            path = Path(raw)
            if path.suffix not in (".py", ".pyc", ".so", ".dylib") or not path.is_file():
                continue
            imported.append([name, hashlib.sha256(path.read_bytes()).hexdigest()])
        runtime_identity = {
            "interpreterSHA256": hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest(),
            "importedCodeSHA256": hashlib.sha256(json.dumps(imported, separators=(",", ":")).encode()).hexdigest(),
            "importedModuleCount": len(imported),
        }
        result = {"sourceCommit": commit, "runtimeIdentity": runtime_identity, "checks": checks, "passed": all(c["passed"] for c in checks),
            "limitations": ["isolated handlers and dispatcher, not a real phone or production HTTP/WS handshake",
                "native grants, database handles and profile roots are test fixtures; no inference or runtime startup"]}
        print(json.dumps(result, ensure_ascii=False, indent=2), file=output)
        if not result["passed"]:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
