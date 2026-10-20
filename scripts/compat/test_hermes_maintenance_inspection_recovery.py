"""Real inspector-process death and locks; selector/runtime reports remain SYNTHETIC."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


HERE = Path(__file__).parent
driver = load("inspection_recovery_test", HERE / "hermes-maintenance-inspection-recovery.py")
fixtures = load("inspection_target_fixture", HERE / "test_hermes_maintenance_target.py")

# Test-only interpreter: actual POSIX lease/control/context/file checks, synthetic runtime report.
# No shipping CLI switch or production actor can select this implementation.
SHIM = '''
import hashlib, importlib.util, json, os, sys
from pathlib import Path
spec=importlib.util.spec_from_file_location("synthetic_inspector",sys.argv[1])
a=importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
def inspect(root,plan,run,source,digest,checkpoint=None):
    pm=a.cli.target.pm
    pm.PINNED={n:hashlib.sha256((root/'checkout'/n).read_bytes()).hexdigest() for n in pm.PINNED}
    key=hashlib.sha256(str(root/'checkout').encode()).hexdigest()[:16]
    v=root/'data/installs'/key/'environments/gen-test/venv'
    base=root/'data/tools/python-runtime'
    report={'executable':str(v/'bin/python'),'base':str(base),'version':[3,11,15],
            'packaging':'test-only','module':str(v/'lib/python3.11/site-packages/packaging/version.py'),
            'ssl':'test-only','sqlite':'test-only','hash':hashlib.sha256(b'HG199 runtime probe').hexdigest()}
    original=pm.child
    def child(root,executable,code,*args,**kwargs):
        return dict(report) if code==pm.RUNTIME else original(root,executable,code,*args,**kwargs)
    pm.child=child
    held=a.cli.driver.held(root)
    try: result=a.cli.target.verify(held,plan,run,source,digest,checkpoint=checkpoint)
    finally: held.close()
    lease=a.cli.driver.load('synthetic_writer',a.cli.driver.PROTOTYPE/'maintenance_lease.py')
    try: writer=lease.Lease([lease.Domain(root/'control',k,root/p) for k,p in (('home','data'),('checkout','checkout'))])
    except lease.Pending: result['ordinaryWriterBlocked']=True
    else:
        writer.close(); raise ValueError('synthetic admission opened')
    return result
try:
    root,fd,run,source,digest,phase=sys.argv[2:]
    a.run(Path(root),int(fd),run,source,digest,phase,inspect=inspect)
except (ValueError,TypeError,OSError,RuntimeError):
    print(json.dumps({'inspectionRefused':True}),flush=True); sys.exit(1)
'''


class InspectionRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PMTargetTest(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.case = self.fixture.case
        p = patch.object(driver.cli, "target", fixtures.target)
        p.start()
        self.addCleanup(p.stop)
        self.plan = self.fixture.plan
        self.sha = driver.cli.target.binding.digest(self.plan)
        self.run_id = self.fixture.context["run"]
        self.source = fixtures.fixtures.driver.COMMIT

    def launch(self, root, plan, run, source, digest, phase):
        manifest = driver.cli.target.binding.private_context_fd(root / "control", plan)
        try:
            return subprocess.Popen([sys.executable, "-I", "-S", "-B", "-c", SHIM,
                                     str(driver.cli.driver.PROTOTYPE / "pm_inspection_actor.py"),
                                     str(root), str(manifest), run, source, digest, phase],
                                    env=driver.cli.driver.environment(root), cwd=root,
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    pass_fds=(manifest,))
        finally:
            os.close(manifest)

    def test_sigkill_three_checkpoints_restarts_fresh_inspectors_and_keeps_intent(self):
        self.case.held.close()
        phases = []

        def start(*args):
            phases.append(args[-1])
            return self.launch(*args)
        value = driver.inspect(self.case.root, self.plan, self.run_id, self.source, self.sha, start=start)
        self.assertEqual(phases, ["validated", "none", "observed", "none", "verified", "none", "none", "none", "none"])
        self.assertEqual(value["inspectionSigkillCases"], 3)
        self.assertEqual(value["freshInspectionMatches"], 3)
        self.assertEqual(value["changedExpectationRefusals"], 3)
        self.assertTrue(value["intentBytesUnchanged"])
        self.assertTrue(value["ordinaryWriterBlocked"])
        self.assertTrue(value["activationInputsCurrent"])
        for key in ("productionCompletionVerified", "targetAuthorityVerified", "durableHandoffVerified", "updaterRecoveryVerified", "certifiesUpgrade"):
            self.assertFalse(value[key])

    def test_checkpoint_locations_count_actual_observations(self):
        checkpoints = []
        fixtures.target.verify(self.case.held, self.plan, self.run_id, self.source, self.sha,
                               checkpoint=lambda name, count: checkpoints.append((name, count, self.case.calls.call_count)))
        self.assertEqual(checkpoints, [("validated", 0, 0), ("observed", 1, 3), ("verified", 2, 6)])

    def test_old_success_does_not_replace_new_file_observation(self):
        old = fixtures.target.verify(self.case.held, self.plan, self.run_id, self.source, self.sha)
        self.assertTrue(old["preparedTargetMatched"])
        self.case.held.close()
        self.case.package.write_bytes(b"# changed after successful inspection\n")
        process = self.launch(self.case.root, self.plan, self.run_id, self.source, self.sha, "none")
        try:
            self.assertEqual(driver.actor.support.read(process.stdout.fileno()), {"inspectionRefused": True})
            self.assertEqual(process.wait(timeout=5), 1)
        finally:
            driver.close(process)
        driver.closed(self.case.root, self.fixture.slots)
        # Negative control: reusing the saved report would miss that actual dependency change.
        self.assertTrue(driver.result({"result": {**old, "ordinaryWriterBlocked": True}}, self.plan)["preparedTargetMatched"])

    def test_dependency_change_after_verified_process_death_refuses_resume(self):
        self.case.held.close()
        verified_started = False
        changed = False

        def start(root, plan, run, source, digest, phase):
            nonlocal verified_started, changed
            if phase == "verified":
                verified_started = True
            elif phase == "none" and verified_started:
                self.case.package.write_bytes(b"# changed after verified inspector SIGKILL\n")
                changed = True
            return self.launch(root, plan, run, source, digest, phase)
        with self.assertRaises(ValueError):
            driver.inspect(self.case.root, self.plan, self.run_id, self.source, self.sha, start=start)
        self.assertTrue(changed)
        driver.closed(self.case.root, self.fixture.slots)

    def test_invalid_independent_expectations_refuse_before_starting_any_child(self):
        self.case.held.close()
        for run, digest in (("00000000-0000-0000-0000-000000000000", self.sha), (self.run_id, "0" * 64)):
            with patch.object(driver, "launch") as start:
                with self.assertRaises(ValueError):
                    driver.inspect(self.case.root, self.plan, run, self.source, digest, start=start)
                start.assert_not_called()

    def test_malformed_checkpoint_stops_own_child_without_starting_a_resume(self):
        self.case.held.close()
        phases = []
        code = '''
import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('bad_checkpoint',sys.argv[1])
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
held=d.cli.driver.held(Path(sys.argv[2]))
print(json.dumps({'checkpoint':sys.argv[3],'observations':False}),flush=True)
sys.stdin.buffer.read()
'''

        def start(root, plan, run, source, digest, phase):
            phases.append(phase)
            return subprocess.Popen([sys.executable, "-I", "-S", "-B", "-c", code, str(HERE / "hermes-maintenance-inspection-recovery.py"), str(root), phase],
                                    env=driver.cli.driver.environment(root), stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        with self.assertRaises(ValueError):
            driver.inspect(self.case.root, self.plan, self.run_id, self.source, self.sha, start=start)
        self.assertEqual(phases, ["validated"])
        driver.closed(self.case.root, self.fixture.slots)

    def test_success_report_with_nonzero_exit_or_extra_output_is_refused(self):
        self.case.held.close()
        for tail in ("\nsys.exit(2)\n", "\nprint('{}',flush=True)\n"):
            def start(root, plan, run, source, digest, phase):
                with patch.dict(self.launch.__globals__, {"SHIM": SHIM + tail}):
                    return self.launch(root, plan, run, source, digest, phase)
            with self.assertRaises(ValueError):
                driver.inspect(self.case.root, self.plan, self.run_id, self.source, self.sha, start=start)
            driver.closed(self.case.root, self.fixture.slots)

    def test_saved_boolean_incomplete_or_authority_results_are_not_inspector_results(self):
        report = fixtures.target.verify(self.case.held, self.plan, self.run_id, self.source, self.sha)
        report["ordinaryWriterBlocked"] = True
        for value in (True, report, {"result": True}, {"result": {**report, "targetAuthorityVerified": True}},
                      {"result": {**report, "ordinaryWriterBlocked": 1}}):
            with self.assertRaises(ValueError):
                driver.result(value, self.plan)

    def test_actor_refuses_linked_or_writable_plan_descriptors(self):
        path = self.case.root / "control/linked-plan"
        path.write_bytes(driver.cli.target.binding.encode(self.plan))
        path.chmod(0o600)
        for flags in (os.O_RDONLY, os.O_RDWR):
            fd = os.open(path, flags)
            with self.assertRaises(ValueError):
                driver.actor.run(self.case.root, fd, self.run_id, self.source, self.sha, "none")
            with self.assertRaises(OSError):
                os.fstat(fd)  # Actor closes the rejected inherited descriptor too.

    def test_refusal_and_missing_cli_arguments_are_bilingual_and_redacted(self):
        args = ["inspection", "--fixture-root", str(self.case.root), "--plan-file", "/private/secret-plan",
                "--expected-run", self.run_id, "--expected-source", self.source, "--expected-plan-sha", self.sha]
        for argv in (args, ["inspection"]):
            output = io.StringIO()
            with patch.object(sys, "platform", "darwin"), patch.object(sys, "argv", argv), patch.object(driver.cli, "read_plan", side_effect=ValueError("TOKEN private-path")), contextlib.redirect_stdout(output):
                self.assertEqual(driver.main(), 1)
            value = json.loads(output.getvalue())
            self.assertEqual(value["code"], "HR-HERMESUPDATE-002")
            self.assertFalse(value["retryable"])
            self.assertEqual(value["summaryZh"], "暂时无法安全升级 Hermes，请查看升级条件后重新检查。")
            self.assertEqual(value["summaryEn"], "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.")
            for private in ("TOKEN", "private-path", "secret-plan", str(self.case.root)):
                self.assertNotIn(private, output.getvalue())


if __name__ == "__main__":
    unittest.main()
