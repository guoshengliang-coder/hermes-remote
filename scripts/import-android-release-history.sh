#!/bin/bash
set -euo pipefail
[[ $# -ge 2 && $(( $# % 2 )) -eq 0 ]] || { echo "usage: $0 APK METADATA [APK METADATA ...]" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_ROOT="${RELEASE_DATA_ROOT:-/srv/hermes-releases}"
[[ "$DATA_ROOT" == "/srv/hermes-releases" || -n "${ALLOW_TEST_DATA_ROOT:-}" ]] || { echo "invalid data root" >&2; exit 1; }
if [[ -z "${ALLOW_TEST_DATA_ROOT:-}" ]]; then
  command -v flock >/dev/null || { echo "flock is required for production imports" >&2; exit 1; }
  exec 9>"$DATA_ROOT/.publish.kernel.lock"
  flock --timeout 120 9
  export PUBLISH_FLOCK_HELD=1
fi
while [[ $# -gt 0 ]]; do
  RELEASE_DATA_ROOT="$DATA_ROOT" node "$ROOT/deploy/publish-release.mjs" "$1" "$2"
  shift 2
done
