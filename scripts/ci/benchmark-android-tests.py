"""Controlled full-suite JVM fork experiment; only for an isolated credential-free runner."""
import collections
import hashlib
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ERROR = {
    "code": "HR-CI-001",
    "summaryZh": "构建对比测试未完成，请查看测试日志并修正后重试。",
    "summaryEn": "The build comparison did not complete. Review the test logs, fix the cause and retry.",
    "retryable": True, "recoveryAction": "inspect_benchmark_logs_and_retry", "stage": "android_test_benchmark",
}
ORDER = ("baseline", "candidate", "candidate", "baseline")
TASK = ":app:testDebugUnitTest"
SAFE_CAUSES = {"invalid_duration", "duplicate_task", "missing_tasks", "missing_junit", "junit_too_large",
               "incomplete_junit", "invalid_junit_class", "invalid_testcase", "inconsistent_junit", "failing_tests",
               "unsafe_output", "runner_not_comparable", "checkout_not_clean", "gradle_failed", "missing_profile",
               "baseline_configuration_changed", "execution_configuration_changed", "test_task_not_executed",
               "compiler_reran_between_samples", "test_coverage_changed", "unsupported_xml"}


def seconds(value):
    match = re.fullmatch(r"(?:(\d+)h)?(?:(\d+)m)?(\d+(?:\.\d+)?)s", value)
    if not match:
        raise ValueError("invalid_duration")
    hours, minutes, tail = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(tail)


class ProfileRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows, self.row, self.cell = [], None, None

    def handle_starttag(self, tag, _attrs):
        if tag == "tr":
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = []

    def handle_data(self, text):
        if self.cell is not None:
            self.cell.append(text)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self.cell is not None:
            self.row.append("".join(self.cell).strip())
            self.cell = None
        elif tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None


def profile_tasks(text):
    parser = ProfileRows()
    parser.feed(text)
    tasks = {}
    for row in parser.rows:
        if len(row) == 3 and re.fullmatch(r":[A-Za-z0-9_:]+", row[0]) and row[2] != "(total)":
            if row[0] in tasks:
                raise ValueError("duplicate_task")
            tasks[row[0]] = {"seconds": seconds(row[1]), "result": row[2]}
    if not tasks:
        raise ValueError("missing_tasks")
    return tasks


class JunitTreeBuilder(ET.TreeBuilder):
    def doctype(self, _name, _public_id, _system_id):
        # Gradle JUnit has no DTD. Reject it before any entity can be expanded,
        # including UTF-16 input where a byte-substring check would be insufficient.
        raise ValueError("unsupported_xml")


