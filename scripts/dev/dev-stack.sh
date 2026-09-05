#!/usr/bin/env bash
# One-command local verification stack for the Android app:
#   mock Hermes (9120)  ->  connector  ->  gateway (8787)  ->  adb reverse
#
# Usage:   ./scripts/dev/dev-stack.sh [start|stop|status]
# The app on the emulator connects to  http://127.0.0.1:8787  with token  dev-app-token.
# All tokens below are local development values, never production credentials.
#
# Ports may be moved out of the way of another project:
#   HERMES_DEV_GATEWAY_PORT=8801 HERMES_DEV_MOCK_PORT=9121 ./scripts/dev/dev-stack.sh start
#
# This script only ever stops processes it started itself. It records each child PID under
# $LOG_DIR and re-checks the recorded command before killing, and it refuses to start when a
# port is held by a foreign process instead of taking the port by force. (Killing whatever
# held 8787 cost an unrelated project its dev server once — 2026-09-04.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="${TMPDIR:-/tmp}/hermes-dev-stack"
mkdir -p "$LOG_DIR"

APP_TOKEN=dev-app-token
CONNECTOR_TOKEN=dev-connector-token
MOCK_PORT="${HERMES_DEV_MOCK_PORT:-9120}"
GATEWAY_PORT="${HERMES_DEV_GATEWAY_PORT:-8787}"

MOCK_MARKER="$ROOT/scripts/dev/mock-hermes-stream.mjs"
GATEWAY_MARKER="$ROOT/gateway/dist/index.js"
CONNECTOR_MARKER="$ROOT/connector/dist/index.js"

# Full command line of a live PID, empty when the process is gone.
pid_command() {
  ps -o command= -p "$1" 2>/dev/null || true
}

# Kill the recorded PID for one component, but only when it is still the process we started.
stop_recorded() {
  local name="$1" marker="$2"
  local pid_file="$LOG_DIR/$name.pid"
  local pid cmd
  [ -f "$pid_file" ] || return 0
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  rm -f "$pid_file"
  case "$pid" in ''|*[!0-9]*) return 0 ;; esac
  cmd="$(pid_command "$pid")"
  if [ -z "$cmd" ]; then
    return 0                      # already gone
  fi
  case "$cmd" in
    *"$marker"*) kill "$pid" 2>/dev/null || true ;;
    *) echo "note: pid $pid is no longer $name ($cmd) — left alone" >&2 ;;
  esac
}

start_recorded() {
  local name="$1" dir="$2"
  shift 2
  ( cd "$dir" && exec nohup "$@" ) > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$LOG_DIR/$name.pid"
}

# Refuse to start when someone else owns the port; clean up a stale instance of our own.
require_port() {
  local port="$1" marker="$2" label="$3" var="$4"
  local pid cmd
  for pid in $(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null || true); do
    cmd="$(pid_command "$pid")"
    case "$cmd" in
      *"$marker"*)
        kill "$pid" 2>/dev/null || true ;;   # our own leftover from an earlier run
      *)
        echo "port $port ($label) is held by pid $pid: $cmd" >&2
        echo "refusing to kill a process this script did not start." >&2
        echo "stop it yourself, or pick another port: $var=<port> $0 start" >&2
        exit 1 ;;
    esac
  done
}

stop_stack() {
  stop_recorded connector "$CONNECTOR_MARKER"
  stop_recorded gateway "$GATEWAY_MARKER"
  stop_recorded mock "$MOCK_MARKER"
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

  sleep 1   # let the processes stopped above release their ports
  require_port "$GATEWAY_PORT" "$GATEWAY_MARKER" gateway HERMES_DEV_GATEWAY_PORT
  require_port "$MOCK_PORT" "$MOCK_MARKER" "mock hermes" HERMES_DEV_MOCK_PORT

  start_recorded mock "$ROOT" \
    env MOCK_HERMES_PORT=$MOCK_PORT node "$MOCK_MARKER"
  start_recorded gateway "$ROOT/gateway" \
    env APP_TOKEN=$APP_TOKEN CONNECTOR_TOKEN=$CONNECTOR_TOKEN PORT=$GATEWAY_PORT \
    node "$GATEWAY_MARKER"
  sleep 1
  start_recorded connector "$ROOT/connector" \
    env GATEWAY_URL=ws://127.0.0.1:$GATEWAY_PORT/v1/connect \
    CONNECTOR_TOKEN=$CONNECTOR_TOKEN HERMES_BASE_URL=http://127.0.0.1:$MOCK_PORT \
    HERMES_BASIC_AUTH_USERNAME=demo HERMES_BASIC_AUTH_PASSWORD=secret \
    node "$CONNECTOR_MARKER"
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
