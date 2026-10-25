import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("lab", Path(__file__).parents[1] / "hermes-compatibility-lab.py")
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)


class LabTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="hermes-lab-tests-")
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def fixture(self, result="success"):
        repo = self.root / "cache"
        repo.mkdir()
        def git(*args):
            return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", "-C", str(repo), *args],
                                           stderr=subprocess.DEVNULL).decode().strip()
        git("init", "-b", "main")
        git("config", "user.name", "Synthetic Lab")
        git("config", "user.email", "lab@example.invalid")
        git("config", "commit.gpgsign", "false")
        git("config", "uploadpack.allowFilter", "true")
        (repo / "source.txt").write_text("old\n")
        git("add", ".")
        git("commit", "-m", "source")
        source = git("rev-parse", "HEAD")
        (repo / "scripts").mkdir()
        # A deliberately synthetic installer. Tests below certify lab recovery,
        # NEVER the upstream installer's compatibility or the OS sandbox.
        script = b'''#!/bin/bash
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dir) repo="$2"; shift 2;;
    --commit) target="$2"; shift 2;;
    *) shift;;
  esac
done
'''
        if result != "wrong_target":
            script += b'git -C "$repo" checkout --detach "$target" || exit 12\n'
        if result == "failure":
            script += b'exit 7\n'
        (repo / "scripts/install.sh").write_bytes(script)
        (repo / "source.txt").write_text("candidate\n")
        git("add", ".")
        git("commit", "-m", "target")
        case = {"schemaVersion": 1, "sourceCommit": source, "targetCommit": git("rev-parse", "HEAD"),
                "installerSha256": lab.sha256(script), "branch": "main"}
        return repo, case

    def run_fixture(self, behavior="success", sandbox_failure=False, fault_after=None):
        cache, case = self.fixture(behavior)
        native_run = lab.run
        def fake_sandbox(argv, env, cwd, log, timeout=900):
            if argv[0] == "/usr/bin/sandbox-exec":
                if sandbox_failure:
                    return 71
                argv = argv[3:]
                if argv[:2] == ["/bin/sh", "-c"]:
                    return 0  # Unit test only: real write fence is exercised by the Mac lab.
            return native_run(argv, env, cwd, log, timeout)
        lab_root = self.root / "experiment"
        lab_root.mkdir()
        with mock.patch.object(lab.platform, "system", return_value="Darwin"), \
             mock.patch.object(lab.tempfile, "mkdtemp", return_value=str(lab_root)), \
             mock.patch.object(lab, "OFFICIAL_REPOSITORY", str(cache)), \
             mock.patch.object(lab, "run", side_effect=fake_sandbox):
            report_path, report = lab.rehearse(cache, case, fault_after=fault_after)
        self.assertEqual(json.loads(report_path.read_text()), report)
        self.assertFalse(report["compatibilityApproved"])
        self.assertIn("dependencyUpgrade", report["notExecuted"])
        return lab_root, case, report

    def test_success_restores_code_config_and_sqlite_to_original_path(self):
        root, case, report = self.run_fixture()
        self.assertEqual(report["status"], "source_stage_passed")
        self.assertEqual(report["checks"]["exactTarget"]["actual"], case["targetCommit"])
        self.assertEqual(report["checks"]["restore"]["actual"], case["sourceCommit"])
        self.assertEqual(report["checks"]["snapshot"]["digest"], report["checks"]["restore"]["digest"])
        self.assertEqual((root / "user/.hermes/hermes-agent/source.txt").read_text(), "old\n")
        with sqlite3.connect(str(root / "user/.hermes/synthetic-probe.sqlite3")) as db:
            self.assertEqual(db.execute("select count(*) from lab_probe").fetchone()[0], 1)
        with sqlite3.connect(str(root / "candidate-after-experiment/.hermes/synthetic-probe.sqlite3")) as db:
            self.assertEqual(db.execute("select count(*) from lab_probe").fetchone()[0], 2)

    def test_failed_installer_restores_even_when_code_already_moved(self):
        root, case, report = self.run_fixture("failure")
        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["checks"]["repository"]["exitCode"], 7)
        self.assertTrue(report["checks"]["restore"]["passed"])
        self.assertEqual((root / "candidate-after-experiment/.hermes/hermes-agent/source.txt").read_text(), "candidate\n")

    def test_injected_failure_is_recorded_and_restores_after_exact_target_proof(self):
        _, _, report = self.run_fixture(fault_after="repository")
        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["reason"], "injected_failure_after_repository")
        self.assertTrue(report["checks"]["exactTarget"]["passed"])
        self.assertTrue(report["checks"]["restore"]["passed"])
        self.assertEqual(report["faultAfter"], "repository")

    def test_exit_zero_at_wrong_commit_is_not_success(self):
        _, _, report = self.run_fixture("wrong_target")
        self.assertEqual(report["reason"], "exact_target_mismatch")
        self.assertTrue(report["checks"]["restore"]["passed"])
        self.assertFalse(report["checks"]["exactTarget"]["passed"])

    def test_no_sandbox_means_no_upstream_execution(self):
        root, _, report = self.run_fixture(sandbox_failure=True)
        self.assertEqual(report["reason"], "sandbox_failed")
        self.assertNotIn("repository", report["checks"])
        self.assertFalse((root / "user/.hermes/hermes-agent").exists())

    def test_tampered_installer_rejected_before_creating_lab(self):
        cache, case = self.fixture()
        case["installerSha256"] = "0" * 64
        with self.assertRaisesRegex(lab.LabRefusal, "installer_digest_mismatch"):
            lab.verify_inputs(cache, case)

    def test_snapshot_tampering_does_not_replace_candidate(self):
        user = self.root / "user"
        user.mkdir()
        (user / "config").write_text("old")
        shutil.copytree(user, self.root / "snapshot")
        digest = lab.tree_digest(user)
        (user / "config").write_text("candidate")
        (self.root / "snapshot/config").write_text("corrupt")
        with self.assertRaisesRegex(lab.LabRefusal, "snapshot_digest_mismatch"):
            lab.restore_snapshot(self.root, digest)
        self.assertEqual((user / "config").read_text(), "candidate")

    def test_snapshot_hashes_symlinks_without_reading_their_targets(self):
        target = self.root / "outside"
        target.write_text("private")
        tree = self.root / "tree"
        tree.mkdir()
        (tree / "link").symlink_to(target)
        digest = lab.tree_digest(tree)
        target.write_text("changed")
        self.assertEqual(lab.tree_digest(tree), digest)
        (tree / "link").unlink()
        (tree / "link").symlink_to(self.root / "missing")
        self.assertNotEqual(lab.tree_digest(tree), digest)

    def test_child_environment_drops_credentials_import_hooks_and_live_home(self):
        with mock.patch.dict(os.environ, {"HOME": "/live", "GITHUB_TOKEN": "never-copy",
                                         "PYTHONPATH": "/live/code", "BASH_ENV": "/live/script"}):
            env = lab.isolated_environment(self.root)
        for key in ("GITHUB_TOKEN", "PYTHONPATH", "BASH_ENV"):
            self.assertNotIn(key, env)
        self.assertEqual(env["HOME"], str(self.root / "user"))
        self.assertEqual(env["GIT_CONFIG_GLOBAL"], "/dev/null")

    def test_temporary_tool_keeps_template_less_files_and_directories_in_lab(self):
        (self.root / "tmp").mkdir()
        lab.prepare_lab_tools(self.root)
        env = lab.isolated_environment(self.root)
        for args, is_directory in (([], False), (["-d"], True)):
            created = Path(subprocess.check_output(["mktemp", *args], env=env, text=True).strip())
            self.assertEqual(created.parent, self.root / "tmp")
            self.assertEqual(created.is_dir(), is_directory)

    def test_cases_require_immutable_identities_and_audited_branch(self):
        _, good = self.fixture()
        file = self.root / "case.json"
        file.write_text(json.dumps(good))
        self.assertEqual(lab.read_case(file), good)
        for override in ({"targetCommit": "main"}, {"installerSha256": ""}, {"branch": "main; touch x"},
                         {"sourceCommit": good["targetCommit"]}, {"extra": True}, {"schemaVersion": True}):
            file.write_text(json.dumps({**good, **override}))
            with self.assertRaises(lab.LabRefusal):
                lab.read_case(file)

    def test_errors_use_registered_bilingual_summaries_and_no_exception_data(self):
        registry = (Path(__file__).parents[2] / "docs/ERROR_HANDLING.md").read_text()
        for recovery in (False, True):
            value = lab.error_payload(recovery)
            for field in ("code", "summaryZh", "summaryEn"):
                self.assertIn(value[field], registry)
            self.assertFalse(value["retryable"])
            self.assertEqual(value["action"], "review_report")
            self.assertNotIn("cause", value)
        self.assertEqual(lab.error_payload(has_report=False)["action"], "check_case_and_cache")

    def test_runtime_recipe_does_not_guess_support_for_other_commits(self):
        cache, case = self.fixture()
        with mock.patch.object(lab.platform, "system", return_value="Darwin"):
            with self.assertRaisesRegex(lab.LabRefusal, "unaudited_runtime_recipe"):
                lab.rehearse(cache, case, runtime=True)


if __name__ == "__main__":
    unittest.main()
