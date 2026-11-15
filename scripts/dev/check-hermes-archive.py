#!/usr/bin/env python3
"""Exercise the installed upstream archive handlers on disposable data, never a user's store.

Use the installed Hermes Python (>=3.11); --site-packages may name its existing application
dependency directory. No installer/launcher, credentials, inference or production endpoint is used.
"""
from contextlib import contextmanager
import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace


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
        result = {"sourceCommit": commit, "checks": checks, "passed": all(c["passed"] for c in checks),
            "limitations": ["isolated handlers and dispatcher, not a real phone or production HTTP/WS handshake",
                "native grants, database handles and profile roots are test fixtures; no inference or runtime startup"]}
        print(json.dumps(result, ensure_ascii=False, indent=2), file=output)
        if not result["passed"]:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
