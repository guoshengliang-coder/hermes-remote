#!/usr/bin/env bash
# Detect what Android verification this host can actually perform, at runtime.
#
# Why this exists: the verification rules are shared through Git, but the hosts are not
# comparable. A 24 GB Mac mini can run a build and an emulator concurrently; a smaller
# laptop cannot, and CI has neither emulator nor device. Hard-coding one host's numbers
# into AGENTS.md, docs/DESIGN.md or gradle.properties silently breaks every other host.
# So the shared files describe only WHAT must be verified (the L1/L2/L3 contract) and this
# script decides HOW on the host it is running on. Nothing here needs per-machine setup.
#
# Layers (see AGENTS.md "Android" verification and docs/DESIGN.md §7):
#   L1  JVM + Roborazzi screenshots      always available, also the only layer CI can run
#   L2  attached physical device         real look-and-feel, vendor ROM behaviour
#   L3  emulator                         targetSdk-level platform behaviour, clean state, matrix
#
# Usage:
#   ./scripts/dev/android-capabilities.sh            human-readable report (paste into handoff)
#   ./scripts/dev/android-capabilities.sh --export   HR_* shell assignments for `eval`
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"

# ---- host tier -------------------------------------------------------------------------
# Memory is the constraint that actually decided the old rules: QEMU vCPU threads starve
# under host memory pressure. Tier the host instead of assuming one machine's headroom.
if [ "$(uname -s)" = "Darwin" ]; then
  HR_HOST_RAM_GB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
  HR_HOST_CPUS=$(sysctl -n hw.ncpu)
  HR_HOST_MODEL="$(sysctl -n hw.model 2>/dev/null || echo unknown)"
elif [ -r /proc/meminfo ]; then
  HR_HOST_RAM_GB=$(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1024 / 1024 ))
  HR_HOST_CPUS=$(nproc 2>/dev/null || echo 1)
  HR_HOST_MODEL="linux"
else
  HR_HOST_RAM_GB=0
  HR_HOST_CPUS=1
  HR_HOST_MODEL="unknown"
fi

# HR_FORCE_TIER overrides the measured tier. Two uses: verifying the low/mid code paths
# from a high-tier machine (otherwise those branches are only ever exercised on whichever
# host happens to be small, which is how they rot), and temporarily throttling a host that
# is busy with something else.
if [ -n "${HR_FORCE_TIER:-}" ]; then
  case "$HR_FORCE_TIER" in
    high|mid|low) HR_HOST_TIER="$HR_FORCE_TIER" ;;
    *) echo "HR_FORCE_TIER must be high, mid or low (got '$HR_FORCE_TIER')" >&2; exit 2 ;;
  esac
elif [ "$HR_HOST_RAM_GB" -ge 24 ]; then
  HR_HOST_TIER=high
elif [ "$HR_HOST_RAM_GB" -ge 16 ]; then
  HR_HOST_TIER=mid
else
  HR_HOST_TIER=low
fi

case "$HR_HOST_TIER" in
  high)                      # emulator may boot while Gradle builds; roomy guest
    HR_EMU_RAM_MB=4096; HR_EMU_ALLOW_CONCURRENT=1; HR_EMU_STOP_DAEMON=0 ;;
  mid)                       # roomier guest, but still never build and boot at once
    HR_EMU_RAM_MB=3072; HR_EMU_ALLOW_CONCURRENT=0; HR_EMU_STOP_DAEMON=1 ;;
  low)                       # the original 2026-09-01 survival rules, unchanged
    HR_EMU_RAM_MB=2048; HR_EMU_ALLOW_CONCURRENT=0; HR_EMU_STOP_DAEMON=1 ;;
esac

# ---- project contract ------------------------------------------------------------------
HR_TARGET_SDK="$(sed -n 's/^ *targetSdk *= *\([0-9]*\).*/\1/p' \
  "$ROOT/android/app/build.gradle.kts" 2>/dev/null | head -1)"
