"""Measure complete Swift gates on disposable, freshly checked-out GitHub runners."""
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import plistlib
import re
import subprocess
import sys
import time

ERROR = {
    "code": "HR-CI-001",
    "summaryZh": "构建对比测试未完成，请查看测试日志并修正后重试。",
    "summaryEn": "The build comparison did not complete. Review the test logs, fix the cause and retry.",
    "retryable": True, "recoveryAction": "inspect_benchmark_logs_and_retry",
    "stage": "swift_cache_benchmark",
}
COMMANDS = {"unit": ["npm", "run", "desktop:test"], "app": ["npm", "run", "desktop:app"]}
CACHE_PATHS = ("desktop/.build", "desktop/build/swift", "desktop/build/module-cache", "desktop/build/swiftpm-module-cache")
IDENTITY = ("sourceCommit", "desktopTree", "swift", "xcode", "osVersion", "architecture", "checkoutPathHash")


def test_counts(text):
    rows = re.findall(r"Executed (\d+) tests?, with (\d+) failures \((\d+) unexpected\)", text)
    if not rows:
        raise ValueError("missing_test_evidence")
    count, failures, unexpected = map(int, rows[-1])
    if count < 1 or failures or unexpected:
        raise ValueError("failing_test_evidence")
    return {"tests": count, "failures": failures, "unexpected": unexpected}


def compare(cold, cached):
    if cold["variant"] != "cold" or cached["variant"] != "cached" or cached["cacheHit"] is not True:
        raise ValueError("invalid_pair")
    for key in (*IDENTITY, "mode", "evidence"):
        if cold[key] != cached[key]:
            raise ValueError("incomparable_evidence")
    for row in (cold, cached):
        if row["returncode"] != 0 or not math.isfinite(row["totalSeconds"]) or row["totalSeconds"] <= 0:
            raise ValueError("invalid_sample")
    saved = cold["totalSeconds"] - cached["totalSeconds"]
    return {"mode": cold["mode"], "coldSeconds": cold["totalSeconds"], "cachedSeconds": cached["totalSeconds"],
            "savedSeconds": round(saved, 3), "savedPercent": round(100 * saved / cold["totalSeconds"], 2)}


def outside_checkout(root, output):
    root = root.resolve()
    output = output.resolve()
    if output == root or root in output.parents:
        raise ValueError("output_must_be_outside_checkout")
    return output


def capture(root, args):
    return subprocess.check_output(args, cwd=root, text=True, stderr=subprocess.DEVNULL, timeout=30).strip()


def main(args):
    root = Path(__file__).resolve().parents[2]
    if args[0] == "start" and len(args) == 2:
        output = outside_checkout(root, Path(args[1]))
        output.mkdir(parents=True, exist_ok=True)
        (output / "start.json").write_text(json.dumps({"monotonic": time.monotonic()}))
        return
    if len(args) != 5 or args[0] != "run":
        raise ValueError("invalid_arguments")
    _, mode, variant, location, hit = args
    if mode not in COMMANDS or variant not in ("seed", "cold", "cached") or hit not in ("true", "false"):
        raise ValueError("invalid_arguments")
    if variant == "cached" and hit != "true":
        raise ValueError("cache_not_restored")
    output = outside_checkout(root, Path(location))
    if capture(root, ["git", "status", "--porcelain"]):
        raise ValueError("checkout_not_clean")
    start = json.loads((output / "start.json").read_text())["monotonic"]
    if not isinstance(start, (int, float)) or not math.isfinite(start) or not 0 <= time.monotonic() - start < 1800:
        raise ValueError("invalid_start")
    row = {"mode": mode, "variant": variant, "cacheHit": hit == "true",
           "sourceCommit": capture(root, ["git", "rev-parse", "HEAD"]),
           "desktopTree": capture(root, ["git", "rev-parse", "HEAD:desktop"]),
           "swift": capture(root, ["xcrun", "swift", "--version"]),
           "xcode": capture(root, ["xcodebuild", "-version"]),
           "osVersion": platform.mac_ver()[0], "architecture": platform.machine(),
           "checkoutPathHash": hashlib.sha256(str(root).encode()).hexdigest()}
    command_start = time.monotonic()
    with (output / "command.log").open("w") as stream:
        result = subprocess.run(COMMANDS[mode], cwd=root, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
    row["commandSeconds"] = round(time.monotonic() - command_start, 3)
    row["returncode"] = result.returncode
    if result.returncode:
        raise ValueError("command_failed")
    if mode == "unit":
        row["evidence"] = test_counts((output / "command.log").read_text())
    else:
        app = root / "desktop/build/Hermes Go Desktop.app"
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        with (app / "Contents/Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        row["evidence"] = {key: info[key] for key in ("CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion")}
        if not all(isinstance(value, str) and value for value in row["evidence"].values()):
            raise ValueError("invalid_bundle_identity")
    row["totalSeconds"] = round(time.monotonic() - start, 3)
    row["cacheBytes"] = sum(p.stat().st_size for directory in CACHE_PATHS
                            for p in (root / directory).rglob("*") if p.is_file() and not p.is_symlink())
    (output / "result.json").write_text(json.dumps(row, indent=2) + "\n")
    print(json.dumps(row))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
            stream.write(f"### Swift cache: {mode} / {variant}\n\nFull command: {row['commandSeconds']}s; including restore/setup: {row['totalSeconds']}s.\n")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Exception:
        print(f"{ERROR['code']}: {ERROR['summaryZh']}\n{ERROR['summaryEn']}", file=sys.stderr)
        print(json.dumps({**ERROR, "technicalCause": "inspect_benchmark_logs"}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
