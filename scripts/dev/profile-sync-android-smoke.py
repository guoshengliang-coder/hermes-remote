#!/usr/bin/env python3
"""Run the opt-in live consumer test on an already-installed emulator; never a physical phone.

The host fixture contains disposable account tokens. Send it through stdin into a private,
test-specific directory; never put its contents in argv, output or a world-readable device file.
Build/install the normal debug and androidTest APKs first, as documented in ACCOUNT_DATA_SYNC_P1A.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--fixture", required=True, type=Path)
parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
args = parser.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    parser.error("this isolated fixture runner only accepts an emulator serial")
data = args.fixture.read_bytes()
config = json.loads(data)
origin = urlparse(config["baseUrl"])
if origin.scheme != "https" or origin.hostname != "localhost" or not origin.port or origin.port < 18000 or origin.username:
    parser.error("only an isolated HTTPS localhost stack is allowed")


def adb(*command, **kwargs):
    return subprocess.run([args.adb, "-s", args.serial, *command], check=True, capture_output=True, **kwargs).stdout


if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
    parser.error("target did not identify itself as an emulator")

remote = "files/hg216-profile-sync-live/hg216-profile-sync.json"
reverse = f"tcp:{origin.port}"
before = adb("reverse", "--list").decode()
had_reverse = any(line.split()[1:] == [reverse, reverse] for line in before.splitlines())
# Refuse to replace an existing mapping to some other server.
if not had_reverse and any(len(line.split()) >= 2 and line.split()[1] == reverse for line in before.splitlines()):
    parser.error("the required reverse port belongs to another mapping")
try:
    if not had_reverse:
        adb("reverse", reverse, reverse)
    adb("shell", "run-as com.hermes.remote mkdir -p files/hg216-profile-sync-live")
    adb("shell", f"run-as com.hermes.remote sh -c 'umask 077; cat > {remote}'", input=data)
    output = adb("shell", "am", "instrument", "-w", "-e", "class",
                 "com.hermes.client.data.auth.AccountProfileSyncLiveTest", "-e", "profileSyncLive", "1",
                 "com.hermes.remote.test/com.hermes.client.HiltTestRunner").decode()
    # am instrument can exit 0 even when its JUnit test failed.
    if "OK (1 test)" not in output or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
        raise RuntimeError(f"Android live consumer test did not pass:\n{output}")
    print(f"PASS {args.serial}: real profile/avatar, encrypted store reopen, offline retention, native sign-out")
finally:
    adb("shell", f"run-as com.hermes.remote rm -f {remote}")
    if not had_reverse:
        adb("reverse", "--remove", reverse)
