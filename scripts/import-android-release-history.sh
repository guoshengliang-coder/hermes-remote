#!/bin/bash
set -euo pipefail
[[ $# -ge 2 && $(( $# % 2 )) -eq 0 ]] || { echo "usage: $0 APK METADATA [APK METADATA ...]" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_ROOT="${RELEASE_DATA_ROOT:-/srv/hermes-releases}"
[[ "$DATA_ROOT" == "/srv/hermes-releases" || -n "${ALLOW_TEST_DATA_ROOT:-}" ]] || { echo "invalid data root" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  node "$ROOT/deploy/publish-release.mjs" "$1" "$2"
  shift 2
done
