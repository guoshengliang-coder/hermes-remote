#!/usr/bin/env bash
# Managed emulator lifecycle for UI verification (verification layer L3).
#
# History: ad-hoc emulator use kept failing on the original low-memory host — QEMU vCPU
# threads starve under memory pressure ("detected a hanging thread 'QEMU2 CPU0 thread'"),
# guest System-UI ANRs appear when the host CPU is pinned by Gradle, and killed instances
# leave crashpad/lock/adb debris that wedges the next boot. Those survival rules were
# written for that host and are wrong to apply blindly to a bigger one — and equally wrong
# to drop for a smaller one. So the numbers are no longer constants here: every host-
# dependent decision comes from scripts/dev/android-capabilities.sh at runtime, which tiers
# the host by RAM. A 24 GB machine gets a 4 GB guest and may build while booting; a small
# laptop still gets the original 2 GB, daemon-stop, build-then-boot regime.
#
# Host-independent rules that always apply:
#   1. Skip snapshots — stale snapshots resume wedged state.
#   2. Pixel image ONLY. The HONOR foldable AVD is retired outright (owner decision,
#      2026-09-01): dual displays, a fragile /sdcard mount and a network stack that never
#      came up made it a pure time sink — do not boot it for any purpose.
#   3. If the log shows "hanging thread", kill and relaunch immediately — waiting never helps.
#   4. Every adb call pins an explicit -s serial. An unqualified `adb shell` is wrong twice
#      over: a connected physical phone already reports boot_completed=1 and would make this
#      script claim the emulator is ready, and with two devices attached adb refuses outright.
#      This script pins the emulator's port so its serial is known before it boots, rather
#      than discovering the serial afterwards — discovery cannot distinguish our emulator
#      from one an unrelated project left running.
#
# Usage: ./scripts/dev/emulator.sh start|stop|status [avd-name]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
LOG="${TMPDIR:-/tmp}/hermes-emulator.log"
# Follow the same override dev-stack.sh accepts, so the reverse tunnel lands on the port the
# stack is actually serving when 8787 had to be moved out of another project's way.
GATEWAY_PORT="${HERMES_DEV_GATEWAY_PORT:-8787}"

# Fail loudly if the capability probe cannot run. Without this the eval quietly produces
# nothing and the first HR_* reference dies as "unbound variable", which says nothing about
# the actual cause (script missing, not executable, or a bad HR_FORCE_TIER value).
if ! HR_CAPS="$("$ROOT/scripts/dev/android-capabilities.sh" --export)"; then
  echo "cannot read host capabilities from scripts/dev/android-capabilities.sh" >&2
  exit 4
fi
eval "$HR_CAPS"

# Default to the first non-foldable AVD this host defines (rule 2), not a hard-coded name:
# AVD names differ per machine, and a name that exists on no host made a missing AVD look
# like a boot failure.
default_avd() {
  # `|| true`: if every AVD is filtered out, grep exits 1 and pipefail would kill the
  # script before it can report that cleanly below.
  echo "$HR_EMU_AVDS" | tr ',' '\n' | grep -vi -E "honor|fold" | head -1 || true
}

# Resolving an AVD is a precondition of `start` ONLY. `stop` must still be able to clear
# leftover processes and `status` must still be able to explain why L3 is unavailable —
# gating them on an AVD existing would make the diagnostic command refuse to diagnose.
require_avd() {
  if [ "$HR_EMU_AVAILABLE" != "1" ]; then
    echo "L3 unavailable on this host:" >&2
    "$ROOT/scripts/dev/android-capabilities.sh" | sed -n '/^L3/,$p' >&2
    exit 3
  fi
  AVD="${AVD_ARG:-$(default_avd)}"
  if [ -z "$AVD" ]; then
    echo "no usable AVD on this host (available: ${HR_EMU_AVDS:-none})" >&2
    exit 3
  fi
  case "$AVD" in
    *[Hh][Oo][Nn][Oo][Rr]*|*[Ff][Oo][Ll][Dd]*)
      echo "refusing $AVD — the HONOR foldable AVD is retired (owner decision 2026-09-01)" >&2
      exit 2 ;;
  esac
}
AVD_ARG="${2:-}"

