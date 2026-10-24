"""Synthetic API/control tests; the separate fixed-source CI job exercises actual SessionDB."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("database_writer_tests", HERE / "hermes-maintenance-database-writers.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

SYNTHETIC_DB = '''
import sqlite3
from pathlib import Path
class SessionDB:
 def __init__(self,db_path):
  Path(db_path).parent.mkdir(parents=True,exist_ok=True)
  self.db=sqlite3.connect(db_path)
  self.db.executescript("create table sessions(id text,title text,parent text);create table messages(content text);")
 def create_session(self,session_id,source,parent_session_id=None):
  self.db.execute("insert into sessions values(?,?,?)",(session_id,"",parent_session_id))
 def set_session_title(self,session_id,title):
  self.db.execute("update sessions set title=? where id=?",(title,session_id))
 def append_message(self,session_id,role,content):
  self.db.execute("insert into messages values(?)",(content,))
 def close(self):
  self.db.commit();self.db.close()
'''


class DatabaseWriterTest(unittest.TestCase):
    def source(self, directory, code=SYNTHETIC_DB):
        source = Path(directory).resolve() / "synthetic-source"
        source.mkdir(mode=0o700)
        (source / "hermes_state.py").write_text(code)
        return source

    def test_synthetic_nonparticipant_writes_while_independent_cooperating_process_is_refused(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-writer-") as directory:
            source = self.source(directory)
            with patch.object(probe.worker, "verify_source") as verified:
                result = probe.inspect(source)
                self.assertEqual(verified.call_count, 2)
                verified.assert_called_with(source, probe.COMMIT, installation=True)
            self.assertTrue(result["actualSessionDBProcessWriteSucceeded"])
            self.assertTrue(result["cooperativeProcessRefusedBeforeAndAfter"])
            self.assertEqual(result["syntheticMessagesObserved"], 3)
            for key in ("globalAdmissionProven", "certifiesUpgrade", "durableIntentPublished", "officialUpdateLockTested",
                        "realHistoryUsed", "servicesStarted", "policyInstalled"):
                self.assertIs(result[key], False)

    def test_source_drift_refuses_before_fixture_enrollment_or_upstream_import(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-source-") as directory:
            marker = Path(directory) / "must-not-import"
            source = self.source(directory, "from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n")
            with patch.object(probe.worker, "verify_source", side_effect=probe.worker.Refused("changed_source")):
                with patch.object(probe.tempfile, "TemporaryDirectory", side_effect=AssertionError("must not enroll")):
                    with self.assertRaises(probe.worker.Refused):
                        probe.inspect(source)
            self.assertFalse(marker.exists())

    def test_a_refused_or_changed_database_api_is_not_promoted_to_global_proof(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-refusal-") as directory:
            source = self.source(directory, "raise RuntimeError('private test failure')\n")
            with patch.object(probe.worker, "verify_source"):
                with self.assertRaises(subprocess.CalledProcessError):
                    probe.inspect(source)

    def test_outside_write_tripwire_refuses_and_keeps_the_destination_absent(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-tripwire-") as directory:
            marker = Path(directory) / "outside-fixture"
            source = self.source(directory, "from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n" + SYNTHETIC_DB)
            with patch.object(probe.worker, "verify_source"):
                with self.assertRaises(subprocess.CalledProcessError):
                    probe.inspect(source)
            self.assertFalse(marker.exists())

    def test_missing_cooperative_refusal_stops_before_the_database_child(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-control-") as directory:
            source = self.source(directory)
            with patch.object(probe.worker, "verify_source"), patch.object(probe, "cooperating", return_value=False):
                with patch.object(probe.subprocess, "run", side_effect=AssertionError("must not start DB")):
                    with self.assertRaises(ValueError):
                        probe.inspect(source)

    def test_a_git_mutator_cannot_use_the_version_probe_exception(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-git-mutator-") as directory:
            destination = Path(directory) / "must-not-initialize"
            prefix = "import subprocess\nsubprocess.run(['git','init'," + repr(str(destination)) + "],check=True)\n"
            source = self.source(directory, prefix + SYNTHETIC_DB)
            with patch.object(probe.worker, "verify_source"):
                with self.assertRaises(subprocess.CalledProcessError):
                    probe.inspect(source)
            self.assertFalse(destination.exists())

    def test_cli_invalid_arguments_and_unverified_source_are_bilingual_and_redacted(self):
        with tempfile.TemporaryDirectory(prefix="hg199-unit-db-cli-") as directory:
            private = str(Path(directory) / "token=must-not-echo")
            for arguments in ([], ["--unknown=token=must-not-echo"], ["--candidate-root", private]):
                result = subprocess.run([sys.executable, "-I", "-S", "-B", str(HERE / "hermes-maintenance-database-writers.py"),
                    *arguments], capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 1)
                value = json.loads(result.stdout)
                self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
                self.assertIs(value["retryable"], False)
                self.assertFalse(value["globalAdmissionProven"])
                self.assertFalse(value["certifiesUpgrade"])
                self.assertTrue(value["summaryChinese"])
                self.assertTrue(value["summaryEnglish"])
                self.assertNotIn("must-not-echo", result.stdout + result.stderr)
                self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
