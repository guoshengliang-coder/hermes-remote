"""Synthetic source/wire tests; a real combined process chain requires its separate Mac probe."""
import copy
import json
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixtures = load("chain_source_pm_tests", HERE / "test_hermes_maintenance_source_pm.py")
chain = load("chain_custody_tests", HERE / "upstream-maintenance-prototype/pm_chain_custody.py")
joint = chain.joint


class PMChainStandaloneTest(unittest.TestCase):
    def test_cli_opt_in_and_invalid_source_errors_are_structured_and_do_not_echo_input(self):
        script = HERE / "hermes-maintenance-pm-chain.py"
        with tempfile.TemporaryDirectory(prefix="hg199-chain-cli-errors-") as directory:
            bad = str(Path(directory) / "token=not-display")
            Path(bad).mkdir(mode=0o700)
            env = {"PATH": os.defpath, "HOME": directory, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
            base = ["/usr/bin/git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-C", bad]
            subprocess.run([*base, "init", "--quiet"], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run([*base, "-c", "user.name=HG199 fixture", "-c", "user.email=fixture@example.invalid",
                            "commit", "--quiet", "--allow-empty", "-m", "synthetic wrong commit"],
                           env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cases = [[], ["--old-root", bad, "--candidate-root", bad, "--mode", "worker-only"],
                     ["--old-root", bad, "--candidate-root", bad, "--mode", "token=not-display"],
                     ["--provision", "--old-root", bad, "--candidate-root", bad, "--mode", "worker-only"]]
            for args in cases:
                process = subprocess.run([sys.executable, "-I", "-S", "-B", str(script), *args], capture_output=True, text=True)
                self.assertEqual(process.returncode, 1)
                result = json.loads(process.stdout)
                self.assertEqual(result["code"], "HR-HERMESUPDATE-002")
                self.assertFalse(result["retryable"])
                self.assertTrue(result["summaryChinese"])
                self.assertTrue(result["summaryEnglish"])
                self.assertNotIn("not-display", process.stdout + process.stderr)

    def test_local_backend_supports_the_actual_pm_editable_build_entrypoint(self):
        actor = load("chain_actor_backend_fixture", HERE / "upstream-maintenance-prototype/pm_chain_actor.py")
        namespace = {}
        source = actor.BACKEND.replace("__ROOT__", repr("/tmp/hg199-not-executed-backend"))
        source = source.replace("__IDENTITIES__", "[]")
        exec(compile(source, "<local-backend-definitions>", "exec"), namespace)
        self.assertIs(namespace["build_editable"], namespace["build_wheel"])
        self.assertEqual(namespace["get_requires_for_build_editable"](), [])


class PMChainCustodyTest(unittest.TestCase):
    def setUp(self):
        self.case = fixtures.JointSourcePMTest()
        self.case.setUp()
        self.addCleanup(self.case.doCleanups)
        self.case.prepare_move()
        self.root, self.primary, self.expected = self.case.root, self.case.plan, self.case.args
        marker = self.root / ".hg199-isolated-pm-chain"
        marker.write_bytes(chain.MARKER)
        marker.chmod(0o600)
        project = self.root / "local-build-project"
        project.mkdir(mode=0o700)
        (project / "pyproject.toml").write_text("# synthetic fixture manifest\n")
        (project / "backend.py").write_text("# synthetic fixture backend\n")
        tools = self.root / "data/tools"
        tools.mkdir(parents=True)
        uv, python = tools / "uv", tools / "python"
        for file in (uv, python):
            file.write_bytes(b"synthetic-not-an-executable-runtime\n")
            file.chmod(0o700)
        self.environment = {"HOME": str(self.root / "user"), "HERMES_HOME": str(self.root / "data")}
        self.packages = [{"name": "uv", "definition": "synthetic"}]
        self.plan = chain.make_plan(self.case.held, self.primary, self.expected, self.case.official,
            uv=uv, python=python, worker=python, environment=self.environment,
            package_digest=joint.binding.digest(self.packages))
        chain.begin(self.case.held, self.primary, self.expected, self.case.official, self.plan)
        self.history = joint.source.slots(self.case.held)

    def require(self, plan=None):
        return chain.require(self.case.held, self.primary, self.expected, self.case.official,
                             self.plan if plan is None else plan)

    def request(self):
        return {"id": "a" * 32, "cancel": False, "operation": "build_environment", "arguments": copy.deepcopy(self.plan["arguments"]),
                "update_id": None, "callbacks": [], "packages": copy.deepcopy(self.packages),
                "context": {"repo": str(self.root / "checkout"), "lockfile": str(self.root / "checkout/pm/lock.json")}}

    def test_build_audit_is_not_a_successful_sync_or_permission_to_reopen(self):
        self.assertEqual(self.require(), self.root)
        self.assertIn(b'"kind":"pm-build-audit-started"', self.history[0])
        self.assertNotIn(b'"kind":"pm-sync-started"', self.history[0])
        with patch.object(joint.pm, "_observe_root", side_effect=AssertionError("unknown build is not PM preparation")) as observer:
            with self.assertRaises(ValueError):
                joint.verify(self.case.held, self.primary, *self.expected, official_fd=self.case.official,
                             generation_sha="0" * 64, observe_prefix=())
            observer.assert_not_called()
        self.assertEqual(joint.source.slots(self.case.held), self.history)
        self.case.held.close()
        self.case.close_official()
        self.case.fixture.blocked()  # Durable intent, after all test-held descriptions are closed.

    def test_changed_plan_scope_arguments_or_project_bytes_refuse_without_rewriting_history(self):
        variants = []
        for key, value in [("operation", "sync_venv"), ("jointPlanSHA", "f" * 64), ("schema", True), ("futureAuthority", True)]:
            plan = copy.deepcopy(self.plan)
            plan[key] = value
            variants.append(plan)
        for key, value in [("out", "/tmp/unselected"), ("offline", 1), ("explicit", False)]:
            plan = copy.deepcopy(self.plan)
            plan["arguments"][key] = value
            variants.append(plan)
        for plan in variants:
            with self.assertRaises(ValueError):
                self.require(plan)
            self.assertEqual(joint.source.slots(self.case.held), self.history)
        (self.root / "local-build-project/backend.py").write_text("# changed after intent\n")
        with self.assertRaises(ValueError):
            self.require()
        self.assertEqual(joint.source.slots(self.case.held), self.history)

    def test_actual_wire_operation_context_packages_and_arguments_are_bound(self):
        chain.request_matches(self.request(), self.root, self.plan)
        for field, value in [("operation", "sync_venv"), ("cancel", True), ("cancel", 0), ("packages", []), ("update_id", "other-receipt"),
                             ("callbacks", ["unselected"]), ("context", {"repo": "/tmp"}), ("future", True)]:
            request = self.request()
            request[field] = value
            with self.assertRaises(ValueError):
                chain.request_matches(request, self.root, self.plan)
        request = self.request()
        request["arguments"]["offline"] = 1
        with self.assertRaises(ValueError):
            chain.request_matches(request, self.root, self.plan)

    def test_wire_decoder_rejects_duplicate_keys_unknown_controls_and_oversize(self):
        request = self.request()
        data = json.dumps(request)
        self.assertEqual(chain.packet(data, self.root, self.plan), request)
        duplicate = data[:-1] + ',"operation":"build_environment"}'
        for invalid in [duplicate, "NaN", "[1,2]", " " * 65_537]:
            with self.assertRaises(ValueError):
                chain.packet(invalid, self.root, self.plan)
        cancellation = {"id": request["id"], "type": "cancel", "cancel": True}
        self.assertEqual(chain.packet(json.dumps(cancellation), self.root, self.plan, request["id"]), cancellation)
        for changed in [{**cancellation, "id": "b" * 32}, {**cancellation, "cancel": 1},
                        {**cancellation, "type": "callback"}, {**cancellation, "futureAuthority": True}]:
            with self.assertRaises(ValueError):
                chain.packet(json.dumps(changed), self.root, self.plan, request["id"])

    def test_worker_and_quiet_native_options_inherit_only_the_actual_descriptions(self):
        argv = [self.plan["tools"]["worker"]["path"], "-I", "-B", str(self.root / "checkout/pm/worker.py")]
        options = {"stdin": subprocess.PIPE, "stdout": subprocess.PIPE, "text": True, "encoding": "utf-8", "env": self.environment}
        worker = chain.worker_kwargs(self.case.held, self.primary, self.expected, self.case.official, self.plan, argv, options)
        self.assertTrue(worker["close_fds"])
        self.assertEqual(worker["pass_fds"], (*self.case.held.pass_fds, self.case.official))
        uv, python = self.plan["tools"]["uv"]["path"], self.plan["tools"]["python"]["path"]
        command = [uv, "lock", "--python", python, "--offline"]
        env = {**self.environment, "UV_PYTHON": python, "UV_PROJECT_ENVIRONMENT": self.plan["arguments"]["out"],
               "UV_CACHE_DIR": str(self.root / "cache"), "UV_PYTHON_DOWNLOADS": "never"}
        options = {"cwd": self.plan["arguments"]["source"], "env": env, "stdout": subprocess.PIPE,
                   "stderr": subprocess.STDOUT, "text": True, "encoding": "utf-8", "errors": "replace", "bufsize": 0}
        native = chain.native_kwargs(self.case.held, self.primary, self.expected, self.case.official, self.plan, command, options)
        self.assertEqual(native["pass_fds"], worker["pass_fds"])
        for fd in native["pass_fds"]:
            os.fstat(fd)
        for changed in [{**options, "shell": True}, {**options, "cwd": "/tmp"},
                        {**options, "env": {**env, "UV_PROJECT_ENVIRONMENT": "/tmp"}}]:
            with self.assertRaises(ValueError):
                chain.native_kwargs(self.case.held, self.primary, self.expected, self.case.official, self.plan, command, changed)
        with self.assertRaises(ValueError):
            chain.native_kwargs(self.case.held, self.primary, self.expected, self.case.official, self.plan,
                                [uv, "run", "unselected"], options)

    def test_partial_build_intent_is_never_repaired_or_accepted(self):
        domain = self.case.held.domains[0]
        fd = domain.intent()
        try:
            os.ftruncate(fd, len(self.history[0]) - 3)
            os.fsync(fd)
        finally:
            os.close(fd)
        partial = joint.source.slots(self.case.held)
        with self.assertRaises(ValueError):
            self.require()
        self.assertEqual(joint.source.slots(self.case.held), partial)


if __name__ == "__main__":
    unittest.main()
