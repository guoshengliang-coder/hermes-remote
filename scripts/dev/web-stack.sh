#!/usr/bin/env bash
# One-command local stack for the Web app (web/), in account mode over HTTPS:
#   Postgres (18434) · mock Hermes (18120) · Gateway over TLS (18443, serving web/dist at /app/)
#   · account-mode Connector (FILES_ROOT in the state dir)
#
# Usage:   ./scripts/dev/web-stack.sh up|down|status|code
#   up      fresh database, sign the dev email in once, bind a mock Mac, start everything
#   code    print the latest email login code (the Gateway's Resend call is captured locally)
#   HR_WEB_STACK_LEGACY_PROTOCOL=1 ./scripts/dev/web-stack.sh up   # old approval/clarify events
#
# Open https://localhost:18443/app/ and sign in with dev@example.test; `code` prints the code.
# Trust the stack's CA in an iOS simulator with:
#   xcrun simctl keychain booted add-root-cert "$STATE/tls/ca.pem"
#
# All ports are in the 18xxx range and all state lives in $HR_WEB_STACK_STATE (a temp dir):
# nothing here touches ~/.hermes, a running Hermes, or any port below 18000. The Connector is
# always pointed at the mock explicitly — its built-in default is the real local Hermes (9119).
# Like dev-stack.sh, this only ever stops processes it started, and refuses foreign ports.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STATE="${HR_WEB_STACK_STATE:-${TMPDIR:-/tmp}/hermes-web-stack}"
mkdir -p "$STATE"
# The Connector insists on a canonical credential path, and macOS's TMPDIR sits under /var ->
# /private/var (with a trailing slash), so resolve it once.
STATE="$(cd "$STATE" && pwd -P)"
GATEWAY_PORT="${HR_WEB_STACK_GATEWAY_PORT:-18443}"
MOCK_PORT="${HR_WEB_STACK_MOCK_PORT:-18120}"
PG_PORT="${HR_WEB_STACK_PG_PORT:-18434}"
EMAIL="${HR_WEB_STACK_EMAIL:-dev@example.test}"
DEVICE_ID="${HR_WEB_STACK_DEVICE_ID:-dev-mac}"
ORIGIN="https://localhost:$GATEWAY_PORT"
PG_BIN="${HR_WEB_STACK_PG_BIN:-$(dirname "$(command -v pg_ctl || echo /opt/homebrew/bin/pg_ctl)")}"
OPENSSL="${HR_WEB_STACK_OPENSSL:-/opt/homebrew/opt/openssl@3/bin/openssl}"
LSOF="$(command -v lsof || echo /usr/sbin/lsof)"
DATABASE_URL="postgres://hermes_web@127.0.0.1:$PG_PORT/hermes_web"
WEB_DIR="${HR_WEB_STACK_WEB_DIR:-$ROOT/web/dist}"

MOCK_MARKER="$ROOT/scripts/dev/mock-hermes-stream.mjs"
GATEWAY_MARKER="$ROOT/gateway/dist/index.js"
CONNECTOR_MARKER="$ROOT/connector/dist/index.js"

for port in "$GATEWAY_PORT" "$MOCK_PORT" "$PG_PORT"; do
  if [ "$port" -lt 18000 ] || [ "$port" = 9119 ]; then
    echo "refusing port $port: web-stack ports stay in the 18xxx range" >&2
    exit 1
  fi
done

pid_command() { ps -o command= -p "$1" 2>/dev/null || true; }

stop_recorded() {
  local name="$1" marker="$2" pid cmd
  local pid_file="$STATE/$name.pid"
  [ -f "$pid_file" ] || return 0
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  rm -f "$pid_file"
  case "$pid" in ''|*[!0-9]*) return 0 ;; esac
  cmd="$(pid_command "$pid")"
  [ -z "$cmd" ] && return 0
  case "$cmd" in
    *"$marker"*) kill "$pid" 2>/dev/null || true ;;
    *) echo "note: pid $pid is no longer $name ($cmd) — left alone" >&2 ;;
  esac
}

start_recorded() {
  local name="$1" dir="$2"
  shift 2
  ( cd "$dir" && exec nohup "$@" ) > "$STATE/$name.log" 2>&1 &
  echo $! > "$STATE/$name.pid"
}

require_port() {
  local port="$1" label="$2" pid
  for pid in $("$LSOF" -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null || true); do
    echo "port $port ($label) is held by pid $pid: $(pid_command "$pid")" >&2
    echo "refusing to take it; stop that process or set HR_WEB_STACK_*_PORT" >&2
    exit 1
  done
}

wait_for() {
  local label="$1" check="$2" i
  for i in $(seq 1 50); do
    if eval "$check" >/dev/null 2>&1; then return 0; fi
    sleep 0.2
  done
  echo "$label did not come up; see $STATE/*.log" >&2
  exit 1
}

