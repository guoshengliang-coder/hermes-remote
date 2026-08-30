#!/bin/bash
set -euo pipefail
[[ "${CONFIRM_PRODUCTION_DEPLOY:-}" == "mrlgs.net" ]] || { echo "Set CONFIRM_PRODUCTION_DEPLOY=mrlgs.net" >&2; exit 1; }
OLD_SERVICE="apk-server.service"
systemctl stop "$OLD_SERVICE"
rollback() { systemctl stop hermes-release-server.service >/dev/null 2>&1 || true; systemctl start "$OLD_SERVICE"; }
trap rollback ERR
"$(cd "$(dirname "$0")" && pwd)/bootstrap-release-server.sh"
systemctl enable --now hermes-release-server.service
ready=false
for _ in $(seq 1 40); do
  if curl --fail --silent --show-error --connect-timeout 1 https://mrlgs.net/health >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 0.25
done
[[ "$ready" == true ]] || { echo "release server readiness check timed out" >&2; exit 1; }
systemctl disable "$OLD_SERVICE" >/dev/null 2>&1 || true
trap - ERR
