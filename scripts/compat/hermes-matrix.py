#!/usr/bin/env python3
"""Offline GO/upstream contract check. Imports happen only in disposable child homes.
Pass source snapshots, never the owner's runtime; this script does not run Hermes services.
"""
import argparse
import ast
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]


def tree_contract(root):
    classes, methods, routes = {}, {}, set()
    for file in sorted((root / "tui_gateway/contracts").glob("*.py")):
        tree = ast.parse(file.read_text(), filename=str(file))
        for node in tree.body:
            if isinstance(node, ast.ClassDef):
                fields = {}
                for field in node.body:
                    if isinstance(field, ast.AnnAssign) and isinstance(field.target, ast.Name):
                        if field.target.id != "model_config":
                            required = field.value is None
                            if isinstance(field.value, ast.Call) and isinstance(field.value.func, ast.Name) and field.value.func.id == "Field":
                                default = next((kw.value for kw in field.value.keywords if kw.arg == "default"), None)
                                required = (default is None and not any(kw.arg == "default_factory" for kw in field.value.keywords))
                                if field.value.args:
                                    required = isinstance(field.value.args[0], ast.Constant) and field.value.args[0].value is Ellipsis
                            fields[field.target.id] = required
                classes[node.name] = ([base.id for base in node.bases if isinstance(base, ast.Name)], fields)
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "method" and node.args:
                if isinstance(node.args[0], ast.Constant):
                    params = next((kw.value.id for kw in node.keywords if kw.arg == "params" and isinstance(kw.value, ast.Name)), None)
                    methods[node.args[0].value] = params
    def fields(model, visiting=None):
        visiting = set() if visiting is None else visiting
        if model in visiting:
            raise ValueError("contract inheritance cycle")
        visiting = visiting | {model}
        bases, own = classes.get(model, ([], {}))
        inherited = {}
        for base in bases:
            inherited.update(fields(base, visiting))
        return inherited | own
    # Only the dashboard route modules: never import the server (which can start writers).
    for file in sorted(list((root / "hermes_cli").glob("web_server*.py")) + list((root / "hermes_cli/web_routers").glob("*.py"))):
        tree = ast.parse(file.read_text(), filename=str(file))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.args:
                method = node.func.attr.upper()
                if method in {"GET", "POST", "PATCH", "PUT", "DELETE"} and isinstance(node.args[0], ast.Constant):
                    path = node.args[0].value
                    if isinstance(path, str) and path.startswith("/api/"):
                        routes.add((method, re.sub(r"\{[^}]+\}", "{id}", path)))
    return {name: fields(model) for name, model in methods.items()}, routes


def source_check(root):
    methods, routes = tree_contract(root)
    mirror = json.loads((REPO / "docs/hermes-rpc-params.json").read_text())["methods"]
    failures = []
    for name, record in mirror.items():
        if name not in methods:
            failures.append("missing RPC " + name)
        else:
            missing = set(record["keys"]) - methods[name].keys()
            # Underscore/in-process alias fields in the mirror use their model names.
            if missing:
                failures.append(f"RPC {name} removed keys {sorted(missing)}")
            extra_required = {key for key, required in methods[name].items() if required} - set(record["keys"])
            if extra_required:
                failures.append(f"RPC {name} added required keys {sorted(extra_required)}")
    consumed = re.findall(r'(?:required|optional)\("(GET|POST|PATCH|PUT|DELETE)", "([^"]+)"',
                          (REPO / "connector/src/hermes-contract.ts").read_text())
    for method, path in consumed:
        if (method, path) not in routes:
            failures.append(f"missing REST {method} {path}")
    for file, name, expected in [
        ("tools/todo_tool.py", "TODO_INJECTION_HEADER", "[Your active task list was preserved across context compression]"),
        ("agent/conversation_compression.py", "_PRUNED_SKILL_RELOAD_NOTICE_HEADER", "[Skills pruned during compression — reload before acting on these tasks]"),
    ]:
        tree = ast.parse((root / file).read_text())
        found = next((node.value.value for node in tree.body if isinstance(node, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == name for t in node.targets)
                      and isinstance(node.value, ast.Constant)), None)
        if found != expected:
            failures.append("display header changed " + name)
    return {"rpcMethods": len(mirror), "restRoutes": len(consumed), "failures": failures}