# A fixed port makes the serial deterministic (emulator-<port>) before the guest exists,
# which is what lets every later call pin -s.
#
# This script is single-instance by design: cleanup_host() kills every qemu process before
# booting, so starting an emulator replaces any running one rather than joining it. The
# port scan is therefore not multi-instance support — it exists because adb can still list
# a just-killed emulator for a second or two, and reusing that serial confuses the boot
# probe. To run a size/density matrix, boot the AVDs one at a time.
pick_port() {
  for p in 5554 5556 5558 5560; do
    "$ADB" devices 2>/dev/null | grep -q "emulator-$p" || { echo "$p"; return; }
  done
  echo "no free emulator port in 5554-5560" >&2
  exit 1
}

cleanup_host() {
  # Only reclaim what this host actually needs reclaiming. On a high tier the Gradle daemon
  # is left alone (stopping it costs a daemon restart every round for no benefit), and adb
  # is never restarted blindly: `adb kill-server` also drops the reverse tunnels of an
  # attached physical device, which breaks a running dev-stack session.
  if [ "$HR_EMU_STOP_DAEMON" = "1" ]; then
    ( cd "$ROOT/android" && ./gradlew --stop >/dev/null 2>&1 ) || true
  fi
  pkill -9 -f qemu-system 2>/dev/null || true
  pkill -9 -f crashpad_handler 2>/dev/null || true
  if [ -z "$HR_DEVICE_SERIAL" ]; then
    "$ADB" kill-server >/dev/null 2>&1 || true
    "$ADB" start-server >/dev/null 2>&1 || true
    sleep 2
  fi
}

start_emulator() {
  require_avd
  # Small hosts must not have a build and qemu resident at once — that is what starves
  # vCPUs. cleanup_host() below already stops the daemon for those tiers; do not refuse to
  # boot merely because a daemon exists. A Gradle daemon idles in the background for hours
  # after a build, so treating its presence as "a build is running" would make every
  # mid/low host unable to start an emulator until the user stopped it by hand.
  [ "$HR_EMU_ALLOW_CONCURRENT" = "1" ] || \
    echo "tier=$HR_HOST_TIER: stopping the Gradle daemon first (build and boot are not concurrent here)"
  cleanup_host
  local port serial
  port="$(pick_port)"
  serial="emulator-$port"
  : > "$LOG"
  nohup "$EMU" -avd "$AVD" -port "$port" -no-snapshot -no-boot-anim \
    -memory "$HR_EMU_RAM_MB" > "$LOG" 2>&1 &
  echo "booting $AVD as $serial (tier=$HR_HOST_TIER, guest ${HR_EMU_RAM_MB}MB, log: $LOG)"
  for _ in $(seq 1 40); do
    # Rule 3: a hanging-thread report means this boot is dead — fail fast.
    if grep -q "hanging thread" "$LOG" 2>/dev/null; then
      echo "QEMU thread hang detected — killing; re-run start (frees more host memory first)" >&2
      pkill -9 -f qemu-system 2>/dev/null || true
      exit 1
    fi
    if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "BOOTED $serial"
      "$ADB" -s "$serial" reverse "tcp:$GATEWAY_PORT" "tcp:$GATEWAY_PORT" >/dev/null 2>&1 || true
      # Name every attached phone, not just the default one: the point of the note is that
      # an unqualified adb command is now ambiguous, and that gets worse with each device.
      [ -n "$HR_DEVICE_SERIALS" ] && \
        echo "note: physical device(s) also attached —$(printf ' %s' $HR_DEVICE_SERIALS); pass -s explicitly"
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
    # Ask each emulator to exit before resorting to signals, so the physical device's adb
    # connection and any reverse tunnels survive. Guarded on adb being present: stop is a
    # cleanup command and must still kill leftover qemu processes on a host where the SDK
    # path is wrong or platform-tools is missing — without the guard, pipefail turns that
    # case into a silent exit 1 that cleans up nothing.
    if [ -x "$ADB" ]; then
      "$ADB" devices 2>/dev/null | awk '/^emulator-/{print $1}' | while read -r s; do
        "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
      done
      sleep 2
    fi
    pkill -9 -f qemu-system 2>/dev/null || true
    pkill -9 -f crashpad_handler 2>/dev/null || true
    echo "emulator stopped"
    ;;
  status)
    "$ROOT/scripts/dev/android-capabilities.sh"
    grep -q "hanging thread" "$LOG" 2>/dev/null && echo "WARNING: last boot logged a QEMU thread hang" || true
    ;;
  *) echo "usage: $0 start|stop|status [avd-name]"; exit 2 ;;
esac
