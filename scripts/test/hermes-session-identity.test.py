#!/usr/bin/env python3
"""Opt-in HG-216 source experiment; never opens an existing Hermes database.

Run with a trusted upstream checkout's Python environment and --source <checkout>.
This is a counterexample test against that checkout, not a production capability probe.
No default source path or user-data path is accepted. See ACCOUNT_DATA_SYNC_PIN_IDENTITY.md.
"""

import argparse
import contextlib
import importlib
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


FIXTURE_ROOT = None
SessionDB = None


class SessionIdentitySourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="case-", dir=FIXTURE_ROOT)
        self.root = Path(self.directory.name)
        self.path = self.root / "state.db"
        self.addCleanup(self.directory.cleanup)
        self.db = None
        self.addCleanup(self.close_db)
        self.db = SessionDB(db_path=self.path)

    def close_db(self):
        if self.db is not None:
            self.db.close()
            self.db = None

    def create(self, timestamp=1700000000.25):
        # Fault injection: wall clocks are not unique, monotonic incarnation issuers.
        with patch("hermes_state_sessions.time.time", return_value=timestamp):
            self.db.create_session("hg216-anonymous-session", "cli", profile_name="default")
        row = self.db.get_session("hg216-anonymous-session")
        self.assertIsNotNone(row)
        self.assertEqual(row["started_at"], timestamp)
        return row

    def generation(self):
        with contextlib.closing(sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)) as conn:
            row = conn.execute("SELECT value FROM state_meta WHERE key = 'db_file_generation'").fetchone()
            self.assertIsNotNone(row)
            self.assertTrue(row[0])
            return row[0], conn.execute("PRAGMA application_id").fetchone()[0]

    def backup_to(self, target):
        with contextlib.closing(sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)) as src:
            with contextlib.closing(sqlite3.connect(target)) as dst:
                src.backup(dst)

    def test_session_delete_recreate_can_reuse_id_and_started_at(self):
        before = self.create()
        generation = self.generation()
        self.assertTrue(self.db.delete_session(before["id"]))
        self.assertIsNone(self.db.get_session(before["id"]))
        after = self.create()
        self.assertEqual(before, after, "the public row cannot distinguish these two lifetimes")
        self.assertEqual(generation, self.generation(), "the database stamp is not per-session")

    def test_backup_restore_reuses_full_row_and_database_generation(self):
        before = self.create()
        generation = self.generation()
        snapshot = self.root / "synthetic-backup.db"
        self.backup_to(snapshot)
        self.assertTrue(self.db.delete_session(before["id"]))
        replacement = self.create(1700001000.5)
        self.assertNotEqual(before["started_at"], replacement["started_at"])
        # Close all upstream handles before restoring ONLY the test-created database.
        self.close_db()
        inode = self.path.stat().st_ino
        with contextlib.closing(sqlite3.connect(snapshot)) as src:
            with contextlib.closing(sqlite3.connect(self.path)) as dst:
                src.backup(dst)
        self.assertEqual(inode, self.path.stat().st_ino, "restore may preserve the filesystem identity")
        self.db = SessionDB(db_path=self.path)
        self.assertEqual(before, self.db.get_session(before["id"]))
        self.assertEqual(generation, self.generation(), "backup includes both persistent stamps")

    def test_copy_includes_database_generation(self):
        before = self.create()
        generation = self.generation()
        clone = self.root / "synthetic-copy.db"
        self.backup_to(clone)
        self.assertNotEqual(self.path.stat().st_ino, clone.stat().st_ino)
        with contextlib.closing(SessionDB(db_path=clone, read_only=True)) as copied:
            self.assertEqual(before, copied.get_session(before["id"]))
        with contextlib.closing(sqlite3.connect(clone.as_uri() + "?mode=ro", uri=True)) as conn:
            stamp = conn.execute("SELECT value FROM state_meta WHERE key = 'db_file_generation'").fetchone()[0]
            self.assertEqual(generation, (stamp, conn.execute("PRAGMA application_id").fetchone()[0]))

    def test_ordinary_edit_changes_file_metadata_without_new_session(self):
        before = self.create()
        generation = self.generation()
        self.close_db()
        # Give the fixture an unambiguously old mtime, without relying on a host clock tick.
        os.utime(self.path, ns=(1_000_000_000, 1_000_000_000))
        metadata = self.path.stat()
        self.db = SessionDB(db_path=self.path)
        self.assertTrue(self.db.set_session_title(before["id"], "Anonymous renamed session"))
        after = self.db.get_session(before["id"])
        self.assertEqual(before["id"], after["id"])
        self.assertEqual(before["started_at"], after["started_at"])
        self.assertEqual(generation, self.generation())
        self.close_db()
        self.assertNotEqual(metadata.st_mtime_ns, self.path.stat().st_mtime_ns)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="trusted, clean upstream source checkout")
    args = parser.parse_args()
    global FIXTURE_ROOT, SessionDB
    source = args.source.resolve(strict=True)
    if not (source / "hermes_state.py").is_file():
        parser.error("source must contain hermes_state.py")
    top = subprocess.check_output(["git", "-C", str(source), "rev-parse", "--show-toplevel"], text=True).strip()
    if Path(top).resolve() != source:
        parser.error("source must be the checkout root")
    for command in (["diff", "--quiet"], ["diff", "--cached", "--quiet"]):
        if subprocess.run(["git", "-C", str(source), *command], check=False).returncode:
            parser.error("tracked source changes prevent a reproducible commit baseline")
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    # Set isolation BEFORE importing upstream code. Do not set HOME, open a default DB,
    # contact a running Hermes, copy user data, or write bytecode into the source checkout.
    sys.dont_write_bytecode = True
    with tempfile.TemporaryDirectory(prefix="hg216-session-identity-") as temporary:
        FIXTURE_ROOT = Path(temporary).resolve()
        os.environ["HERMES_HOME"] = str(FIXTURE_ROOT)
        os.environ["HERMES_TEST_ISOLATION"] = "1"
        os.environ.pop("HERMES_STATE_DB_GUARD_BYPASS", None)
        os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
        sys.path.insert(0, str(source))
        module = importlib.import_module("hermes_state")
        if Path(module.__file__).resolve() != source / "hermes_state.py":
            parser.error("upstream import resolved outside the specified checkout")
        SessionDB = module.SessionDB
        print(f"Upstream source commit: {commit}", flush=True)
        print(f"Python {sys.version.split()[0]}, SQLite {sqlite3.sqlite_version}", flush=True)
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SessionIdentitySourceTest))
        return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
