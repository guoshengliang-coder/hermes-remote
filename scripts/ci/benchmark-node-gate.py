"""Six fixed, alternating Node-gate samples on one clean disposable checkout. Never publishes."""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time

ORDER = ("old", "new", "new", "old", "old", "new")
COMMANDS = {"old": ["bash", "-c", "npm run build && npm test"], "new": ["npm", "run", "test:ci"]}
FIELDS = ("tests", "pass", "fail", "skipped")
ERROR = {
    "code": "HR-CI-001",
    "summaryZh": "构建对比测试未完成，请查看测试日志并修正后重试。",
    "summaryEn": "The build comparison did not complete. Review the test logs, fix the cause and retry.",
    "retryable": True,
    "recoveryAction": "inspect_benchmark_logs_and_retry",
    "stage": "node_gate_benchmark",
}


def counts_from_log(text):
    counts = {key: [int(n) for n in re.findall(r"^# " + key + r" (\d+)$", text, re.M)] for key in FIELDS}
    # Four workspace suites plus scripts. Missing/incomplete TAP is not a valid performance result.
    if any(len(values) != 5 for values in counts.values()):
        raise ValueError("incomplete_test_counts")
    if sum(counts["fail"]) or not sum(counts["pass"]):
        raise ValueError("tests_failed")
    if any(total != passed + skipped for total, passed, skipped in zip(counts["tests"], counts["pass"], counts["skipped"])):
        raise ValueError("inconsistent_test_counts")
    return counts


def summarize(rows):
    if [row["variant"] for row in rows] != list(ORDER):
        raise ValueError("incomplete_samples")
    if any(row["counts"] != rows[0]["counts"] for row in rows):
        raise ValueError("test_counts_differ")
    medians = {variant: statistics.median(row["elapsedSeconds"] for row in rows if row["variant"] == variant) for variant in COMMANDS}
    saved = medians["old"] - medians["new"]
    return {"medians": medians, "savedSeconds": round(saved, 3),
            "savedPercent": round(100 * saved / medians["old"], 2), "allCountsIdentical": True}


def clean_outputs(root):
    for component in ("protocol", "connector", "gateway"):
        relative = component + "/dist"
        tracked = subprocess.check_output(["git", "ls-files", "--", relative], cwd=root, text=True)
        target = root / relative
        if tracked.strip() or target.is_symlink():
            raise ValueError("unsafe_output_directory")
        if target.exists():
            shutil.rmtree(target)


def main():
    root = Path(__file__).resolve().parents[2]
    output = Path(os.environ["BENCHMARK_OUTPUT_DIR"]).resolve()
    if output == root or root in output.parents:
        raise ValueError("output_must_be_outside_checkout")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
        raise ValueError("checkout_not_clean")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.pop("ACCOUNT_TEST_DATABASE_URL", None)
    env.pop("RUN_NETWORK_TESTS", None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    metadata = {"sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                "nodeVersion": subprocess.check_output(["node", "--version"], text=True).strip(),
                "system": platform.system(), "architecture": platform.machine(), "osVersion": platform.mac_ver()[0],
                "commands": COMMANDS, "order": ORDER, "samples": []}
    for number, variant in enumerate(ORDER, 1):
        clean_outputs(root)
        log_name = f"{number}-{variant}.log"
        print(f"BENCH_START {number}/6 {variant}", flush=True)
        start = time.monotonic()
        with (output / log_name).open("w") as stream:
            result = subprocess.run(COMMANDS[variant], cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT)
        row = {"round": number, "variant": variant, "elapsedSeconds": round(time.monotonic() - start, 3),
               "returncode": result.returncode, "log": log_name}
        metadata["samples"].append(row)
        (output / "results.json").write_text(json.dumps(metadata, indent=2) + "\n")
        if result.returncode:
            raise ValueError("benchmark_command_failed")
        row["counts"] = counts_from_log((output / log_name).read_text())
        (output / "results.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print("BENCH_DONE " + json.dumps(row), flush=True)
    summary = summarize(metadata["samples"])
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("BENCH_SUMMARY " + json.dumps(summary), flush=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
            stream.write("## Node gate benchmark\n\n| Command | Three times (s) | Median (s) |\n|---|---|---:|\n")
            for variant in COMMANDS:
                times = [row["elapsedSeconds"] for row in metadata["samples"] if row["variant"] == variant]
                stream.write(f"| {variant} | {', '.join(map(str, times))} | {summary['medians'][variant]} |\n")
            stream.write(f"\nMedian difference: {summary['savedSeconds']}s ({summary['savedPercent']}%). Test counts identical.\n\n")
            stream.write("Same source/runner; generated compilation outputs removed per sample. Three samples per group; not an end-to-end release benchmark.\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Raw subprocess/test diagnostics live in the retained logs, never in the primary error.
        print(f"{ERROR['code']}: {ERROR['summaryZh']}\n{ERROR['summaryEn']}", file=sys.stderr)
        print(json.dumps({**ERROR, "technicalCause": "inspect_benchmark_logs"}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
