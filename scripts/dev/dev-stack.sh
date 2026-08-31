#!/usr/bin/env bash
# One-command local verification stack for the Android app:
#   mock Hermes (9120)  ->  connector  ->  gateway (8787)  ->  adb reverse
#
# Usage:   ./scripts/dev/dev-stack.sh [start|stop|status]
# The app on the emulator connects to  http://127.0.0.1:8787  with token  dev-app-token.
# All tokens below are local development values, never production credentials.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="${TMPDIR:-/tmp}/hermes-dev-stack"
mkdir -p "$LOG_DIR"

APP_TOKEN=dev-app-token
CONNECTOR_TOKEN=dev-connector-token
MOCK_PORT=9120
GATEWAY_PORT=8787

stop_stack() {
  pkill -f "scripts/dev/mock-hermes-stream.mjs" 2>/dev/null || true
  for port in "$GATEWAY_PORT" "$MOCK_PORT"; do
    lsof -ti ":$port" 2>/dev/null | xargs kill 2>/dev/null || true
  done
  pkill -f "$ROOT/connector/dist/index.js" 2>/dev/null || true
  echo "dev stack stopped"
}

status_stack() {
  curl -s "http://127.0.0.1:$GATEWAY_PORT/api/status" -H "x-hermes-session-token: $APP_TOKEN" || echo "gateway not responding"
  echo
}

start_stack() {
  stop_stack
  ( cd "$ROOT/gateway" && [ -f dist/index.js ] ) || { echo "gateway/dist missing — run npm run build"; exit 1; }
  ( cd "$ROOT/connector" && [ -f dist/index.js ] ) || { echo "connector/dist missing — run npm run build"; exit 1; }

  MOCK_HERMES_PORT=$MOCK_PORT nohup node "$ROOT/scripts/dev/mock-hermes-stream.mjs" > "$LOG_DIR/mock.log" 2>&1 &
  ( cd "$ROOT/gateway" && APP_TOKEN=$APP_TOKEN CONNECTOR_TOKEN=$CONNECTOR_TOKEN PORT=$GATEWAY_PORT \
      nohup node dist/index.js > "$LOG_DIR/gateway.log" 2>&1 & )
  sleep 1
  ( cd "$ROOT/connector" && GATEWAY_URL=ws://127.0.0.1:$GATEWAY_PORT/v1/connect \
      CONNECTOR_TOKEN=$CONNECTOR_TOKEN HERMES_BASE_URL=http://127.0.0.1:$MOCK_PORT \
      HERMES_BASIC_AUTH_USERNAME=demo HERMES_BASIC_AUTH_PASSWORD=secret \
      nohup node dist/index.js > "$LOG_DIR/connector.log" 2>&1 & )
  sleep 2

  ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  if [ -x "$ADB" ] && "$ADB" get-state >/dev/null 2>&1; then
    "$ADB" reverse "tcp:$GATEWAY_PORT" "tcp:$GATEWAY_PORT" && echo "adb reverse tcp:$GATEWAY_PORT ready"
  else
    echo "note: no adb device — skip reverse (run again after the emulator boots)"
  fi
  status_stack
  echo "logs: $LOG_DIR/{mock,gateway,connector}.log"
}

case "${1:-start}" in
  start) start_stack ;;
  stop) stop_stack ;;
  status) status_stack ;;
  *) echo "usage: $0 [start|stop|status]"; exit 1 ;;
esac
