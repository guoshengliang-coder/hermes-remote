#!/bin/bash
set -euo pipefail

[[ "${CONFIRM_PRODUCTION_DEPLOY:-}" == "mrlgs.net" ]] || {
  echo "Set CONFIRM_PRODUCTION_DEPLOY=mrlgs.net" >&2
  exit 1
}
[[ "$(id -u)" == 0 ]] || { echo "Run as root" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$(mktemp -d /var/tmp/hermes-edge-backup.XXXXXX)"
RELEASE_ENV=/etc/hermes-release-server/environment
EDGE_CONF=/etc/nginx/conf.d/hermes-edge.conf
DEFAULT_SITE=/etc/nginx/sites-enabled/default
DEFAULT_SITE_BACKUP=/etc/nginx/sites-enabled/default.hermes-disabled
CERT_HOOK=/etc/letsencrypt/renewal-hooks/deploy/hermes-services
NGINX_WAS_ACTIVE=false
RELEASE_ENV_EXISTED=false
EDGE_CONF_EXISTED=false
CERT_HOOK_EXISTED=false

wait_for_https() {
  for _ in $(seq 1 40); do
    if curl --fail --silent --show-error "$@" >/dev/null 2>&1; then return 0; fi
    sleep 0.25
  done
  return 1
}

systemctl is-active --quiet hermes-remote-gateway.service
systemctl is-active --quiet hermes-release-server.service
[[ -r /etc/letsencrypt/live/mrlgs.net/fullchain.pem && -r /etc/letsencrypt/live/mrlgs.net/privkey.pem ]]

if systemctl is-active --quiet nginx.service 2>/dev/null; then NGINX_WAS_ACTIVE=true; fi
if [[ -e "$RELEASE_ENV" ]]; then cp -a "$RELEASE_ENV" "$BACKUP_DIR/release-environment"; RELEASE_ENV_EXISTED=true; fi
if [[ -e "$EDGE_CONF" ]]; then cp -a "$EDGE_CONF" "$BACKUP_DIR/hermes-edge.conf"; EDGE_CONF_EXISTED=true; fi
if [[ -e "$CERT_HOOK" ]]; then cp -a "$CERT_HOOK" "$BACKUP_DIR/hermes-services"; CERT_HOOK_EXISTED=true; fi

rollback() {
  echo "Edge deployment failed; restoring previous services" >&2
  systemctl stop nginx.service >/dev/null 2>&1 || true
  systemctl unmask nginx.service >/dev/null 2>&1 || true
  if [[ "$RELEASE_ENV_EXISTED" == true ]]; then cp -a "$BACKUP_DIR/release-environment" "$RELEASE_ENV"; fi
  if [[ "$EDGE_CONF_EXISTED" == true ]]; then cp -a "$BACKUP_DIR/hermes-edge.conf" "$EDGE_CONF"; else rm -f "$EDGE_CONF"; fi
  if [[ "$CERT_HOOK_EXISTED" == true ]]; then cp -a "$BACKUP_DIR/hermes-services" "$CERT_HOOK"; else rm -f "$CERT_HOOK"; fi
  if [[ -e "$DEFAULT_SITE_BACKUP" && ! -e "$DEFAULT_SITE" ]]; then mv "$DEFAULT_SITE_BACKUP" "$DEFAULT_SITE"; fi
  systemctl daemon-reload >/dev/null 2>&1 || true
  systemctl restart hermes-release-server.service >/dev/null 2>&1 || true
  if [[ "$NGINX_WAS_ACTIVE" == true ]]; then systemctl start nginx.service >/dev/null 2>&1 || true; fi
}
trap rollback ERR INT TERM

if ! command -v nginx >/dev/null 2>&1; then
  systemctl mask nginx.service >/dev/null 2>&1 || true
  DEBIAN_FRONTEND=noninteractive apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
  systemctl unmask nginx.service >/dev/null 2>&1 || true
fi

install -d -o root -g root -m 0700 /etc/hermes-edge/tls
install -o root -g root -m 0600 /etc/letsencrypt/live/mrlgs.net/fullchain.pem /etc/hermes-edge/tls/fullchain.pem
install -o root -g root -m 0600 /etc/letsencrypt/live/mrlgs.net/privkey.pem /etc/hermes-edge/tls/privkey.pem
install -m 0644 "$ROOT/release-server/src/server.mjs" /opt/hermes-release-server/release-server/src/server.mjs
install -o root -g root -m 0600 "$ROOT/deploy/hermes-release-server.environment.template" "$RELEASE_ENV"
install -o root -g root -m 0644 "$ROOT/deploy/hermes-edge.nginx.conf.template" "$EDGE_CONF"
install -o root -g root -m 0755 "$ROOT/deploy/certbot-hermes-services-hook.sh.template" "$CERT_HOOK"
if [[ -e "$DEFAULT_SITE" && ! -e "$DEFAULT_SITE_BACKUP" ]]; then mv "$DEFAULT_SITE" "$DEFAULT_SITE_BACKUP"; fi

nginx -t
systemctl daemon-reload
systemctl restart hermes-release-server.service
wait_for_https --resolve mrlgs.net:9443:127.0.0.1 https://mrlgs.net:9443/health
systemctl enable --now nginx.service

wait_for_https --resolve mrlgs.net:443:127.0.0.1 https://mrlgs.net/health
wait_for_https --resolve mrlgs.net:443:127.0.0.1 https://mrlgs.net/relay-health
wait_for_https --resolve mrlgs.net:443:127.0.0.1 https://mrlgs.net/releases/index.json
[[ "$(curl --silent --show-error --resolve mrlgs.net:443:127.0.0.1 --output /dev/null --write-out '%{http_code}' https://mrlgs.net/api/status)" == 401 ]]

trap - ERR INT TERM
rm -rf "$BACKUP_DIR"
echo "EDGE_DEPLOY_OK BASE_URL=https://mrlgs.net"