make_tls() {
  local dir="$STATE/tls"
  [ -f "$dir/server.pem" ] && return 0
  mkdir -p "$dir"
  "$OPENSSL" req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=Hermes Web Stack Dev CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -keyout "$dir/ca.key" -out "$dir/ca.pem" 2>/dev/null
  "$OPENSSL" req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
    -keyout "$dir/server.key" -out "$dir/server.csr" 2>/dev/null
  printf '%s\n' "subjectAltName=DNS:localhost,IP:127.0.0.1" "extendedKeyUsage=serverAuth" \
    "basicConstraints=CA:FALSE" > "$dir/server.ext"
  "$OPENSSL" x509 -req -in "$dir/server.csr" -CA "$dir/ca.pem" -CAkey "$dir/ca.key" \
    -CAcreateserial -days 365 -extfile "$dir/server.ext" -out "$dir/server.pem" 2>/dev/null
  chmod 600 "$dir"/*.key
}

start_postgres() {
  if [ ! -f "$STATE/pgdata/PG_VERSION" ]; then
    "$PG_BIN/initdb" -D "$STATE/pgdata" -U hermes_web --auth=trust -E UTF8 > "$STATE/initdb.log" 2>&1
  fi
  if ! "$PG_BIN/pg_ctl" -D "$STATE/pgdata" status >/dev/null 2>&1; then
    require_port "$PG_PORT" postgres
    "$PG_BIN/pg_ctl" -D "$STATE/pgdata" -l "$STATE/postgres.log" \
      -o "-p $PG_PORT -k $STATE -c listen_addresses=127.0.0.1" start >/dev/null
  fi
  PGOPTIONS="-c client_min_messages=warning" "$PG_BIN/psql" -q -h 127.0.0.1 -p "$PG_PORT" -U hermes_web -d postgres \
    -c "DROP DATABASE IF EXISTS hermes_web" -c "CREATE DATABASE hermes_web"
  local migration
  for migration in "$ROOT"/gateway/migrations/[0-9][0-9][0-9]_*.sql; do
    PGOPTIONS="-c client_min_messages=warning" "$PG_BIN/psql" -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PORT" -U hermes_web -d hermes_web \
      -f "$migration" > /dev/null
  done
}

stop_stack() {
  stop_recorded connector "$CONNECTOR_MARKER"
  stop_recorded gateway "$GATEWAY_MARKER"
  stop_recorded mock "$MOCK_MARKER"
  if [ -f "$STATE/pgdata/PG_VERSION" ] && "$PG_BIN/pg_ctl" -D "$STATE/pgdata" status >/dev/null 2>&1; then
    "$PG_BIN/pg_ctl" -D "$STATE/pgdata" stop -m fast >/dev/null
  fi
  echo "web stack stopped"
}

# Every run starts from a fresh database, so the Gateway's keys are simply generated per run: nothing
# that looks like a credential is written into this file (the secret scanner reads it too).
random_hex() { "$OPENSSL" rand -hex "$1"; }

gateway_env() {
  cat <<EOF
HOST=127.0.0.1
PORT=$GATEWAY_PORT
TLS_CERT_FILE=$STATE/tls/server.pem
TLS_KEY_FILE=$STATE/tls/server.key
APP_TOKEN=web-stack-legacy-app-token
CONNECTOR_TOKEN=web-stack-legacy-connector-token
DEFAULT_DEVICE_ID=$DEVICE_ID
LIFECYCLE_EVENT_STORE_FILE=$STATE/lifecycle-events.json
ACCOUNT_AUTH_ENABLED=1
ACCOUNT_BINDING_ENABLED=1
ACCOUNT_MULTI_DEVICE_ENABLED=1
ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1
ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=1
ACCOUNT_EMAIL_OTP_ENABLED=1
ACCOUNT_EMAIL_OTP_HASH_KEY=$(random_hex 24)
ACCOUNT_EMAIL_OTP_ISSUER=$ORIGIN
ACCOUNT_RESEND_WEBHOOK_ENABLED=1
ACCOUNT_RESEND_WEBHOOK_SECRET=whsec_$("$OPENSSL" rand -base64 32)
ACCOUNT_RESEND_API_KEY=re_local_$(random_hex 8)
ACCOUNT_EMAIL_FROM=Hermes GO Dev <dev@example.invalid>
ACCOUNT_WEB_SESSION_ENABLED=1
ACCOUNT_WEB_ORIGIN=$ORIGIN
ACCOUNT_WEB_DEVICE_ACCESS_ENABLED=1
ACCOUNT_GATEWAY_ORIGIN=$ORIGIN
ACCOUNT_DATABASE_URL=$DATABASE_URL
ACCOUNT_TOKEN_HASH_KEY=$(random_hex 24)
WEB_APP_ENABLED=1
WEB_APP_DIR=$WEB_DIR
HR_DEV_EMAIL_SINK=$STATE/login-codes.jsonl
GATEWAY_LOG_LEVEL=info
EOF
}

start_stack() {
  mkdir -p "$STATE"
  stop_stack >/dev/null
  [ -f "$GATEWAY_MARKER" ] || { echo "gateway/dist missing — run npm run build" >&2; exit 1; }
  [ -f "$CONNECTOR_MARKER" ] || { echo "connector/dist missing — run npm run build" >&2; exit 1; }
  [ -f "$WEB_DIR/index.html" ] || { echo "$WEB_DIR missing — run (cd web && npm ci && npm run build)" >&2; exit 1; }
  sleep 0.5
  require_port "$GATEWAY_PORT" gateway
  require_port "$MOCK_PORT" "mock hermes"
  make_tls
  start_postgres
  : > "$STATE/login-codes.jsonl"
  chmod 600 "$STATE/login-codes.jsonl"
  mkdir -p "$STATE/files/uploads"
  cp "$ROOT/android/app/src/main/ic_launcher-playstore.png" "$STATE/files/sample.png" 2>/dev/null || true
  printf '<!doctype html><script>alert("xss")</script>\n' > "$STATE/files/page.html"
  printf '<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>\n' > "$STATE/files/image.svg"

  local server_requests=1
  [ "${HR_WEB_STACK_LEGACY_PROTOCOL:-0}" = 1 ] && server_requests=0
  start_recorded mock "$ROOT" env MOCK_HERMES_PORT="$MOCK_PORT" \
    MOCK_HERMES_EXTRA_SESSIONS="${HR_WEB_STACK_EXTRA_SESSIONS:-6}" \
    HR_MOCK_SERVER_REQUESTS="$server_requests" node "$MOCK_MARKER"
  wait_for "mock hermes" "\"$LSOF\" -ti tcp:$MOCK_PORT -sTCP:LISTEN"

  local -a gateway_environment=()
  local line
  while IFS= read -r line; do gateway_environment+=("$line"); done < <(gateway_env)
  start_recorded gateway "$ROOT/gateway" env "${gateway_environment[@]}" \
    node --import "$ROOT/scripts/dev/resend-capture.mjs" "$GATEWAY_MARKER"
  wait_for gateway "curl -s --cacert $STATE/tls/ca.pem $ORIGIN/readyz | grep -q '\"status\":\"ready\"'"

  NODE_EXTRA_CA_CERTS="$STATE/tls/ca.pem" WEB_STACK_ORIGIN="$ORIGIN" WEB_STACK_EMAIL="$EMAIL" \
    HR_DEV_EMAIL_SINK="$STATE/login-codes.jsonl" WEB_STACK_DATABASE_URL="$DATABASE_URL" \
    WEB_STACK_CREDENTIAL_FILE="$STATE/connector-credential.json" WEB_STACK_DEVICE_ID="$DEVICE_ID" \
    node "$ROOT/scripts/dev/web-stack-seed.mjs"

  local hermes_url="http://127.0.0.1:$MOCK_PORT"
  case "$hermes_url" in *:9119*) echo "refusing to point the Connector at 9119" >&2; exit 1 ;; esac
  start_recorded connector "$ROOT/connector" env CONNECTOR_MODE=account \
    ACCOUNT_CONNECTOR_CREDENTIAL_FILE="$STATE/connector-credential.json" \
    GATEWAY_URL="wss://localhost:$GATEWAY_PORT/v2/connect" NODE_EXTRA_CA_CERTS="$STATE/tls/ca.pem" \
    DEVICE_ID="$DEVICE_ID" HERMES_BASE_URL="$hermes_url" \
    HERMES_BASIC_AUTH_USERNAME=demo HERMES_BASIC_AUTH_PASSWORD=secret \
    FILES_ROOT="$STATE/files" UPLOAD_ROOT="$STATE/files/uploads" \
    SESSION_OBSERVER_ENABLED=0 OBSERVER_STATE_FILE="$STATE/observer-state.json" \
    node "$CONNECTOR_MARKER"
  sleep 2
  status_stack
  echo "open $ORIGIN/app/ and sign in with $EMAIL ($0 code prints the login code)"
  echo "logs: $STATE/{mock,gateway,connector,postgres}.log"
}

status_stack() {
  curl -s --cacert "$STATE/tls/ca.pem" "$ORIGIN/readyz" || echo "gateway not responding"
  echo
  grep -q "connector.ready\|account connector ready\|ready" "$STATE/connector.log" 2>/dev/null \
    && echo "connector: see $STATE/connector.log" || true
}

print_code() {
  tail -n 1 "$STATE/login-codes.jsonl" 2>/dev/null || echo "no code captured yet"
}

case "${1:-}" in
  up) start_stack ;;
  down) stop_stack ;;
  status) status_stack ;;
  code) print_code ;;
  *) echo "usage: $0 up|down|status|code" >&2; exit 2 ;;
esac
