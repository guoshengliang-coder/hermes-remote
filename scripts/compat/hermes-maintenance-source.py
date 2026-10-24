#!/usr/bin/env python3
"""Fixed upstream old/new Git switch in disposable fixtures; no updater or PM runs."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("source_target_cli", HERE / "hermes-maintenance-target.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)
source = cli.driver.load("source_transition_driver", cli.driver.PROTOTYPE / "source_transition.py")
lease, support = source.load("maintenance_lease"), source.load("handoff_actor")
OLD, NEW = "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121", cli.driver.COMMIT


class Fixture:
    def __init__(self, old, candidate, before=OLD, after=NEW):
        # Production CLI fixes both identities; tests may supply their explicitly synthetic Git pair.
        source.worker.verify_source(old, before)
        source.worker.verify_source(candidate, after)
        self.root = Path(tempfile.mkdtemp(prefix="hg199-source-transition-")).resolve()
        for name in ("control", "data", "user", "tmp"):
            (self.root / name).mkdir(mode=0o700)
        marker = self.root / ".hg199-isolated-source-transition"
        marker.write_bytes(source.MARKER)
        marker.chmod(0o600)
        env = cli.driver.environment(self.root)
        subprocess.run(["/usr/bin/git", "-c", "core.hooksPath=/dev/null", "clone", "--quiet", "--no-hardlinks",
                        str(old), str(self.root / "checkout")], env=env, stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=120)
        source.worker.git(self.root / "checkout", "fetch", "--no-tags", str(candidate), after, snapshot_fetch=True)
        self.domains = [lease.Domain(self.root / "control", kind, self.root / name)
                        for kind, name in (("home", "data"), ("checkout", "checkout"))]
        for domain in self.domains:
            domain.enroll()
        held = self.held()
        try:
            records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
            context = source.binding.context(str(uuid.uuid4()), before, self.root / "checkout", self.root / "data",
                                             "hermes_cli.main", records, list(source.NAMES))
            target = copy.deepcopy(context)
            target.update(source=after, files=source.binding.snapshot(candidate, list(source.NAMES)))
            self.plan = {"schema": 1, "policy": source.POLICY, "before": context, "after": target,
                         "trees": {"before": source.worker.git(old, "rev-parse", before + "^{tree}").decode().strip(),
                                   "after": source.worker.git(candidate, "rev-parse", after + "^{tree}").decode().strip()}}
            self.args = (context["run"], before, after, source.binding.digest(self.plan))
            source.intent.begin(held, context)
        finally:
            held.close()

    def held(self):
        held = lease.Lease(self.domains, maintenance=True)
        if not held.drain():
            held.close()
            raise ValueError("source fixture busy")
        return held

    def blocked(self):
        try:
            writer = lease.Lease(self.domains)
        except lease.Pending:
            return
        writer.close()
        raise ValueError("source transition opened admission")

    def killed(self, action, phase):
        manifest = source.binding.private_context_fd(self.root / "control", self.plan)
        process = None
        try:
            process = subprocess.Popen([sys.executable, "-I", "-S", "-B", str(cli.driver.PROTOTYPE / "source_transition_actor.py"),
                                        str(self.root), str(manifest), *self.args, action, phase],
                                       env=cli.driver.environment(self.root), stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, pass_fds=(manifest,))
            os.close(manifest)
            manifest = None
            if support.read(process.stdout.fileno(), timeout=90) != {"checkpoint": action + "-" + phase}:
                raise ValueError("source checkpoint not observed")
            process.kill()
            if process.wait(timeout=5) != -signal.SIGKILL or process.stdout.read(1):
                raise ValueError("source coordinator death not observed")
        finally:
            if manifest is not None:
                os.close(manifest)
            if process is not None:
                if process.poll() is None:
                    process.kill()  # Only this test-owned Popen.
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()
        self.blocked()


def inspect(old, candidate, *, identities=(OLD, NEW)):
    fixture = Fixture(old, candidate, *identities)
    fixture.killed("prepare", "complete")
    held = fixture.held()
    try:
        source.require(held, fixture.plan, *fixture.args, "before")
        saved = source.slots(held)
        intent_inodes = [d._path("intent").stat().st_ino for d in held.domains]
    finally:
        held.close()
    fixture.killed("move", "complete")
    held = fixture.held()
    refusals = 0
    try:
        source.require(held, fixture.plan, *fixture.args, "after")
        if source.slots(held) != saved or [d._path("intent").stat().st_ino for d in held.domains] != intent_inodes:
            raise ValueError("source transition replaced intent history")
        for args, state in (((str(uuid.uuid4()), *fixture.args[1:]), "after"),
                            ((fixture.args[0], "0" * 40, *fixture.args[2:]), "after"),
                            ((fixture.args[0], fixture.args[1], "0" * 40, fixture.args[3]), "after"),
                            ((*fixture.args[:3], "0" * 64), "after"), (fixture.args, "before")):
            try:
                source.require(held, fixture.plan, *args, state)
            except (ValueError, source.worker.Refused):
                refusals += 1
            else:
                raise ValueError("changed source expectations accepted")
        path = fixture.root / "checkout/hermes_constants.py"
        path.write_bytes(path.read_bytes() + b"\n# isolated source fault\n")
        try:
            source.require(held, fixture.plan, *fixture.args, "after")
        except (ValueError, source.worker.Refused):
            refusals += 1
        else:
            raise ValueError("changed source bytes accepted")
    finally:
        held.close()
    fixture.blocked()
    partial = Fixture(old, candidate, *identities)
    partial.killed("prepare", "partial")
    held = partial.held()
    try:
        saved = source.slots(held)
        try:
            source.require(held, partial.plan, *partial.args, "before")
        except ValueError:
            pass
        else:
            raise ValueError("partial source plan accepted")
        if source.slots(held) != saved:
            raise ValueError("partial source plan repaired")
    finally:
        held.close()
    partial.blocked()
    return {"fixedGitSourceSwitchObserved": True, "sourceCoordinatorSigkillCases": 3,
            "sourceExpectationRefusals": refusals, "partialPlanPreserved": True, "ordinaryWriterBlocked": True,
            "updaterExecuted": False, "pmExecuted": False, "runtimeVerified": False,
            "updaterRecoveryVerified": False, "productionCompletionVerified": False,
            "durableHandoffVerified": False, "certifiesUpgrade": False}


def main():
    parser = cli.PlanArguments(description=__doc__)
    parser.add_argument("--old-root", required=True, type=Path)
    parser.add_argument("--candidate-root", required=True, type=Path)
    try:
        args = parser.parse_args()
        roots = [args.old_root.resolve(), args.candidate_root.resolve()]
        if any(root.is_relative_to(Path.home() / ".hermes") for root in roots):
            raise ValueError("live installation source refused")
        value = inspect(*roots)
    except (ValueError, TypeError, OSError, RuntimeError, source.worker.Refused, subprocess.SubprocessError):
        print(json.dumps({"ok": False, "code": "HR-HERMESUPDATE-002", "retryable": False,
                          "summaryZh": "暂时无法安全升级 Hermes，请查看升级条件后重新检查。",
                          "summaryEn": "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again.",
                          "reason": "source-transition-needs-review"}, ensure_ascii=False))
        return 1
    print(json.dumps({"ok": True, "observations": value}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