HR_MIN_SDK="$(sed -n 's/^ *minSdk *= *\([0-9]*\).*/\1/p' \
  "$ROOT/android/app/build.gradle.kts" 2>/dev/null | head -1)"
# 0 means the literal could not be read — e.g. the build file moved to a version catalog
# (`targetSdk = libs.versions.target.get()`). Say so: the targetSdk gap check silently
# passes when this is 0, which would quietly retire the very warning it exists to raise.
HR_TARGET_SDK="${HR_TARGET_SDK:-0}"
HR_MIN_SDK="${HR_MIN_SDK:-0}"
HR_SDK_PARSE_WARNING=""
[ "$HR_TARGET_SDK" = "0" ] && \
  HR_SDK_PARSE_WARNING="could not read targetSdk from android/app/build.gradle.kts — the SDK gap check is disabled; fix the parser in this script"

# ---- L3: emulator capability -----------------------------------------------------------
# The guest ABI follows the host CPU, not the project: an Apple-silicon host needs
# arm64-v8a, an Intel host x86_64. Hard-coding either one sends the other host on a long
# download that ends in an image its emulator refuses to boot.
case "$(uname -m)" in
  arm64|aarch64) HR_EMU_ABI=arm64-v8a ;;
  *)             HR_EMU_ABI=x86_64 ;;
esac

HR_EMU_AVAILABLE=0
HR_EMU_AVDS=""
if [ -x "$EMU" ]; then
  # `|| true`: with no AVD defined, grep exits 1 and pipefail would abort the whole report
  # — which is exactly the state this script has to be able to describe.
  HR_EMU_AVDS="$("$EMU" -list-avds 2>/dev/null | grep -v '^$' | tr '\n' ',' | sed 's/,$//' || true)"
  [ -n "$HR_EMU_AVDS" ] && HR_EMU_AVAILABLE=1
fi

# ---- attached devices ------------------------------------------------------------------
# Split by serial: emulators are always `emulator-NNNN`, everything else is physical.
# Every consumer must pin -s explicitly; with a device permanently attached, a bare
# `adb shell` breaks the moment an emulator joins.
HR_DEVICE_SERIAL=""
HR_DEVICE_MODEL=""
HR_DEVICE_SDK=""
HR_DEVICE_BRAND=""
HR_EMU_SERIAL=""
HR_EMU_SDK=""
HR_DEVICE_PROBLEM=""
if [ -x "$ADB" ]; then
  while read -r serial state _rest; do
    # A phone in `unauthorized` (the RSA prompt was never accepted, common after a reboot)
    # or `offline` state is attached but unusable. Reporting it as "no device" would send
    # the reader looking for a cable problem instead of the dialog on the phone.
    case "${state:-}" in
      device) ;;
      unauthorized|offline)
        HR_DEVICE_PROBLEM="$serial is $state — accept the USB-debugging prompt on the device"
        continue ;;
      *) continue ;;
    esac
    case "$serial" in
      emulator-*)
        if [ -z "$HR_EMU_SERIAL" ]; then
          HR_EMU_SERIAL="$serial"
          HR_EMU_SDK="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
        fi
        ;;
      *)
        if [ -z "$HR_DEVICE_SERIAL" ]; then
          HR_DEVICE_SERIAL="$serial"
          HR_DEVICE_MODEL="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
          HR_DEVICE_SDK="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
          HR_DEVICE_BRAND="$("$ADB" -s "$serial" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')"
        fi
        ;;
    esac
  done <<EOF
$("$ADB" devices 2>/dev/null | tail -n +2)
EOF
fi

HR_L1_STATUS=available     # Roborazzi runs on the JVM; no device, no host tier requirement
[ -n "$HR_DEVICE_SERIAL" ] && HR_L2_STATUS=available || HR_L2_STATUS=unavailable
[ "$HR_EMU_AVAILABLE" = "1" ] && HR_L3_STATUS=available || HR_L3_STATUS=unavailable