def junit_summary(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError("missing_junit")
    counts = collections.Counter(tests=0, failures=0, errors=0, skipped=0)
    identities, classes = [], []
    for file in files:
        if file.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("junit_too_large")
        # Audited: local generated JUnit, bounded above, DTD/entities rejected by the
        # parser target. No external resolver or third-party Python install required.
        # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
        suite = ET.parse(file, parser=ET.XMLParser(target=JunitTreeBuilder())).getroot()
        cases = suite.findall("testcase")
        if suite.tag != "testsuite" or int(suite.get("tests", "-1")) != len(cases):
            raise ValueError("incomplete_junit")
        row = {"class": suite.get("name"), "tests": len(cases), "suiteSeconds": float(suite.get("time", "nan")), "caseSeconds": 0.0}
        if not re.fullmatch(r"[A-Za-z0-9_.$]+", row["class"] or "") or not math.isfinite(row["suiteSeconds"]) or row["suiteSeconds"] < 0:
            raise ValueError("invalid_junit_class")
        actual = collections.Counter(tests=len(cases), failures=0, errors=0, skipped=0)
        for case in cases:
            duration = float(case.get("time", "nan"))
            if not math.isfinite(duration) or duration < 0 or not case.get("name") or not case.get("classname"):
                raise ValueError("invalid_testcase")
            row["caseSeconds"] += duration
            for kind in ("failure", "error", "skipped"):
                actual[{"failure": "failures", "error": "errors", "skipped": "skipped"}[kind]] += case.find(kind) is not None
            identities.append((case.get("classname"), case.get("name"), case.find("skipped") is not None))
        for key in ("failures", "errors", "skipped"):
            if int(suite.get(key, "0")) != actual[key]:
                raise ValueError("inconsistent_junit")
        counts.update(actual)
        classes.append(row)
    if not counts["tests"] or counts["failures"] or counts["errors"]:
        raise ValueError("failing_tests")
    # Preserve duplicate identities; a set would hide lost/duplicated tests.
    digest = hashlib.sha256(json.dumps(sorted(identities), ensure_ascii=False).encode()).hexdigest()
    return {"counts": dict(counts), "identityHash": digest,
            "sumSuiteSeconds": sum(row["suiteSeconds"] for row in classes),
            "sumCaseSeconds": sum(row["caseSeconds"] for row in classes),
            "classes": sorted(classes, key=lambda row: row["suiteSeconds"], reverse=True)}


INIT = r'''
gradle.projectsEvaluated {
    allprojects {
        tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
            if (path == ':app:testDebugUnitTest') {
                int requested = Integer.parseInt(gradle.startParameter.projectProperties['researchForks'])
                int cpus = Runtime.runtime.availableProcessors()
                if (requested < 1 || requested > cpus || requested > gradle.startParameter.maxWorkerCount) {
                    throw new GradleException('Research fork limit is outside runner capability')
                }
                println('ANDROID_RESEARCH_CONFIG ' + groovy.json.JsonOutput.toJson([
                    cpus: cpus, originalForks: maxParallelForks, requestedForks: requested, heap: maxHeapSize
                ]))
                maxParallelForks = requested
                beforeTest { descriptor ->
                    println('ANDROID_TEST_START ' + groovy.json.JsonOutput.toJson([
                        testClass: descriptor.className, methodHash: Integer.toHexString(descriptor.name.hashCode())
                    ]))
                }
                afterTest { descriptor, result ->
                    println('ANDROID_TEST_END ' + groovy.json.JsonOutput.toJson([
                        testClass: descriptor.className, methodHash: Integer.toHexString(descriptor.name.hashCode()),
                        result: result.resultType.toString()
                    ]))
                }
                doFirst {
                    println('ANDROID_RESEARCH_EXECUTION ' + groovy.json.JsonOutput.toJson([
                        forks: maxParallelForks, heap: maxHeapSize,
                        watchdogEnabled: jvmArgs.any { it.startsWith('-javaagent:') && it.contains('test-watchdog.jar') }
                    ]))
                }
            }
        }
    }
}
'''


def snapshot_threads(output, label, interval):
    try:
        listing = subprocess.run(["jcmd", "-l"], capture_output=True, text=True, timeout=15)
        for line in listing.stdout.splitlines():
            pid, _, command = line.partition(" ")
            if pid.isdecimal() and "GradleWorkerMain" in command:
                with (output / f"{label}-worker-{pid}-{interval}.txt").open("w") as dump:
                    subprocess.run(["jcmd", pid, "Thread.print", "-l"], stdout=dump,
                                   stderr=subprocess.STDOUT, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        # Diagnostics are best effort and must not terminate or change the test run.
        (output / f"{label}-diagnostics-{interval}.txt").write_text("Thread snapshot unavailable.\n")


def main():
    root = Path(__file__).resolve().parents[2]
    output = Path(os.environ["BENCHMARK_OUTPUT_DIR"]).resolve()
    if output == root or root in output.parents:
        raise ValueError("unsafe_output")
    output.mkdir(parents=True, exist_ok=True)
    cpus = len(os.sched_getaffinity(0))
    memory = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    baseline, candidate = max(1, cpus // 2), min(cpus, 4)
    if memory < 12 * 1024 ** 3 or candidate <= baseline:
        raise ValueError("runner_not_comparable")
    metadata = {"sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                "androidTree": subprocess.check_output(["git", "rev-parse", "HEAD:android"], cwd=root, text=True).strip(),
                "cpus": cpus, "memoryBytes": memory, "forks": {"baseline": baseline, "candidate": candidate}, "samples": []}
    init = output / "research.init.gradle"
    init.write_text(INIT)
    android = root / "android"
    common = ["--no-build-cache", "--no-configuration-cache", "--profile", "--console=plain", "-I", str(init)]

    def run(label, tasks, forks):
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
            raise ValueError("checkout_not_clean")
        before = set((android / "build/reports/profile").glob("*.html"))
        start = time.monotonic()
        with (output / f"{label}.log").open("w") as stream:
            with subprocess.Popen(["./gradlew", *tasks, *common, f"-PresearchForks={forks}"], cwd=android,
                                  stdout=stream, stderr=subprocess.STDOUT) as process:
                for interval in range(20):
                    try:
                        process.wait(timeout=60)
                        break
                    except subprocess.TimeoutExpired:
                        # Credential-free test workers only; preserve evidence before the existing
                        # 600s watchdog kills a hung process. Never change the watchdog deadline.
                        if interval in (2, 6):
                            snapshot_threads(output, label, interval)
                else:
                    process.kill()
                    process.wait()
                    raise ValueError("gradle_failed")
        fresh = set((android / "build/reports/profile").glob("*.html")) - before
        if len(fresh) != 1:
            raise ValueError("missing_profile")
        profile = fresh.pop()
        shutil.copyfile(profile, output / f"{label}.html")
        if process.returncode:
            raise ValueError("gradle_failed")
        return time.monotonic() - start, profile_tasks(profile.read_text())

    elapsed, tasks = run("compile", [":app:compileDebugUnitTestKotlin", ":testWatchdogJar", ":verifyTestWatchdog"], baseline)
    metadata["compile"] = {"elapsedSeconds": elapsed, "tasks": tasks}
    (output / "results.json").write_text(json.dumps(metadata, indent=2) + "\n")
    for index, variant in enumerate(ORDER, 1):
        label = f"{index}-{variant}"
        elapsed, tasks = run(label, [TASK, "--rerun"], metadata["forks"][variant])
        lines = (output / f"{label}.log").read_text().splitlines()
        configuration = [json.loads(line.split(" ", 1)[1]) for line in lines if line.startswith("ANDROID_RESEARCH_CONFIG ")]
        if len(configuration) != 1 or configuration[0] != {"cpus": cpus, "originalForks": baseline, "requestedForks": metadata["forks"][variant], "heap": "1g"}:
            raise ValueError("baseline_configuration_changed")
        execution = [json.loads(line.split(" ", 1)[1]) for line in lines
                     if line.startswith("ANDROID_RESEARCH_EXECUTION ")]
        if len(execution) != 1 or execution[0] != {"forks": metadata["forks"][variant], "heap": "1g", "watchdogEnabled": True}:
            raise ValueError("execution_configuration_changed")
        if tasks[TASK]["result"] != "" or tasks[TASK]["seconds"] <= 0:
            raise ValueError("test_task_not_executed")
        for dependency in (":app:kspDebugKotlin", ":app:compileDebugKotlin", ":app:compileDebugUnitTestKotlin"):
            if tasks[dependency]["result"] != "UP-TO-DATE":
                raise ValueError("compiler_reran_between_samples")
        summary = junit_summary(android / "app/build/test-results/testDebugUnitTest")
        if metadata["samples"] and any(summary[key] != metadata["samples"][0]["junit"][key] for key in ("counts", "identityHash")):
            raise ValueError("test_coverage_changed")
        row = {"variant": variant, "forks": metadata["forks"][variant], "elapsedSeconds": elapsed,
               "testTaskSeconds": tasks[TASK]["seconds"], "junit": summary}
        metadata["samples"].append(row)
        (output / "results.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print("ANDROID_SAMPLE " + json.dumps({key: value for key, value in row.items() if key != "junit"}), flush=True)
    print("ANDROID_BENCHMARK_COMPLETE", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        cause = str(error) if isinstance(error, ValueError) and str(error) in SAFE_CAUSES else "inspect_benchmark_logs"
        print(f"{ERROR['code']}: {ERROR['summaryZh']}\n{ERROR['summaryEn']}", file=sys.stderr)
        print(json.dumps({**ERROR, "technicalCause": cause}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
