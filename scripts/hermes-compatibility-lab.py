#!/usr/bin/env python3
"""I0 developer rehearsal. Never operates on an existing user installation.

The default experiment covers the repository stage. --runtime also exercises
real dependency installation and synthetic history through a temporary backend.
Full contract/managed-service validation remain separate, unexecuted gates.
No compatibility approval or production recommendation is emitted here.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

OFFICIAL_REPOSITORY = "https://github.com/NousResearch/hermes-agent.git"
SUITE_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
CASE_KEYS = {"schemaVersion", "sourceCommit", "targetCommit", "installerSha256", "branch"}
UNEXECUTED = ["dependencyUpgrade", "historyMigration", "restRpc", "connector",
              "launchAgentRecovery", "concurrentWriters", "crashRecovery"]
RUNTIME_SOURCE = "633dda6d7fdcac7e8340471c9f5ed08cdccbd70c"
RUNTIME_TARGET = "bf0203a794a07aac3ff764972ab9087baa4e4f4e"
RUNTIME_TARGET_TAG = "v0.22.5"
HISTORY_TEXT = "Hermes GO 升级前的合成会话"
SEED_HISTORY = '''from hermes_state import SessionDB
db = SessionDB()
db.create_session("go-compat-lab-history", "cli")
db.append_message("go-compat-lab-history", "user", "Hermes GO 升级前的合成会话")
db.close()
'''


class LabRefusal(Exception):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason)


def read_case(file):
    case = json.loads(Path(file).read_text())
    if (not isinstance(case, dict) or set(case) != CASE_KEYS
            or type(case["schemaVersion"]) is not int or case["schemaVersion"] != 1):
        raise LabRefusal("invalid_case")
    for field, size in [("sourceCommit", 40), ("targetCommit", 40), ("installerSha256", 64)]:
        if not isinstance(case[field], str) or not re.fullmatch("[a-f0-9]{%s}" % size, case[field]):
            raise LabRefusal("invalid_case")
    # The first audited official installer requires its commit on origin/main.
    # Broader recipes need their own audit; this is deliberately not a shell interface.
    if case["branch"] != "main" or case["sourceCommit"] == case["targetCommit"]:
        raise LabRefusal("invalid_case")
    return case


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def tree_digest(root):
    """Hash bytes, relative names, modes and link targets, without following links."""
    records = []
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            item = Path(directory) / name
            info = item.lstat()
            relative = str(item.relative_to(root))
            if stat.S_ISLNK(info.st_mode):
                content = ["link", os.readlink(item)]
            elif stat.S_ISREG(info.st_mode):
                digest = hashlib.sha256()
                with item.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                content = ["file", digest.hexdigest()]
            elif stat.S_ISDIR(info.st_mode):
                content = ["directory"]
            else:
                raise LabRefusal("unsupported_snapshot_entry")
            records.append([relative, stat.S_IMODE(info.st_mode), content])
    return sha256(json.dumps(sorted(records), separators=(",", ":")).encode())


def isolated_environment(root):
    """Do not inherit credentials, Python import hooks, Git config or live HOME."""
    home = root / "user"
    env = {
        "HOME": str(home), "HERMES_HOME": str(home / ".hermes"),
        "PATH": str(root / "lab-tools") + ":/usr/bin:/bin:/usr/sbin:/sbin", "SHELL": "/bin/bash",
        "TMPDIR": str(root / "tmp"), "LANG": "en_US.UTF-8", "CI": "1",
        "XDG_CACHE_HOME": str(home / ".cache"), "XDG_CONFIG_HOME": str(home / ".config"),
        "XDG_DATA_HOME": str(home / ".local/share"),
        "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_TERMINAL_PROMPT": "0", "GIT_CONFIG_COUNT": "2",
        "GIT_CONFIG_KEY_0": "core.hooksPath", "GIT_CONFIG_VALUE_0": "/dev/null",
        "GIT_CONFIG_KEY_1": "core.fsmonitor", "GIT_CONFIG_VALUE_1": "false",
        "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1",
    }
    # Network transport only; never print inherited values. The source installer
    # needs the host's already configured proxy on some developer machines.
    for key in ("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY",
                "https_proxy", "http_proxy", "all_proxy", "no_proxy"):
        if key in os.environ:
            env[key] = os.environ[key]
    return env


def prepare_lab_tools(root):
    # BSD mktemp requires a template; the old official installer falls back to
    # hard-coded /tmp names when its no-argument call fails. Supply that one
    # GNU-compatible form inside the lab instead of granting global /tmp writes.
    # The upstream installer remains byte-identical to its approved Git blob.
    directory = root / "lab-tools"
    directory.mkdir(mode=0o700)
    command = directory / "mktemp"
    command.write_text('''#!/bin/sh
if [ "$#" -eq 0 ]; then
    exec /usr/bin/mktemp "${TMPDIR:?}/hermes-lab.XXXXXXXXXX"
elif [ "$#" -eq 1 ] && [ "$1" = "-d" ]; then
    exec /usr/bin/mktemp -d "${TMPDIR:?}/hermes-lab.XXXXXXXXXX"
fi
exec /usr/bin/mktemp "$@"
''')
    command.chmod(0o700)


def sandbox_profile(root, real_home):
    # An env-only HOME override does not protect real services or data. All
    # upstream execution runs under these OS-enforced restrictions. macOS only.
    def quoted(path):
        value = str(Path(path).resolve())
        if any(c in value for c in '\n\r\x00"\\'):
            raise LabRefusal("unsupported_sandbox_path")
        return '"' + value + '"'
    lab = quoted(root)
    owner = quoted(real_home)
    writable = ' '.join('(subpath %s)' % quoted(root / part) for part in ("user", "tmp"))
    logs = ' '.join('(literal %s)' % quoted(root / name) for name in
                    ("private.log", "sourceHistory.private.log", "candidateHistory.private.log", "restoredHistory.private.log"))
    return "\n".join([
        "(version 1)", "(allow default)",
        "(deny file-write*)", "(deny file-read* (subpath %s))" % owner,
        "(allow file-read* (subpath %s))" % lab,
        "(allow file-write* %s %s)" % (writable, logs),
        '(allow file-write* (literal "/dev/null") (literal "/dev/tty"))',
        "(deny signal (target others))",
        '(deny process-exec (literal "/bin/launchctl") (literal "/usr/bin/osascript")'
        ' (literal "/usr/bin/sudo"))',
    ]) + "\n"


def run(argv, env, cwd, log, timeout=900):
    """Private log, bounded child lifetime; no raw subprocess output in reports."""
    fd = os.open(str(log), os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW)
    with os.fdopen(fd, "ab") as output:
        metadata = os.fstat(output.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise LabRefusal("unsafe_lab_log")
        process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                   start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return 124


def git_read(repository, *args):
    # This reads the trusted audit cache only, never imports upstream Python.
    env = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_TERMINAL_PROMPT": "0"}
    result = subprocess.run(["/usr/bin/git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null",
                             "-c", "core.fsmonitor=false", "-C", str(repository), *args],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60)
    if result.returncode:
        raise LabRefusal("audit_cache_incomplete")
    return result.stdout


def verify_inputs(cache, case):
    for field in ("sourceCommit", "targetCommit"):
        resolved = git_read(cache, "rev-parse", case[field] + "^{commit}").decode().strip()
        if resolved != case[field]:
            raise LabRefusal("commit_identity_mismatch")
    installer = git_read(cache, "show", case["targetCommit"] + ":scripts/install.sh")
    if sha256(installer) != case["installerSha256"]:
        raise LabRefusal("installer_digest_mismatch")
    return installer


def restore_snapshot(root, before_digest):
    """Keep the failed candidate, restore to the SAME path used by launchers."""
    user = root / "user"
    backup = root / "snapshot"
    if tree_digest(backup) != before_digest:
        raise LabRefusal("snapshot_digest_mismatch")
    user.rename(root / "candidate-after-experiment")
    shutil.copytree(backup, user, symlinks=True)
    if tree_digest(user) != before_digest:
        raise LabRefusal("restore_digest_mismatch")


def probe_history(root, profile, env, launcher, name, expected_version):
    """Real loopback REST with a fresh token and an OS-selected port. No model calls."""
    log = root / (name + ".private.log")
    # Never accept a previous process's ready marker. Keep one descriptor so a
    # path replacement cannot redirect the controller's reads to another file.
    fd = os.open(str(log), os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    token = secrets.token_urlsafe(32)
    child_env = {**env, "HERMES_DESKTOP": "1", "HERMES_DASHBOARD_SESSION_TOKEN": token}
    with os.fdopen(fd, "r+b", buffering=0) as output:
        child = subprocess.Popen(["/usr/bin/sandbox-exec", "-f", str(profile), str(launcher),
                                  "--profile", "default", "serve", "--host", "127.0.0.1", "--port", "0"],
                                 cwd=launcher.parents[2], env=child_env, stdout=output, stderr=output,
                                 stdin=subprocess.DEVNULL, start_new_session=True)
        try:
            deadline = time.monotonic() + 180
            port = None
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise LabRefusal(name + "_startup_failed")
                fresh_output = os.pread(output.fileno(), 4 * 1024 * 1024, 0).decode(errors="replace")
                marker = re.search(r"(?m)^HERMES_BACKEND_READY port=([0-9]+)\s*$", fresh_output)
                if marker and 0 < int(marker[1]) <= 65535:
                    port = int(marker[1])
                    break
                time.sleep(0.25)
            if port is None:
                raise LabRefusal(name + "_startup_timeout")
            # Never send the synthetic session token through the system proxy.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            status_request = urllib.request.Request("http://127.0.0.1:%s/api/status" % port,
                                                    headers={"X-Hermes-Session-Token": token})
            with opener.open(status_request, timeout=15) as response:
                status = json.loads(response.read(2 * 1024 * 1024))
            if status.get("version") != expected_version:
                raise LabRefusal(name + "_running_version_mismatch")
            url = "http://127.0.0.1:%s/api/sessions/go-compat-lab-history/messages" % port
            request = urllib.request.Request(url, headers={"X-Hermes-Session-Token": token})
            with opener.open(request, timeout=15) as response:
                body = response.read(2 * 1024 * 1024)
                payload = json.loads(body)
            if HISTORY_TEXT not in json.dumps(payload, ensure_ascii=False):
                raise LabRefusal(name + "_history_missing")
            with opener.open("http://127.0.0.1:%s/openapi.json" % port, timeout=15) as response:
                schema = json.loads(response.read(8 * 1024 * 1024))
            paths = schema.get("paths")
            if not isinstance(paths, dict):
                raise LabRefusal(name + "_schema_missing")
            # Paths/methods only. No descriptions, examples or personal responses.
            footprint = {"openapi": "3.1.0", "paths": {
                path: {method: {} for method in methods if method in
                       {"get", "put", "post", "delete", "patch", "options", "head"}}
                for path, methods in paths.items() if not path.startswith("/api/plugins/")}}
            encoded = (json.dumps(footprint, indent=2) + "\n").encode()
            (root / (name + ".openapi-paths.json")).write_bytes(encoded)
            return {"passed": True, "historyPresent": True, "runningVersion": expected_version,
                    "pathCount": len(footprint["paths"]),
                    "openapiPathsSha256": sha256(encoded)}
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()


def rehearse(cache, case, runtime=False, fault_after=None):
    if platform.system() != "Darwin":
        raise LabRefusal("isolated_macos_required")
    if runtime and (case["sourceCommit"] != RUNTIME_SOURCE or case["targetCommit"] != RUNTIME_TARGET):
        raise LabRefusal("unaudited_runtime_recipe")
    if fault_after not in (None, "repository", "target_python-deps") or (fault_after == "target_python-deps" and not runtime):
        raise LabRefusal("invalid_fault_stage")
    installer = verify_inputs(cache, case)
    source_installer = git_read(cache, "show", case["sourceCommit"] + ":scripts/install.sh") if runtime else None
    root = Path(tempfile.mkdtemp(prefix="hermes-compat-lab-")).resolve()
    root.chmod(0o700)
    for directory in ("user/.hermes", "tmp"):
        (root / directory).mkdir(parents=True, mode=0o700)
    prepare_lab_tools(root)
    env = isolated_environment(root)
    profile = root / "isolation.sb"
    profile.write_text(sandbox_profile(root, Path.home()))
    log = root / "private.log"
    log.touch(mode=0o600)
    report = {"schemaVersion": 1, "kind": "runtime-rehearsal" if runtime else "source-stage-rehearsal",
              "case": case, "platform": platform.system(), "arch": platform.machine(),
              "suiteSha256": SUITE_SHA256,
              "startedAt": int(time.time()), "status": "running", "checks": {},
              "notExecuted": UNEXECUTED, "compatibilityApproved": False}
    if fault_after:
        report["faultAfter"] = fault_after
    report_path = root / "report.json"

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        report_path.chmod(0o600)

    def step(name, argv, isolated=True, cwd=None):
        report["activeStage"] = name
        save()
        if isolated:
            argv = ["/usr/bin/sandbox-exec", "-f", str(profile), *argv]
        rc = run(argv, env, cwd or root, log)
        report["checks"][name] = {"passed": rc == 0, "exitCode": rc}
        save()
        if rc:
            raise LabRefusal(name + "_failed")

    snapshot_digest = None
    repo = root / "user/.hermes/hermes-agent"
    try:
        step("sandbox", ["/usr/bin/true"])
        # Positive startup is insufficient: prove the write fence actually
        # refuses a harmless outside sentinel before running upstream code.
        with tempfile.NamedTemporaryFile(prefix="hermes-compat-fence-", delete=False) as probe:
            probe.write(b"unchanged")
            probe_path = Path(probe.name)
        try:
            step("writeFence", ["/bin/sh", "-c", 'if echo changed > "$1"; then exit 1; fi',
                                "probe", str(probe_path)])
            if probe_path.read_bytes() != b"unchanged":
                raise LabRefusal("isolation_fence_failed")
        finally:
            probe_path.unlink()
        # The candidate cannot rewrite the controller's reports or snapshots.
        protected = root / "protected-sentinel"
        protected.write_bytes(b"unchanged")
        step("evidenceFence", ["/bin/sh", "-c", 'if echo changed > "$1"; then exit 1; fi',
                               "probe", str(protected)])
        if protected.read_bytes() != b"unchanged":
            raise LabRefusal("evidence_fence_failed")
        # The cache is only for immutable input verification. The actual lab
        # install comes directly from upstream, without alternates, hardlinks,
        # inherited hooks/config, or a dependency on the audit cache's lifetime.
        step("clone", ["/usr/bin/git", "clone", "--no-local", "--filter=blob:none", "--no-checkout",
                       "--single-branch", "--no-tags", "--branch", case["branch"],
                       OFFICIAL_REPOSITORY, str(repo)])
        step("origin", ["/usr/bin/git", "-C", str(repo), "remote", "set-url", "origin", OFFICIAL_REPOSITORY])
        step("sourceCheckout", ["/usr/bin/git", "-C", str(repo), "checkout", "--detach", case["sourceCommit"]])
        if runtime:
            # Upstream's new stamp derives baseVersion from reachable release
            # tags. A --no-tags cache alone cannot prove a named release.
            step("targetTag", ["/usr/bin/git", "-C", str(repo), "fetch", "--no-tags", "origin",
                               "refs/tags/%s:refs/tags/%s" % (RUNTIME_TARGET_TAG, RUNTIME_TARGET_TAG)])
            if git_read(repo, "rev-parse", "refs/tags/%s^{commit}" % RUNTIME_TARGET_TAG).decode().strip() != case["targetCommit"]:
                raise LabRefusal("release_tag_moved")
            old_script = root / "source-install.sh"
            old_script.write_bytes(source_installer)
            report["sourceInstallerSha256"] = sha256(source_installer)
            for stage in ("venv", "python-deps", "config"):
                step("source_" + stage, ["/bin/bash", str(old_script), "--stage", stage, "--json",
                     "--non-interactive", "--skip-browser", "--skip-computer-use", "--dir", str(repo),
                     "--hermes-home", env["HERMES_HOME"]])
            step("seedHistory", [str(repo / "venv/bin/python"), "-B", "-c", SEED_HISTORY], cwd=repo)
            report["activeStage"] = "sourceHistory"
            save()
            report["checks"]["sourceHistory"] = probe_history(root, profile, env, repo / "venv/bin/hermes", "sourceHistory", "0.21.3")
        # This is a backup probe, deliberately not a fabricated Hermes schema.
        data = root / "user/.hermes/synthetic-probe.sqlite3"
        with sqlite3.connect(str(data)) as db:
            db.execute("create table lab_probe (id integer primary key, body text)")
            db.execute("insert into lab_probe values (1, '升级前的合成数据')")
        (root / "user/.hermes/synthetic-config.yaml").write_text("lab: preserve-before-upgrade\n")
        shutil.copytree(root / "user", root / "snapshot", symlinks=True)
        snapshot_digest = tree_digest(root / "user")
        if tree_digest(root / "snapshot") != snapshot_digest:
            raise LabRefusal("snapshot_digest_mismatch")
        report["checks"]["snapshot"] = {"passed": True, "digest": snapshot_digest}
        script = root / "verified-install.sh"
        script.write_bytes(installer)
        step("repository", ["/bin/bash", str(script), "--stage", "repository", "--json",
                            "--non-interactive", "--dir", str(repo), "--hermes-home",
                            env["HERMES_HOME"], "--branch", case["branch"],
                            "--commit", case["targetCommit"]])
        actual = git_read(repo, "rev-parse", "HEAD").decode().strip()
        report["checks"]["exactTarget"] = {"passed": actual == case["targetCommit"], "actual": actual}
        if actual != case["targetCommit"]:
            raise LabRefusal("exact_target_mismatch")
        if fault_after == "repository":
            raise LabRefusal("injected_failure_after_repository")
        if runtime:
            for stage in ("venv", "python-deps", "config", "products", "complete"):
                step("target_" + stage, ["/bin/bash", str(script), "--stage", stage, "--json",
                     "--non-interactive", "--skip-browser", "--skip-computer-use", "--dir", str(repo),
                     "--hermes-home", env["HERMES_HOME"], "--commit", case["targetCommit"]])
                if stage == "python-deps" and fault_after == "target_python-deps":
                    raise LabRefusal("injected_failure_after_target_dependencies")
            report["activeStage"] = "candidateHistory"
            save()
            if git_read(repo, "rev-parse", "HEAD").decode().strip() != case["targetCommit"]:
                raise LabRefusal("completion_changed_target")
            report["checks"]["candidateHistory"] = probe_history(root, profile, env, repo / ".hermes/bin/hermes", "candidateHistory", "0.22.5")
            report["notExecuted"] = [x for x in UNEXECUTED if x not in ("dependencyUpgrade", "historyMigration")]
        # Model a candidate writing new config/data, then test byte-for-byte
        # pre-use recovery. This does NOT claim to test upstream migrations.
        with sqlite3.connect(str(data)) as db:
            db.execute("insert into lab_probe values (2, '候选阶段的数据')")
        (root / "user/.hermes/synthetic-config.yaml").write_text("lab: candidate\n")
        report["status"] = "runtime_stage_passed" if runtime else "source_stage_passed"
    except LabRefusal as error:
        report["status"] = "failed"
        report["reason"] = error.reason
    except Exception:
        report["status"] = "failed"
        report["reason"] = "lab_io_failure"
    finally:
        if snapshot_digest:
            try:
                restore_snapshot(root, snapshot_digest)
                actual = git_read(repo, "rev-parse", "HEAD").decode().strip()
                report["checks"]["restore"] = {"passed": actual == case["sourceCommit"], "actual": actual,
                                                 "digest": tree_digest(root / "user")}
                if actual != case["sourceCommit"]:
                    raise LabRefusal("restored_commit_mismatch")
                if runtime:
                    report["activeStage"] = "restoredHistory"
                    save()
                    report["checks"]["restoredHistory"] = probe_history(root, profile, env, repo / "venv/bin/hermes", "restoredHistory", "0.21.3")
            except Exception:
                report["status"] = "recovery_required"
                report["reason"] = "snapshot_recovery_failed"
        report["finishedAt"] = int(time.time())
        report.pop("activeStage", None)
        save()
    return report_path, report


def error_payload(recovery_required=False, has_report=True):
    if recovery_required:
        code = "HR-HERMESUPDATE-003"
        zh = "Hermes 升级未完成，需要恢复上次操作。请查看详情。"
        en = "Hermes upgrade did not finish. Recover the previous operation and review the details."
    else:
        code = "HR-HERMESUPDATE-002"
        zh = "暂时无法安全升级 Hermes，请查看升级条件后重新检查。"
        en = "Hermes cannot be upgraded safely yet. Review the upgrade conditions and check again."
    return {"code": code, "summaryZh": zh, "summaryEn": en, "retryable": False,
            "action": "review_report" if has_report else "check_case_and_cache"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True, help="isolated official Git source cache")
    parser.add_argument("--case", type=Path, required=True)
    parser.add_argument("--runtime", action="store_true", help="also install real dependencies, seed/read history, and prove restored service startup")
    parser.add_argument("--fault-after", choices=["repository", "target_python-deps"], help="inject a controlled failure to exercise restoration; target dependencies requires --runtime")
    args = parser.parse_args()
    try:
        report_path, report = rehearse(args.cache.resolve(), read_case(args.case), runtime=args.runtime, fault_after=args.fault_after)
        print(json.dumps({"report": str(report_path), "status": report["status"],
                          "compatibilityApproved": False}, ensure_ascii=False))
        if report["status"] in ("source_stage_passed", "runtime_stage_passed"):
            return 0
        error = error_payload(report["status"] == "recovery_required")
    except (LabRefusal, ValueError, OSError, subprocess.SubprocessError):
        error = error_payload(has_report=False)
    print(json.dumps(error, ensure_ascii=False))
    return 1


if __name__ == "__main__":
    sys.exit(main())
