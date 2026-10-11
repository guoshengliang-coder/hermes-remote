import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest import mock
import uuid

SCRIPT = Path(os.environ.get("HG199_WORKER_SCRIPT", Path(__file__).resolve().parents[2] /
    "desktop/Sources/HermesGoDesktopCore/Resources/hermes-upgrade-worker.py"))
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

    def finish_handoff(self):
        run, upstream = str(uuid.uuid4()), str(uuid.uuid4())
        cfg = {"journal": str(self.record), "runID": run, "upstreamUpdateID": upstream,
               "sourceRoot": str(self.root), "sourceCommit": "a" * 40, "targetCommit": "b" * 40,
               "hermesHome": str(self.root), "targetVersion": "0.21.6", "preUpdateVersion": "0.21.3"}
        cfg["plan"] = {"install_method": "git", "updatable_in_place": True,
                       "expected_sha": cfg["sourceCommit"], "expected_version": "0.21.3",
                       "profiles": ["default"], "runtimes": []}
        self.save({"schemaVersion": 1, "runID": run, "upstreamUpdateID": upstream,
                   "checkout": str(self.root), "hermesHome": str(self.root), "sourceCommit": "a" * 40,
                   "phase": "updating", "target": {"commit": "b" * 40, "version": "0.21.6"},
                   "backup": {"runID": run, "manifestSHA256": "c" * 64}})
        receipt = {"schema": 1, "update_id": upstream, "correlation_id": run,
                   "pre_update": {"sha": "a" * 40, "version": "0.21.3"}, "post_update": {},
                   "outcome": "running", "started_at": "2026-10-11T00:00:00+00:00", "finished_at": None,
                   "pid": 123456, "steps": [], "skips": [], "gateway_restart": {}, "fleet": []}
        pm = {"schema": 1, "update_id": upstream, "kind": "sync", "outcome": "ok", "exit_code": 0,
              "finished_at": "2026-10-11T00:01:00+00:00", "venv_rebuild": {"ok": True}}
        context = {"root": str(self.root), "update_id": upstream, "receipt": receipt, "pm_receipt": pm,
                   "assume_yes": True, "gateway_mode": False, "desktop": False,
                   "pre_update_version": "0.21.3", "plan": cfg["plan"]}
        (self.root / "logs/update_receipts").mkdir(parents=True)
        archive = self.root / ("logs/update_receipts/update_20261011_000000_123456_" + upstream + ".json")
        worker.durable_json(archive, {**receipt, "writer_pid": 123456, "writer_create_time": None})
        worker.durable_json(self.root / "official-context.json", context)
        worker.durable_json(self.root / "official-finish-state.json", worker.finish_checkpoint(cfg, self.root, self.root, "ready"))
        return cfg, context, archive

    def test_finish_resume_keeps_the_exact_handoff_and_dispatches_only_official_finish(self):
        cfg, context, archive = self.finish_handoff()
        self.assertEqual(worker.validate_finish_handoff(cfg, self.root, self.root, self.root), context)
        before = (self.root / "official-context.json").read_bytes(), archive.read_bytes()
        child = mock.MagicMock(); child.__enter__.return_value = child
        child.wait.return_value = 0; child.pid = 123457
        popen = mock.Mock(return_value=child)
        custody = types.ModuleType("hermes_cli.update_custody"); custody.popen_post_commit = popen
        with mock.patch.dict("sys.modules", {"hermes_cli.update_custody": custody}), \
                mock.patch.object(worker, "verify_source"), mock.patch.object(worker, "validate_target_tag"), \
                mock.patch.object(worker, "finish_environment", return_value=(Path("/fixture/python"), {"HOME": "fixture"})), \
                mock.patch.object(worker, "emit"):
            worker.resume_finish(cfg, self.root, self.root, self.root)
        args, kwargs = popen.call_args
        self.assertEqual(args[0], ["/fixture/python", "-I", "-B", "-X", "utf8",
            str(self.root / "hermes_cli/update_finish.py"), str(self.root / "official-context.json"),
            str(self.root / "official-result.json")])
        self.assertEqual(kwargs["env"], {"HOME": "fixture"})
        self.assertEqual(((self.root / "official-context.json").read_bytes(), archive.read_bytes()), before)
        self.assertEqual(worker.private_json(self.root / "official-finish-state.json")["phase"], "finishing")

    def test_terminal_debts_or_advanced_running_receipt_are_never_erased(self):
        cfg, context, archive = self.finish_handoff()
        original = worker.private_json(archive)
        for changed in [{"outcome": "success", "finished_at": "2026-10-11T00:02:00+00:00"},
                        {"followups": [{"step": "gateway_restart", "reason": "owed"}]},
                        {"stages": [{"name": "build", "outcome": "success"}]},
                        {"steps": [{"name": "historical_takeover", "ok": True}]},
                        {"gateway_restart": {"incomplete": True}},
                        {"carried_manual_serves": [{"pid": 42}]},
                        {"writer_pid": 1, "correlation_id": str(uuid.uuid4())}]:
            worker.durable_json(archive, {**original, **changed})
            before = archive.read_bytes()
            with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
            self.assertEqual(archive.read_bytes(), before)
        self.assertEqual(worker.private_json(self.root / "official-context.json"), context)

    def test_incomplete_pm_wrong_ids_or_changed_plan_cannot_resume(self):
        cfg, context, archive = self.finish_handoff()
        for changes in [{"pm_receipt": None}, {"pm_receipt": {**context["pm_receipt"], "outcome": "failed"}},
                        {"pm_receipt": {**context["pm_receipt"], "update_id": str(uuid.uuid4())}},
                        {"pm_receipt": {**context["pm_receipt"], "exit_code": False}},
                        {"update_id": str(uuid.uuid4())}, {"root": str(self.root / "other")},
                        {"plan": {**cfg["plan"], "runtimes": [{"kind": "gateway"}]}},
                        {"restart_update": True}, {"desktop": True},
                        {"receipt": {**context["receipt"], "pre_update": {"sha": "d" * 40, "version": "0.21.3"}}}]:
            worker.durable_json(self.root / "official-context.json", {**context, **changes})
            with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        self.assertEqual(worker.private_json(archive)["outcome"], "running")

    def test_missing_ambiguous_linked_or_completed_handoffs_fail_closed(self):
        cfg, context, archive = self.finish_handoff()
        other = archive.with_name("update_other_" + cfg["upstreamUpdateID"] + ".json")
        worker.durable_json(other, context["receipt"])
        with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        other.unlink()
        archive.chmod(0o644)
        with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        archive.chmod(0o600)
        worker.durable_json(self.root / "official-result.json", {"receipt_handled": True})
        with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        (self.root / "official-result.json").unlink()
        other.symlink_to(archive)
        with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        other.unlink(); archive.unlink()
        # Official persistence is best effort in a historical -S interpreter. GO's write-ahead
        # checkpoint proves no finish was attempted; the original private handoff remains custody.
        self.assertEqual(worker.validate_finish_handoff(cfg, self.root, self.root, self.root), context)
        (self.root / "official-finish-state.json").unlink()
        with self.assertRaises(OSError): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)

    def test_preparing_finishing_foreign_or_changed_handoff_checkpoints_do_not_resume(self):
        cfg, context, archive = self.finish_handoff()
        checkpoint = worker.finish_checkpoint(cfg, self.root, self.root, "ready")
        for change in [{"phase": "preparing"}, {"phase": "finishing"}, {"runID": str(uuid.uuid4())},
                       {"upstreamUpdateID": str(uuid.uuid4())}, {"targetCommit": "c" * 40},
                       {"contextSHA256": "c" * 64}, {"schema": True}]:
            worker.durable_json(self.root / "official-finish-state.json", {**checkpoint, **change})
            with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)
        worker.durable_json(self.root / "official-finish-state.json", checkpoint)
        worker.durable_json(self.root / "official-context.json", {**context, "pm_receipt": {**context["pm_receipt"], "exit_code": 1}})
        with self.assertRaises(worker.Refused): worker.validate_finish_handoff(cfg, self.root, self.root, self.root)

    def test_official_prepare_publishes_ready_only_after_pm_context_is_durable(self):
        cfg, context, archive = self.finish_handoff()
        (self.root / "official-finish-state.json").unlink()
        worker.durable_json(self.root / "official-context.json", worker.initial_context(cfg, self.root))
        def prepare(request):
            self.assertEqual(worker.private_json(self.root / "official-finish-state.json")["phase"], "preparing")
            self.assertNotIn("pm_receipt", worker.private_json(self.root / "official-context.json"))
            request["pm_receipt"] = context["pm_receipt"]
            return Path("/fixture/python"), {"HOME": "fixture"}
        receipt = mock.Mock(); receipt._current.get.return_value.data = context["receipt"]
        official = mock.Mock(); official.prepare.side_effect = prepare
        parent = types.ModuleType("hermes_cli"); parent.update_receipt = receipt; parent._update_takeover = official
        with mock.patch.dict("sys.modules", {"hermes_cli": parent}):
            result = worker.prepare_handoff(cfg, self.root, self.root)
        self.assertEqual(result[0], context)
        self.assertEqual(worker.private_json(self.root / "official-finish-state.json"),
                         worker.finish_checkpoint(cfg, self.root, self.root, "ready"))
        self.assertEqual(worker.validate_finish_handoff(cfg, self.root, self.root, self.root), context)
        receipt.begin_update_receipt.assert_called_once_with(previous=worker.initial_context(cfg, self.root)["receipt"],
                                                            correlation_id=cfg["upstreamUpdateID"])
        with mock.patch.dict("sys.modules", {"hermes_cli": parent}):
            with self.assertRaises(worker.Refused): worker.prepare_handoff(cfg, self.root, self.root)
        official.prepare.assert_called_once()

    def test_prepare_failure_keeps_nonresumable_intent_and_original_context(self):
        cfg, context, archive = self.finish_handoff()
        (self.root / "official-finish-state.json").unlink()
        initial = worker.initial_context(cfg, self.root)
        worker.durable_json(self.root / "official-context.json", initial)
        parent = types.ModuleType("hermes_cli"); parent.update_receipt = mock.Mock(); parent._update_takeover = mock.Mock()
        parent._update_takeover.prepare.side_effect = RuntimeError("fixture preparation interrupted")
        with mock.patch.dict("sys.modules", {"hermes_cli": parent}):
            with self.assertRaises(RuntimeError): worker.prepare_handoff(cfg, self.root, self.root)
        self.assertEqual(worker.private_json(self.root / "official-finish-state.json")["phase"], "preparing")
        self.assertEqual(worker.private_json(self.root / "official-context.json"), initial)
        parent._update_takeover._record_failure.assert_called_once()

    def test_probe_time_handoff_change_is_refused_before_child(self):
        cfg, context, archive = self.finish_handoff()
        def probe(_):
            worker.durable_json(archive, {**context["receipt"], "followups": [{"step": "maintenance"}]})
            return Path("/fixture/python"), {}
        with mock.patch.object(worker, "verify_source"), mock.patch.object(worker, "validate_target_tag"), \
                mock.patch.object(worker, "finish_environment", side_effect=probe):
            with self.assertRaises(worker.Refused): worker.resume_finish(cfg, self.root, self.root, self.root)
        self.assertFalse((self.root / "official-result.json").exists())

    def test_live_checkout_python_overlays_still_fail_with_pm_and_old_venv(self):
        commit = self.repository()
        (self.root / ".git/info/exclude").write_text("venv/\n.hermes/\nnode_modules/\nui-tui/\nhermes_cli/injected.py\n")
        for directory in ("venv", ".hermes"):
            (self.root / directory).mkdir(); (self.root / directory / "dependency.py").write_text("VALUE = 1\n")
        for directory in ("node_modules", "ui-tui/node_modules"):
            (self.root / directory).mkdir(parents=True); (self.root / directory / "dependency.py").write_text("VALUE = 1\n")
        worker.verify_source(self.root, commit, installation=True)
        (self.root / "venv/__init__.py").write_text("VALUE = 3\n")
        with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit, installation=True)
        (self.root / "venv/__init__.py").unlink()
        (self.root / "hermes_cli/injected.py").write_text("VALUE = 3\n")
        with self.assertRaises(worker.Refused): worker.verify_source(self.root, commit, installation=True)

    def test_unbounded_archive_inventory_is_not_silently_treated_as_missing(self):
        cfg, context, archive = self.finish_handoff()
        for index in range(513): (archive.parent / ("fixture-" + str(index))).touch()
        with self.assertRaisesRegex(worker.Refused, "receipt_inventory_unbounded"):
            worker.validate_finish_handoff(cfg, self.root, self.root, self.root)

    def test_invalid_private_currency_response_never_selects_an_interpreter(self):
        custody = types.ModuleType("hermes_cli.update_custody")
        child = mock.MagicMock(); child.__enter__.return_value = child; child.wait.return_value = 0
        custody.popen_post_commit = mock.Mock(return_value=child)
        for raw in [b'{}', b'{"python":"relative","environment":{}}', b'x' * (worker.MAX_JSON + 1)]:
            child.stdout.read.return_value = raw
            with mock.patch.dict("sys.modules", {"hermes_cli.update_custody": custody}):
                with self.assertRaises(worker.Refused): worker.finish_environment(self.root)
        child.stdout.close.assert_called_once()

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
