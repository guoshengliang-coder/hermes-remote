#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SYSTEM_ROOT="${SYSTEM_ROOT:-}"
prefix() { printf '%s%s' "$SYSTEM_ROOT" "$1"; }
APP_DIR="$(prefix /opt/hermes-release-server)"
DATA_DIR="$(prefix /srv/hermes-releases)"
ETC_DIR="$(prefix /etc/hermes-release-server)"
UNIT_DIR="$(prefix /etc/systemd/system)"

install -d -m 0755 "$APP_DIR" "$APP_DIR/deploy" "$APP_DIR/release-server/src"
install -d -m 0700 "$DATA_DIR"
install -d -m 0750 "$ETC_DIR/tls" "$UNIT_DIR"
install -m 0644 "$ROOT/deploy/publish-release.mjs" "$APP_DIR/deploy/publish-release.mjs"
install -m 0644 "$ROOT/release-server/src/schema.mjs" "$APP_DIR/release-server/src/schema.mjs"
install -m 0644 "$ROOT/release-server/src/server.mjs" "$APP_DIR/release-server/src/server.mjs"
install -m 0644 "$ROOT/deploy/hermes-release-server.service.template" "$UNIT_DIR/hermes-release-server.service"
install -m 0600 "$ROOT/deploy/hermes-release-server.environment.template" "$ETC_DIR/environment"
if [[ ! -e "$DATA_DIR/index.json" ]]; then
  printf '%s\n' '{"schemaVersion":1,"channel":"internal","latestVersionCode":0,"generatedAt":"1970-01-01T00:00:00Z","versions":[]}' > "$DATA_DIR/index.json"
  chmod 0600 "$DATA_DIR/index.json"
fi
node -e "import('$APP_DIR/release-server/src/schema.mjs').then(async m=>m.validateIndex(JSON.parse(await require('node:fs/promises').readFile('$DATA_DIR/index.json','utf8'))))"

if [[ -z "$SYSTEM_ROOT" ]]; then
  install -o root -g kkk -m 0640 /etc/letsencrypt/live/mrlgs.net/fullchain.pem "$ETC_DIR/tls/fullchain.pem.new"
  install -o root -g kkk -m 0640 /etc/letsencrypt/live/mrlgs.net/privkey.pem "$ETC_DIR/tls/privkey.pem.new"
  mv -f "$ETC_DIR/tls/fullchain.pem.new" "$ETC_DIR/tls/fullchain.pem"
  mv -f "$ETC_DIR/tls/privkey.pem.new" "$ETC_DIR/tls/privkey.pem"
  chown -R kkk:kkk "$APP_DIR" "$DATA_DIR"
  chown -R root:kkk "$ETC_DIR/tls"
  systemctl daemon-reload
fi
