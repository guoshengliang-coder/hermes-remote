#!/bin/bash
set -euo pipefail
[[ "${CONFIRM_PRODUCTION_DEPLOY:-}" == "mrlgs.net" ]] || { echo "Set CONFIRM_PRODUCTION_DEPLOY=mrlgs.net" >&2; exit 1; }
OLD_SERVICE="apk-server.service"
systemctl stop "$OLD_SERVICE"
rollback() { systemctl stop hermes-release-server.service >/dev/null 2>&1 || true; systemctl start "$OLD_SERVICE"; }
trap rollback ERR
"$(cd "$(dirname "$0")" && pwd)/bootstrap-release-server.sh"
systemctl enable --now hermes-release-server.service
curl --fail --silent --show-error https://mrlgs.net/health >/dev/null
systemctl disable "$OLD_SERVICE" >/dev/null 2>&1 || true
trap - ERR
