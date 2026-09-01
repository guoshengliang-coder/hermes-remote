#!/usr/bin/env bash
# Managed emulator lifecycle for UI verification. Exists because ad-hoc emulator use kept
# failing on this host: QEMU vCPU threads starve under memory pressure ("detected a hanging
# thread 'QEMU2 CPU0 thread'"), guest-side System-UI ANRs appear when the host CPU is pinned
# by Gradle, and killed instances leave crashpad/lock/adb debris that wedges the next boot.
#
# Rules this script enforces (see docs/DESIGN.md §7):
#   1. Free the host first: stop Gradle daemons, kill leftover qemu/crashpad, restart adb.
#   2. Cap guest RAM (-memory 2048) and skip snapshots (stale snapshots resume wedged state).
#   3. Pixel image ONLY. The HONOR foldable AVD is retired outright (owner decision,
#      2026-09-01): dual displays, fragile /sdcard mount and a network stack that never came
#      up made it a pure time sink — do not boot it for any purpose.
#   4. Build BEFORE booting, never concurrently (compile + qemu together is what starves vCPUs).
#   5. If the log shows "hanging thread", kill and relaunch immediately — waiting never helps.
#
# Usage: ./scripts/dev/emulator.sh start|stop|status [avd-name]
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVD="${2:-Pixel_9_API_36_1}"
LOG="${TMPDIR:-/tmp}/hermes-emulator.log"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

cleanup_host() {
  # Rule 1: reclaim memory and clear debris before (re)booting.
  ( cd "$ROOT/android" && ./gradlew --stop >/dev/null 2>&1 ) || true
  pkill -9 -f qemu-system 2>/dev/null || true
  pkill -9 -f crashpad_handler 2>/dev/null || true
  "$ADB" kill-server >/dev/null 2>&1 || true
  "$ADB" start-server >/dev/null 2>&1 || true
  sleep 2
}

start_emulator() {
  cleanup_host
  # Rule 2: capped memory, no snapshots.
  nohup "$EMU" -avd "$AVD" -no-snapshot -no-boot-anim -memory 2048 > "$LOG" 2>&1 &
  echo "booting $AVD (log: $LOG)"
  for _ in $(seq 1 40); do
    # Rule 5: a hanging-thread report means this boot is dead — fail fast.
    if grep -q "hanging thread" "$LOG" 2>/dev/null; then
      echo "QEMU thread hang detected — killing; re-run start (frees more host memory first)" >&2
      pkill -9 -f qemu-system 2>/dev/null || true
      exit 1
    fi
    if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "BOOTED"
      "$ADB" reverse tcp:8787 tcp:8787 >/dev/null 2>&1 || true
      exit 0
    fi
    sleep 6
  done
  echo "boot timed out — check $LOG" >&2
  exit 1
}

case "${1:-}" in
  start) start_emulator ;;
  stop)
    pkill -9 -f qemu-system 2>/dev/null || true
    pkill -9 -f crashpad_handler 2>/dev/null || true
    echo "emulator stopped"
    ;;
  status)
    "$ADB" devices | tail -n +2
    grep -q "hanging thread" "$LOG" 2>/dev/null && echo "WARNING: last boot logged a QEMU thread hang" || true
    ;;
  *) echo "usage: $0 start|stop|status [avd-name]"; exit 2 ;;
esac