# A physical device below targetSdk cannot exercise the platform behaviour the app opts
# into. This is the gap L3 exists to cover — name it explicitly so a handoff cannot claim
# blanket "device verified" while the targetSdk paths were never executed anywhere.
HR_TARGETSDK_GAP=0
if [ -n "$HR_DEVICE_SDK" ] && [ "$HR_TARGET_SDK" -gt 0 ] \
   && [ "$HR_DEVICE_SDK" -lt "$HR_TARGET_SDK" ]; then
  HR_TARGETSDK_GAP=1
fi

if [ "${1:-}" = "--export" ]; then
  for v in HR_HOST_RAM_GB HR_HOST_CPUS HR_HOST_MODEL HR_HOST_TIER \
           HR_EMU_RAM_MB HR_EMU_ALLOW_CONCURRENT HR_EMU_STOP_DAEMON \
           HR_EMU_AVAILABLE HR_EMU_AVDS HR_EMU_SERIAL HR_EMU_SDK HR_EMU_ABI \
           HR_DEVICE_SERIAL HR_DEVICE_MODEL HR_DEVICE_SDK HR_DEVICE_BRAND \
           HR_TARGET_SDK HR_MIN_SDK HR_TARGETSDK_GAP HR_DEVICE_PROBLEM \
           HR_L1_STATUS HR_L2_STATUS HR_L3_STATUS; do
    eval "printf '%s=%q\n' \"$v\" \"\${$v}\""
  done
  exit 0
fi

echo "host       : $HR_HOST_MODEL, ${HR_HOST_RAM_GB}GB, ${HR_HOST_CPUS} cpus -> tier=$HR_HOST_TIER"
echo "project    : minSdk=$HR_MIN_SDK targetSdk=$HR_TARGET_SDK"
echo
echo "L1 jvm+roborazzi : $HR_L1_STATUS"
if [ "$HR_L2_STATUS" = available ]; then
  echo "L2 device        : available ($HR_DEVICE_BRAND $HR_DEVICE_MODEL, SDK $HR_DEVICE_SDK, $HR_DEVICE_SERIAL)"
elif [ -n "$HR_DEVICE_PROBLEM" ]; then
  echo "L2 device        : unavailable ($HR_DEVICE_PROBLEM)"
else
  echo "L2 device        : unavailable (no physical device attached)"
fi
if [ "$HR_L3_STATUS" = available ]; then
  echo "L3 emulator      : available (avds: $HR_EMU_AVDS; guest ${HR_EMU_RAM_MB}MB, concurrent-build=$HR_EMU_ALLOW_CONCURRENT)"
  [ -n "$HR_EMU_SERIAL" ] && echo "                   running: $HR_EMU_SERIAL (SDK $HR_EMU_SDK)"
else
  if [ ! -x "$EMU" ]; then
    echo "L3 emulator      : unavailable (emulator package not installed)"
    echo "                   sdkmanager --install emulator '<image>'"
  else
    echo "L3 emulator      : unavailable (emulator installed but no AVD defined)"
    echo "                   avdmanager create avd -n <name> -k '<image>'"
  fi
  # Do not print a guessed package coordinate: image packages carry a minor version
  # (android-37.0, android-36.1), so the obvious "android-$targetSdk" spelling is often not
  # a real package and the install fails with a bare "not found". Point at the lookup.
  echo "                   resolve <image> first (names carry a minor version):"
  echo "                     sdkmanager --list | grep 'system-images.*$HR_EMU_ABI'"
fi
if [ -n "$HR_SDK_PARSE_WARNING" ]; then
  echo
  echo "WARNING: $HR_SDK_PARSE_WARNING"
fi
if [ "$HR_TARGETSDK_GAP" = "1" ]; then
  echo
  echo "GAP: device SDK $HR_DEVICE_SDK < targetSdk $HR_TARGET_SDK — targetSdk-gated platform"
  echo "     behaviour cannot be verified on this device. Use L3, or declare it unverified."
fi