CHILD = r'''
import json, os, sys
from pathlib import Path
root, home, mode = map(str, sys.argv[1:])
os.environ["HOME"] = home
os.environ["HERMES_HOME"] = str(Path(home) / "data")
os.environ["XDG_CACHE_HOME"] = str(Path(home) / "cache")
# Parent removes inherited Hermes/GO/auth configuration; these fixtures never touch a real home.
sys.path.insert(0, root)
from hermes_state import SessionDB
db = SessionDB(db_path=Path(home) / "data/state.db")
if mode == "create":
    db.create_session("upgrade-parent", "cli")
    db.set_session_title("upgrade-parent", "Retained conversation")
    db.create_session("upgrade-child", "cli", parent_session_id="upgrade-parent")
    db.append_message("upgrade-parent", "user", "before upgrade")
    db.append_message("upgrade-parent", "assistant", "retained answer")
    db.append_message("upgrade-child", "user", "child prompt")
else:
    rows = db.get_messages("upgrade-parent")
    assert any(row["content"] == "before upgrade" for row in rows)
    assert any(row["content"] == "retained answer" for row in rows)
    db.append_message("upgrade-parent", "user", mode)
    rows = db.get_messages("upgrade-parent")
    assert any(row["content"] == mode for row in rows)
db.close()
print(json.dumps({"stage": mode, "ok": True}))
'''


def db_roundtrip(old, candidate):
    with tempfile.TemporaryDirectory(prefix="go-hermes-matrix-") as directory:
        home = Path(directory)
        env = {"PATH": os.defpath, "LANG": "en_US.UTF-8"}
        env.update(HOME=str(home), HERMES_HOME=str(home / "data"), XDG_CACHE_HOME=str(home / "cache"))
        def child(root, mode):
            result = subprocess.run([sys.executable, "-I", "-B", "-c", CHILD, str(root), str(home), mode],
                                    env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120)
            if result.returncode:
                # Fixtures have no credentials; report exception type, never arbitrary module output.
                raise RuntimeError(f"isolated DB stage {mode} exited {result.returncode}")
        child(old, "create")
        database = home / "data/state.db"
        backup = home / "before.db"
        with sqlite3.connect(database) as live, sqlite3.connect(backup) as saved:
            live.backup(saved)
            assert saved.execute("pragma quick_check").fetchone()[0] == "ok"
            original = saved.execute("select count(*) from messages").fetchone()[0]
        child(candidate, "after_upgrade")
        child(old, "old_reopen")
        with sqlite3.connect(database) as db:
            assert db.execute("pragma quick_check").fetchone()[0] == "ok"
            assert db.execute("select count(*) from messages").fetchone()[0] == original + 2
        # Recovery rehearsal uses a separate destination. It never overwrites new writes.
        with sqlite3.connect(backup) as saved, sqlite3.connect(home / "recovered.db") as restored:
            saved.backup(restored)
            assert restored.execute("pragma quick_check").fetchone()[0] == "ok"
            assert restored.execute("select count(*) from messages").fetchone()[0] == original
        return {"sampleHistoryRoundtrip": True, "backupRecoverySeparateDestination": True,
                "limitations": ["synthetic database only", "not an official updater or service recovery rehearsal"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--old-root", type=Path, required=True)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    roots = [args.old_root.resolve(), args.candidate_root.resolve()]
    protected = Path.home() / ".hermes"
    for root in roots:
        if root == protected or protected in root.parents:
            parser.error("use downloaded source snapshots outside the live Hermes home")
    result = {"schema": 1, "current": source_check(roots[0]), "candidate": source_check(roots[1])}
    if not args.source_only:
        result["database"] = db_roundtrip(*roots)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return int(bool(result["current"]["failures"] or result["candidate"]["failures"]))


if __name__ == "__main__":
    raise SystemExit(main())
