import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SCRIPT = Path(__file__).resolve().parents[2] / "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py"
spec = importlib.util.spec_from_file_location("upgrade_worker", SCRIPT)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="hg199-worker-boundary-")
        self.root = Path(self.temporary.name).resolve()
        self.record = self.root / "record.json"

    def tearDown(self):
        self.temporary.cleanup()

    def save(self, value):
        self.record.write_text(json.dumps(value))
        self.record.chmod(0o600)

    def test_private_record_roundtrip_and_atomic_permissions(self):
        worker.durable_json(self.record, {"run": "retained"})
        self.assertEqual(worker.private_json(self.record), {"run": "retained"})
        self.assertEqual(self.record.stat().st_mode & 0o777, 0o600)

    def test_record_links_and_public_permissions_fail_closed(self):
        self.save({"run": "retained"})
        link = self.root / "link.json"
        link.symlink_to(self.record)
        with self.assertRaises(OSError): worker.private_json(link)
        link.unlink(); os.link(self.record, link)
        with self.assertRaises(worker.Refused): worker.private_json(self.record)
        link.unlink(); self.record.chmod(0o644)
        with self.assertRaises(worker.Refused): worker.private_json(self.record)

    def test_duplicate_fields_are_not_ambiguous_between_swift_and_python(self):
        self.record.write_text('{"phase":"quiescing","phase":"updating"}')
        self.record.chmod(0o600)
        with self.assertRaises(worker.Refused): worker.private_json(self.record)

    def test_journal_binds_both_ids_target_backup_and_commit_phase(self):
        run, upstream = str(uuid.uuid4()), str(uuid.uuid4())
        cfg = {"journal": str(self.record), "runID": run, "upstreamUpdateID": upstream,
               "hermesHome": str(self.root), "sourceCommit": "a" * 40, "targetCommit": "b" * 40, "targetVersion": "0.21.6"}
        journal = {"schemaVersion": 1, "runID": run, "upstreamUpdateID": upstream,
                   "checkout": str(self.root), "hermesHome": str(self.root), "sourceCommit": "a" * 40,
                   "phase": "updating", "target": {"commit": "b" * 40, "version": "0.21.6"},
                   "backup": {"runID": run, "manifestSHA256": "c" * 64}}
        self.save(journal); worker.validate_journal(cfg, self.root)
        for key, value in [("phase", "backingUp"), ("runID", str(uuid.uuid4())),
                           ("upstreamUpdateID", str(uuid.uuid4())), ("sourceCommit", "d" * 40),
                           ("target", {"commit": "d" * 40}), ("backup", {"runID": run, "manifestSHA256": "z" * 64})]:
            self.save({**journal, key: value})
            with self.assertRaises(worker.Refused, msg=key): worker.validate_journal(cfg, self.root)

    def repository(self):
        for directory in ["hermes_cli", "pm"]: (self.root / directory).mkdir()
        (self.root / "hermes_cli/code.py").write_text("VALUE = 1\n")
        (self.root / "pm/code.py").write_text("VALUE = 2\n")
        self.git("init", "-q"); self.git("add", "."); self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *arguments):
        return subprocess.check_output(["/usr/bin/git", "-c", "core.hooksPath=/dev/null",
            "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-C", str(self.root), *arguments],
            env={"PATH": "/usr/bin:/bin", "HOME": str(self.root), "GIT_CONFIG_GLOBAL": "/dev/null",
                 "GIT_CONFIG_NOSYSTEM": "1"}, stderr=subprocess.DEVNULL)

    def test_pinned_source_refuses_dirty_and_hidden_index_edits(self):
        commit = self.repository()
        worker.verify_source(self.root, commit)
        self.git("update-index", "--assume-unchanged", "hermes_cli/code.py")
        (self.root / "hermes_cli/code.py").write_text("VALUE = 3\n")
        with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit)

    def test_ignored_python_cannot_shadow_the_verified_graph(self):
        commit = self.repository()
        (self.root / ".git/info/exclude").write_text("injected.py\n")
        (self.root / "pm/injected.py").write_text("VALUE = 3\n")
        with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit)

    def test_ignored_helper_outside_cli_and_pm_cannot_enter_import_graph(self):
        commit = self.repository()
        (self.root / ".git/info/exclude").write_text("utils/\n")
        (self.root / "utils").mkdir()
        (self.root / "utils/injected.py").write_text("VALUE = 3\n")
        with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit)

    def test_local_filter_or_url_rewrite_is_refused_before_execution(self):
        commit = self.repository()
        for key, value in [("filter.fixture.clean", "touch should-not-run"),
                           ("url.ext::unexpected.insteadOf", "/")]:
            self.git("config", key, value)
            with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit)
            self.assertFalse((self.root / "should-not-run").exists())
            self.git("config", "--unset", key)
        worker.verify_source(self.root, commit)

    def test_committed_source_never_replaces_retained_official_context(self):
        commit = self.repository()
        run, upstream = str(uuid.uuid4()), str(uuid.uuid4())
        cfg = {"journal": str(self.record), "runID": run, "upstreamUpdateID": upstream,
               "hermesHome": str(self.root), "sourceCommit": "a" * 40, "targetCommit": commit, "targetVersion": "0.21.6"}
        self.save({"schemaVersion": 1, "runID": run, "upstreamUpdateID": upstream,
                   "checkout": str(self.root), "hermesHome": str(self.root), "sourceCommit": "a" * 40,
                   "phase": "updating", "target": {"commit": commit, "version": "0.21.6"},
                   "backup": {"runID": run, "manifestSHA256": "c" * 64}})
        context = self.root / "official-context.json"
        worker.durable_json(context, {"pm_receipt": "retain", "followups": ["owed"]})
        with self.assertRaisesRegex(worker.Refused, "committed_resume_requires_reconciliation"):
            worker.update(cfg, self.root, self.root, self.root)
        self.assertEqual(worker.private_json(context), {"pm_receipt": "retain", "followups": ["owed"]})

    def test_missing_or_stale_plan_never_becomes_an_empty_safe_fleet(self):
        cfg = {"sourceCommit": "a" * 40, "preUpdateVersion": "0.21.3"}
        for plan in [{}, {"install_method": "git", "updatable_in_place": True,
                         "expected_sha": "b" * 40, "expected_version": "0.21.3",
                         "profiles": ["default"], "runtimes": []}]:
            with self.assertRaises(worker.Refused): worker.validate_update_plan({**cfg, "plan": plan})
        good = {"install_method": "git", "updatable_in_place": True,
                "expected_sha": "a" * 40, "expected_version": "0.21.3",
                "profiles": ["default"], "runtimes": []}
        worker.validate_update_plan({**cfg, "plan": good})
        with self.assertRaises(worker.Refused): worker.validate_update_plan({**cfg, "plan": {**good, "runtimes": [{}]}})

    def test_release_tag_must_exist_and_peel_to_pinned_commit(self):
        commit = self.repository()
        with self.assertRaisesRegex(worker.Refused, "target_tag_moved"):
            worker.validate_target_tag(self.root, self.root, "0.21.6", commit)
        self.git("tag", "-a", "v0.21.6", "-m", "fixture release")
        self.assertEqual(worker.validate_target_tag(self.root, self.root, "0.21.6", commit), "refs/tags/v0.21.6")
        with self.assertRaisesRegex(worker.Refused, "target_tag_moved"):
            worker.validate_target_tag(self.root, self.root, "0.21.6", "b" * 40)

    def test_conflicting_owner_tag_is_retained_before_any_fetch(self):
        commit = self.repository()
        self.git("tag", "v0.21.6")
        other = self.root / "owner"
        subprocess.check_call(["/usr/bin/git", "clone", "-q", str(self.root), str(other)],
                              env={"PATH": "/usr/bin:/bin", "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"})
        self.git("commit", "--allow-empty", "-qm", "new target")
        target = self.git("rev-parse", "HEAD").decode().strip()
        self.git("tag", "-f", "v0.21.6")
        with self.assertRaisesRegex(worker.Refused, "target_tag_conflict"):
            worker.validate_target_tag(self.root, other, "0.21.6", target)
        self.assertEqual(worker.git(other, "rev-parse", "v0.21.6^{commit}").decode().strip(), commit)


if __name__ == "__main__":
    unittest.main()
