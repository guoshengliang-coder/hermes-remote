#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

service_name=hermes-go-gateway-ephemeral
container_name=hermes-go-gateway-ephemeral
blue_service_name=hermes-go-gateway-ephemeral-blue
green_service_name=hermes-go-gateway-ephemeral-green
blue_container_name=hermes-go-gateway-ephemeral-blue
green_container_name=hermes-go-gateway-ephemeral-green
server_name=staging.hermes.invalid
gateway_port=28787
blue_port=28788
green_port=28789
edge_port=28443
mock_port=29001
r3_commit=e94d89dea9b4f416942a78e3120d14bb94500e5c
r4_commit=1dc2c38e22e1e8eb049020361a29ee929144f839
run_dir=
mock_pid=
connector_pid=

report_failure() {
  node scripts/report-release-error.mjs "$1" "ephemeral_staging_$2"
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  set +e
  if [ -n "$connector_pid" ]; then kill "$connector_pid" >/dev/null 2>&1; wait "$connector_pid" >/dev/null 2>&1; fi
  if [ -n "$mock_pid" ]; then kill "$mock_pid" >/dev/null 2>&1; wait "$mock_pid" >/dev/null 2>&1; fi
  sudo systemctl stop "${service_name}.service" >/dev/null 2>&1
  sudo systemctl stop "${blue_service_name}.service" "${green_service_name}.service" >/dev/null 2>&1
  docker rm --force "$container_name" "$blue_container_name" "$green_container_name" >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

if [ "$(uname -s)" != "Linux" ] || [ "$(uname -m)" != "x86_64" ]; then
  report_failure prerequisite "requires_linux_x86_64"
  exit 1
fi

for command_name in curl docker git nginx node npm openssl pg_isready psql ss sudo systemctl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    report_failure prerequisite "missing_command=$command_name"
    exit 1
  fi
done

if ! PGPASSWORD=ephemeral-only-password psql \
    --host 127.0.0.1 --username hermes_staging --dbname hermes_staging \
    --tuples-only --no-align --command 'SHOW server_version_num' | grep '^18[0-9][0-9][0-9][0-9]$' >/dev/null; then
  report_failure prerequisite "postgresql_18_unavailable"
  exit 1
fi

run_dir=$(mktemp -d "${TMPDIR:-/tmp}/hermes-r4-ephemeral.XXXXXX")
chmod 0700 "$run_dir"
mkdir -m 0700 "$run_dir/inputs" "$run_dir/runtime" "$run_dir/runtime/uploads" \
  "$run_dir/runtime/candidate" "$run_dir/runtime/candidate/uploads" \
  "$run_dir/r3-bundle" "$run_dir/r4-bundle"

umask 077
openssl rand -hex 32 >"$run_dir/inputs/app-token"
openssl rand -hex 32 >"$run_dir/inputs/connector-token"
openssl rand -hex 32 >"$run_dir/inputs/internal-status-token"
printf '%s\n' 'postgresql://hermes_staging:ephemeral-only-password@127.0.0.1:5432/hermes_staging' \
  >"$run_dir/inputs/account-database-url"

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -subj "/CN=Hermes R4 Ephemeral Root" \
  -keyout "$run_dir/inputs/ca.key" \
  -out "$run_dir/inputs/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -sha256 -nodes \
  -subj "/CN=${server_name}" \
  -keyout "$run_dir/inputs/privkey.pem" \
  -out "$run_dir/inputs/server.csr" >/dev/null 2>&1
printf '%s\n' \
  "subjectAltName=DNS:${server_name}" \
  "extendedKeyUsage=serverAuth" \
  >"$run_dir/inputs/server.ext"
openssl x509 -req -sha256 -days 1 \
  -in "$run_dir/inputs/server.csr" \
  -CA "$run_dir/inputs/ca.crt" \
  -CAkey "$run_dir/inputs/ca.key" \
  -CAcreateserial \
  -extfile "$run_dir/inputs/server.ext" \
  -out "$run_dir/inputs/fullchain.pem" >/dev/null 2>&1
chmod 0600 "$run_dir/inputs/app-token" \
  "$run_dir/inputs/connector-token" \
  "$run_dir/inputs/internal-status-token" \
  "$run_dir/inputs/account-database-url" \
  "$run_dir/inputs/privkey.pem"
chmod 0644 "$run_dir/inputs/fullchain.pem" "$run_dir/inputs/ca.crt"

sudo install -m 0644 "$run_dir/inputs/ca.crt" /usr/local/share/ca-certificates/hermes-r4-ephemeral.crt
sudo update-ca-certificates >/dev/null
printf '127.0.0.1 %s\n' "$server_name" | sudo tee -a /etc/hosts >/dev/null

npm ci --ignore-scripts
npm run build

git clone --quiet --no-local "$repo_root" "$run_dir/r3-source"
git -C "$run_dir/r3-source" checkout --quiet --detach "$r3_commit"
(
  cd "$run_dir/r3-source"
  npm ci --ignore-scripts
  ./scripts/package-gateway-bundle.sh "$run_dir/r3-bundle"
) >"$run_dir/runtime/r3-bundle-output"
r3_bundle_output=$(cat "$run_dir/runtime/r3-bundle-output")
printf '%s\n' "$r3_bundle_output"
r3_manifest_path=$(printf '%s\n' "$r3_bundle_output" | sed -n 's/^MANIFEST=//p')
r3_server_version=$(printf '%s\n' "$r3_bundle_output" | sed -n 's/^SERVER_VERSION=//p' | tail -n 1)
r3_source_commit=$(printf '%s\n' "$r3_bundle_output" | sed -n 's/^SOURCE_COMMIT=//p' | tail -n 1)
if [ "$r3_server_version" != "0.2.0" ] || [ "$r3_source_commit" != "$r3_commit" ]; then
  report_failure candidate "r3_bundle_identity_invalid"
  exit 1
fi

git clone --quiet --no-local "$repo_root" "$run_dir/r4-source"
git -C "$run_dir/r4-source" checkout --quiet --detach "$r4_commit"
(
  cd "$run_dir/r4-source"
  npm ci --ignore-scripts
  ./scripts/package-gateway-bundle.sh "$run_dir/r4-bundle"
) >"$run_dir/runtime/r4-bundle-output"
r4_bundle_output=$(cat "$run_dir/runtime/r4-bundle-output")
printf '%s\n' "$r4_bundle_output"
r4_manifest_path=$(printf '%s\n' "$r4_bundle_output" | sed -n 's/^MANIFEST=//p')
r4_server_version=$(printf '%s\n' "$r4_bundle_output" | sed -n 's/^SERVER_VERSION=//p' | tail -n 1)
r4_source_commit=$(printf '%s\n' "$r4_bundle_output" | sed -n 's/^SOURCE_COMMIT=//p' | tail -n 1)
if [ "$r4_server_version" != "0.3.0" ] || [ "$r4_source_commit" != "$r4_commit" ]; then
  report_failure candidate "r4_bundle_identity_invalid"
  exit 1
fi

output_name="gateway-r4-database-staging-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
case "$output_name" in
  *[!A-Za-z0-9._-]*)
    report_failure prerequisite "output_identity_invalid"
    exit 1
    ;;
esac
if ! database_bundle_output=$(./scripts/package-gateway-bundle.sh "outputs/$output_name"); then
  report_failure prerequisite "bundle_package_failed"
  exit 1
fi
printf '%s\n' "$database_bundle_output"
database_manifest_path=$(printf '%s\n' "$database_bundle_output" | sed -n 's/^MANIFEST=//p')
database_server_version=$(printf '%s\n' "$database_bundle_output" | sed -n 's/^SERVER_VERSION=//p' | tail -n 1)
database_source_commit=$(printf '%s\n' "$database_bundle_output" | sed -n 's/^SOURCE_COMMIT=//p' | tail -n 1)
if [ -z "$r3_manifest_path" ] || [ -z "$r4_manifest_path" ] || [ -z "$database_manifest_path" ] \
    || [ "$database_server_version" != "0.4.0" ] || [ -z "$database_source_commit" ]; then
  report_failure candidate "bundle_identity_missing"
  exit 1
fi

config_path="$run_dir/inputs/staging.json"
cat >"$config_path" <<EOF
{
  "schemaVersion": 1,
  "environment": "staging",
  "operator": "github-actions",
  "artifactManifest": "$r3_manifest_path",
  "paths": {
    "installRoot": "/opt/hermes-go-ephemeral",
    "configRoot": "/etc/hermes-go-ephemeral",
    "stateRoot": "/var/lib/hermes-go-ephemeral",
    "systemdUnitDirectory": "/etc/systemd/system"
  },
  "service": {
    "name": "$service_name",
    "containerName": "$container_name",
    "gatewayPort": $gateway_port
  },
  "gateway": {
    "defaultDeviceId": "oci-staging",
    "accountAuthEnabled": false,
    "accountBindingEnabled": false
  },
  "secrets": {
    "appTokenSource": "$run_dir/inputs/app-token",
    "connectorTokenSource": "$run_dir/inputs/connector-token",
    "internalStatusTokenSource": "$run_dir/inputs/internal-status-token"
  },
  "nginx": {
    "serverName": "$server_name",
    "listenPort": $edge_port,
    "certificateSource": "$run_dir/inputs/fullchain.pem",
    "privateKeySource": "$run_dir/inputs/privkey.pem",
    "configFile": "/etc/nginx/conf.d/hermes-go-ephemeral.conf"
  }
}
EOF
chmod 0600 "$config_path"

node scripts/hermesctl.mjs preflight --config "$config_path"
sudo env "PATH=$PATH" node scripts/hermesctl.mjs bootstrap --config "$config_path" --confirm staging
sudo env "PATH=$PATH" node scripts/hermesctl.mjs bootstrap --config "$config_path" --confirm staging

MOCK_HERMES_PORT="$mock_port" MOCK_HERMES_USERNAME=demo MOCK_HERMES_PASSWORD=secret \
  node scripts/mock-hermes.mjs >"$run_dir/runtime/mock-hermes.log" 2>&1 &
mock_pid=$!

app_token=$(sed -n '1p' "$run_dir/inputs/app-token")
connector_token=$(sed -n '1p' "$run_dir/inputs/connector-token")
internal_status_token=$(sed -n '1p' "$run_dir/inputs/internal-status-token")
NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
GATEWAY_URL="wss://${server_name}:${edge_port}/v1/connect" \
CONNECTOR_TOKEN="$connector_token" \
DEVICE_ID=oci-staging \
HERMES_MODE=live \
HERMES_BASE_URL="http://127.0.0.1:${mock_port}" \
HERMES_BASIC_AUTH_USERNAME=demo \
HERMES_BASIC_AUTH_PASSWORD=secret \
SESSION_OBSERVER_ENABLED=0 \
FILES_ROOT="$run_dir/runtime" \
UPLOAD_ROOT="$run_dir/runtime/uploads" \
  node connector/dist/index.js >"$run_dir/runtime/connector.log" 2>"$run_dir/runtime/connector.error.log" &
connector_pid=$!

connector_ready=0
for _ in $(seq 1 75); do
  health=$(curl --fail --silent --show-error --max-time 2 \
    "https://${server_name}:${edge_port}/relay-health" 2>/dev/null || true)
  case "$health" in
    *'"connectors":1'*) connector_ready=1; break ;;
  esac
  sleep 0.2
done
if [ "$connector_ready" -ne 1 ]; then
  report_failure candidate "connector_attach_timeout"
  exit 1
fi

NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
PUBLIC_GATEWAY_URL="https://${server_name}:${edge_port}" \
INTERNAL_GATEWAY_URL="http://127.0.0.1:${gateway_port}" \
RELAY_HEALTH_PATH=/relay-health \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$r3_source_commit" \
EXPECTED_SERVER_VERSION="$r3_server_version" \
EXPECTED_DEVICE_ID=oci-staging \
  node scripts/verify-gateway-image-candidate.mjs

sudo env "PATH=$PATH" node scripts/hermesctl.mjs status --config "$config_path"
doctor_path="$run_dir/runtime/doctor.json"
sudo env "PATH=$PATH" node scripts/hermesctl.mjs doctor --config "$config_path" --output "$doctor_path"
if [ "$(sudo stat -c '%a' "$doctor_path")" != "600" ]; then
  report_failure candidate "doctor_permissions_invalid"
  exit 1
fi

deploy_config_path="$run_dir/inputs/deploy.json"
write_deploy_config() {
  target_manifest=$1
  database_mode=${2:-disabled}
  database_json=null
  if [ "$database_mode" = enabled ]; then
    database_json="{\"urlSource\":\"$run_dir/inputs/account-database-url\",\"ssl\":false,\"migrationLockId\":741852}"
  fi
  cat >"$deploy_config_path" <<EOF
{
  "schemaVersion": 2,
  "environment": "staging",
  "operator": "github-actions",
  "targetArtifactManifest": "$target_manifest",
  "paths": {
    "installRoot": "/opt/hermes-go-ephemeral",
    "configRoot": "/etc/hermes-go-ephemeral",
    "stateRoot": "/var/lib/hermes-go-ephemeral",
    "systemdUnitDirectory": "/etc/systemd/system"
  },
  "legacySource": {
    "serviceName": "$service_name",
    "containerName": "$container_name",
    "gatewayPort": $gateway_port,
    "stateDirectory": "/var/lib/hermes-go-ephemeral/gateway"
  },
  "slots": {
    "blue": {
      "serviceName": "$blue_service_name",
      "containerName": "$blue_container_name",
      "gatewayPort": $blue_port
    },
    "green": {
      "serviceName": "$green_service_name",
      "containerName": "$green_container_name",
      "gatewayPort": $green_port
    }
  },
  "gateway": {
    "defaultDeviceId": "oci-staging",
    "accountAuthEnabled": false,
    "accountBindingEnabled": false
  },
  "secrets": {
    "appTokenSource": "$run_dir/inputs/app-token",
    "connectorTokenSource": "$run_dir/inputs/connector-token",
    "internalStatusTokenSource": "$run_dir/inputs/internal-status-token"
  },
  "database": $database_json,
  "nginx": {
    "serverName": "$server_name",
    "listenPort": $edge_port,
    "certificateSource": "$run_dir/inputs/fullchain.pem",
    "privateKeySource": "$run_dir/inputs/privkey.pem",
    "configFile": "/etc/nginx/conf.d/hermes-go-ephemeral.conf",
    "upstreamConfigFile": "/etc/nginx/hermes-go-upstreams/hermes-go-ephemeral-upstream.conf"
  },
  "deployment": {
    "drainTimeoutSeconds": 5,
    "observationSeconds": 1
  }
}
EOF
  chmod 0600 "$deploy_config_path"
}

run_transition() {
  operation=$1
  sudo env \
    "PATH=$PATH" \
    "NODE_EXTRA_CA_CERTS=$run_dir/inputs/ca.crt" \
    "HERMES_SMOKE_CONNECTOR_ENTRY=$repo_root/connector/dist/index.js" \
    "HERMES_MODE=live" \
    "HERMES_BASE_URL=http://127.0.0.1:${mock_port}" \
    "HERMES_BASIC_AUTH_USERNAME=demo" \
    "HERMES_BASIC_AUTH_PASSWORD=secret" \
    "FILES_ROOT=$run_dir/runtime/candidate" \
    "UPLOAD_ROOT=$run_dir/runtime/candidate/uploads" \
    node scripts/hermesctl.mjs "$operation" --config "$deploy_config_path" --confirm staging
}

if [ "${HERMES_R5D_ONLY:-0}" = 1 ]; then
  production_hostname=$(hostname)
  r3_archive_path=$(node --input-type=module -e '
    import { readFileSync } from "node:fs";
    import path from "node:path";
    const manifestPath = process.argv[1];
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    process.stdout.write(path.join(path.dirname(manifestPath), manifest.archiveFile));
  ' "$r3_manifest_path")
  r3_manifest_sha=$(sha256sum "$r3_manifest_path" | cut -d' ' -f1)
  r3_archive_sha=$(sha256sum "$r3_archive_path" | cut -d' ' -f1)
  identity_digest=$(R3_MANIFEST_PATH="$r3_manifest_path" R3_MANIFEST_SHA="$r3_manifest_sha" \
    R3_ARCHIVE_PATH="$r3_archive_path" R3_ARCHIVE_SHA="$r3_archive_sha" \
    node --input-type=module -e '
      import { createHash } from "node:crypto";
      const files = [
        { path: process.env.R3_MANIFEST_PATH, sha256: process.env.R3_MANIFEST_SHA },
        { path: process.env.R3_ARCHIVE_PATH, sha256: process.env.R3_ARCHIVE_SHA },
      ].sort((left, right) => left.path.localeCompare(right.path));
      process.stdout.write(createHash("sha256").update(JSON.stringify(files)).digest("hex"));
    ')
  legacy_evidence_path="$run_dir/inputs/legacy-recovery.json"
  SOURCE_HOSTNAME="$production_hostname" IDENTITY_DIGEST="$identity_digest" \
    node --input-type=module -e '
      import { writeFileSync } from "node:fs";
      const now = new Date();
      const evidence = {
        schemaVersion: 1,
        kind: "hermes-go-legacy-recovery-v1",
        sourceHostname: process.env.SOURCE_HOSTNAME,
        createdAt: new Date(now.getTime() - 1000).toISOString(),
        artifactSha256: "e".repeat(64),
        subject: { identityDigest: process.env.IDENTITY_DIGEST },
        restoreHostname: "isolated-r5d-restore",
        restoredAt: now.toISOString(),
        verifiedChecks: ["archive_hash", "files_restored", "service_start"],
      };
      writeFileSync(process.argv[1], `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
    ' "$legacy_evidence_path"

  candidate_nginx_path="$run_dir/inputs/hermes-go-ephemeral.candidate.conf"
  sed \
    -e "1i\\include /etc/nginx/hermes-go-upstreams/hermes-go-ephemeral-upstream.conf;" \
    -e "s#http://127.0.0.1:${gateway_port}#http://hermes_go_gateway_production#g" \
    /etc/nginx/conf.d/hermes-go-ephemeral.conf >"$candidate_nginx_path"
  chmod 0600 "$candidate_nginx_path"
  candidate_nginx_sha=$(sha256sum "$candidate_nginx_path" | cut -d' ' -f1)
  production_config_path="$run_dir/inputs/managed-baseline.json"
  cat >"$production_config_path" <<EOF
{
  "schemaVersion": 1,
  "environment": "production",
  "operator": "github-actions",
  "targetArtifactManifest": "$r4_manifest_path",
  "host": { "hostname": "$production_hostname", "architecture": "amd64" },
  "paths": {
    "installRoot": "/opt/hermes-go-ephemeral",
    "configRoot": "/etc/hermes-go-ephemeral",
    "stateRoot": "/var/lib/hermes-go-ephemeral",
    "systemdUnitDirectory": "/etc/systemd/system"
  },
  "legacySource": {
    "serviceName": "$service_name",
    "containerName": "$container_name",
    "gatewayPort": $gateway_port,
    "stateDirectory": "/var/lib/hermes-go-ephemeral/gateway",
    "compatibilityVersion": "$r3_server_version",
    "identityFiles": [
      { "path": "$r3_manifest_path", "sha256": "$r3_manifest_sha" },
      { "path": "$r3_archive_path", "sha256": "$r3_archive_sha" }
    ],
    "recoveryEvidence": "$legacy_evidence_path"
  },
  "slots": {
    "blue": { "serviceName": "$blue_service_name", "containerName": "$blue_container_name", "gatewayPort": $blue_port },
    "green": { "serviceName": "$green_service_name", "containerName": "$green_container_name", "gatewayPort": $green_port }
  },
  "gateway": { "defaultDeviceId": "oci-staging", "accountAuthEnabled": false, "accountBindingEnabled": false },
  "secrets": {
    "appTokenSource": "$run_dir/inputs/app-token",
    "connectorTokenSource": "$run_dir/inputs/connector-token",
    "internalStatusTokenSource": "$run_dir/inputs/internal-status-token"
  },
  "database": null,
  "nginx": {
    "serverName": "$server_name",
    "listenPort": $edge_port,
    "certificateSource": "$run_dir/inputs/fullchain.pem",
    "privateKeySource": "$run_dir/inputs/privkey.pem",
    "candidateConfigSource": "$candidate_nginx_path",
    "candidateConfigSha256": "$candidate_nginx_sha",
    "configFile": "/etc/nginx/conf.d/hermes-go-ephemeral.conf",
    "upstreamConfigFile": "/etc/nginx/hermes-go-upstreams/hermes-go-ephemeral-upstream.conf"
  },
  "deployment": { "drainTimeoutSeconds": 5, "observationSeconds": 1 }
}
EOF
  chmod 0600 "$production_config_path"

  # The disposable R3 bootstrap has its own release identity; production starts without managed links.
  sudo unlink /opt/hermes-go-ephemeral/current

  sudo env \
    "PATH=$PATH" \
    "NODE_EXTRA_CA_CERTS=$run_dir/inputs/ca.crt" \
    "HERMES_SMOKE_CONNECTOR_ENTRY=$repo_root/connector/dist/index.js" \
    "HERMES_MODE=live" \
    "HERMES_BASE_URL=http://127.0.0.1:${mock_port}" \
    "HERMES_BASIC_AUTH_USERNAME=demo" \
    "HERMES_BASIC_AUTH_PASSWORD=secret" \
    "FILES_ROOT=$run_dir/runtime/candidate" \
    "UPLOAD_ROOT=$run_dir/runtime/candidate/uploads" \
    node scripts/production-baseline.mjs \
      --config "$production_config_path" \
      --confirm "production:${production_hostname}"

  expected_r5d_release="releases/${r4_server_version}-$(printf '%s' "$r4_source_commit" | cut -c1-12)"
  expected_legacy_release="releases/${r3_server_version}-$(printf '%s' "$identity_digest" | cut -c1-12)"
  if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_r5d_release" ] \
      || [ "$(sudo readlink /opt/hermes-go-ephemeral/previous)" != "$expected_legacy_release" ] \
      || ! sudo systemctl is-active --quiet "${blue_service_name}.service" \
      || sudo systemctl is-active --quiet "${service_name}.service" \
      || ! sudo grep '^ACCOUNT_AUTH_ENABLED=0$' /etc/hermes-go-ephemeral/slots/blue/gateway.env >/dev/null \
      || ! sudo grep '^ACCOUNT_BINDING_ENABLED=0$' /etc/hermes-go-ephemeral/slots/blue/gateway.env >/dev/null; then
    report_failure candidate "managed_baseline_final_state_invalid"
    exit 1
  fi
  echo "GATEWAY_R5D_MANAGED_BASELINE_OK"
  echo "TARGET_SERVER_VERSION=$r4_server_version"
  echo "TARGET_SOURCE_COMMIT=$r4_source_commit"
  exit 0
fi

write_deploy_config "$r4_manifest_path"
run_transition deploy

expected_r4_release="releases/${r4_server_version}-$(printf '%s' "$r4_source_commit" | cut -c1-12)"
expected_r3_release="releases/${r3_server_version}-$(printf '%s' "$r3_source_commit" | cut -c1-12)"
if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_r4_release" ] \
    || [ "$(sudo readlink /opt/hermes-go-ephemeral/previous)" != "$expected_r3_release" ]; then
  report_failure candidate "deploy_release_links_invalid"
  exit 1
fi

NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
PUBLIC_GATEWAY_URL="https://${server_name}:${edge_port}" \
INTERNAL_GATEWAY_URL="http://127.0.0.1:${blue_port}" \
RELAY_HEALTH_PATH=/relay-health \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$r4_source_commit" \
EXPECTED_SERVER_VERSION="$r4_server_version" \
EXPECTED_DEVICE_ID=oci-staging \
  node scripts/verify-gateway-image-candidate.mjs

write_deploy_config "$r3_manifest_path"
run_transition rollback

if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_r3_release" ] \
    || [ "$(sudo readlink /opt/hermes-go-ephemeral/previous)" != "$expected_r4_release" ] \
    || ! sudo systemctl is-active --quiet "${green_service_name}.service" \
    || sudo systemctl is-active --quiet "${blue_service_name}.service" \
    || sudo systemctl is-active --quiet "${service_name}.service"; then
  report_failure candidate "rollback_final_state_invalid"
  exit 1
fi

NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
PUBLIC_GATEWAY_URL="https://${server_name}:${edge_port}" \
INTERNAL_GATEWAY_URL="http://127.0.0.1:${green_port}" \
RELAY_HEALTH_PATH=/relay-health \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$r3_source_commit" \
EXPECTED_SERVER_VERSION="$r3_server_version" \
EXPECTED_DEVICE_ID=oci-staging \
  node scripts/verify-gateway-image-candidate.mjs

write_deploy_config "$r4_manifest_path"
run_transition deploy

write_deploy_config "$database_manifest_path" enabled
run_transition deploy

expected_database_release="releases/${database_server_version}-$(printf '%s' "$database_source_commit" | cut -c1-12)"
if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_database_release" ] \
    || [ "$(sudo readlink /opt/hermes-go-ephemeral/previous)" != "$expected_r4_release" ] \
    || [ "$(PGPASSWORD=ephemeral-only-password psql \
      --host 127.0.0.1 --username hermes_staging --dbname hermes_staging \
      --tuples-only --no-align --command 'SELECT version FROM gateway_schema_state WHERE singleton = true')" != "7" ]; then
  report_failure candidate "database_deploy_state_invalid"
  exit 1
fi

NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
PUBLIC_GATEWAY_URL="https://${server_name}:${edge_port}" \
INTERNAL_GATEWAY_URL="http://127.0.0.1:${green_port}" \
RELAY_HEALTH_PATH=/relay-health \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$database_source_commit" \
EXPECTED_SERVER_VERSION="$database_server_version" \
EXPECTED_DEVICE_ID=oci-staging \
  node scripts/verify-gateway-image-candidate.mjs

write_deploy_config "$r4_manifest_path" enabled
if database_rollback_error=$(run_transition rollback 2>&1); then
  report_failure candidate "legacy_database_rollback_was_not_blocked"
  exit 1
fi
case "$database_rollback_error" in
  *HR-OPS-006*) ;;
  *) report_failure candidate "legacy_database_rollback_error_invalid"; exit 1 ;;
esac
if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_database_release" ] \
    || ! sudo systemctl is-active --quiet "${green_service_name}.service"; then
  report_failure candidate "blocked_database_rollback_changed_service"
  exit 1
fi

write_deploy_config "$r4_manifest_path"
run_transition rollback

if [ "$(sudo readlink /opt/hermes-go-ephemeral/current)" != "$expected_r4_release" ] \
    || [ "$(sudo readlink /opt/hermes-go-ephemeral/previous)" != "$expected_database_release" ] \
    || ! sudo systemctl is-active --quiet "${blue_service_name}.service" \
    || sudo systemctl is-active --quiet "${green_service_name}.service" \
    || [ "$(PGPASSWORD=ephemeral-only-password psql \
      --host 127.0.0.1 --username hermes_staging --dbname hermes_staging \
      --tuples-only --no-align --command 'SELECT version FROM gateway_schema_state WHERE singleton = true')" != "7" ]; then
  report_failure candidate "database_fallback_state_invalid"
  exit 1
fi

NODE_EXTRA_CA_CERTS="$run_dir/inputs/ca.crt" \
PUBLIC_GATEWAY_URL="https://${server_name}:${edge_port}" \
INTERNAL_GATEWAY_URL="http://127.0.0.1:${blue_port}" \
RELAY_HEALTH_PATH=/relay-health \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$r4_source_commit" \
EXPECTED_SERVER_VERSION="$r4_server_version" \
EXPECTED_DEVICE_ID=oci-staging \
  node scripts/verify-gateway-image-candidate.mjs

audit_path=/var/lib/hermes-go-ephemeral/ops/operations.jsonl
if [ "$(sudo stat -c '%a' "$audit_path")" != "600" ]; then
  report_failure candidate "audit_permissions_invalid"
  exit 1
fi
sudo env "PATH=$PATH" \
  "APP_TOKEN=$app_token" \
  "CONNECTOR_TOKEN=$connector_token" \
  "INTERNAL_STATUS_TOKEN=$internal_status_token" \
  "ACCOUNT_DATABASE_URL=$(sed -n '1p' "$run_dir/inputs/account-database-url")" \
  "DOCTOR_PATH=$doctor_path" \
  "AUDIT_PATH=$audit_path" \
  "JOURNAL_PATH=/var/lib/hermes-go-ephemeral/ops/deploy-state.json" \
  node --input-type=module -e '
    import { readFileSync } from "node:fs";
    const doctorText = readFileSync(process.env.DOCTOR_PATH, "utf8");
    const auditText = readFileSync(process.env.AUDIT_PATH, "utf8");
    const journalText = readFileSync(process.env.JOURNAL_PATH, "utf8");
    for (const name of ["APP_TOKEN", "CONNECTOR_TOKEN", "INTERNAL_STATUS_TOKEN", "ACCOUNT_DATABASE_URL"]) {
      if (doctorText.includes(process.env[name]) || auditText.includes(process.env[name]) ||
          journalText.includes(process.env[name])) {
        throw new Error("diagnostic_secret_leak");
      }
    }
    const doctor = JSON.parse(doctorText);
    const policy = doctor.collectionPolicy;
    if (!policy?.allowlistedFieldsOnly || policy.journalIncluded || policy.requestBodiesIncluded ||
        policy.environmentFilesIncluded || policy.secretFilesIncluded || policy.sourcePathsIncluded) {
      throw new Error("doctor_collection_policy_invalid");
    }
    const audit = auditText.trim().split("\n").map(JSON.parse);
    const expectedResults = [
      "started", "success", "started", "success",
      "started", "success", "started", "success",
      "started", "success", "started", "success",
      "started", "failed", "started", "success",
    ];
    if (audit.length !== expectedResults.length || audit.some((entry, index) =>
      entry.environment !== "staging" || entry.operator !== "github-actions" ||
      entry.result !== expectedResults[index]) ||
        audit[4].operation !== "deploy" || audit[5].operation !== "deploy" ||
        audit[6].operation !== "rollback" || audit[7].operation !== "rollback" ||
        audit[8].operation !== "deploy" || audit[9].operation !== "deploy" ||
        audit[10].operation !== "deploy" || audit[11].operation !== "deploy" ||
        audit[12].operation !== "rollback" || audit[12].errorCode !== null ||
        audit[13].operation !== "rollback" || audit[13].errorCode !== "HR-OPS-006" ||
        audit[14].operation !== "rollback" || audit[15].operation !== "rollback") {
      throw new Error("audit_sequence_invalid");
    }
    const journal = JSON.parse(journalText);
    if (journal.operation !== "rollback" || journal.stage !== "committed" || journal.candidateSlot !== "blue") {
      throw new Error("rollback_journal_invalid");
    }
  '

echo "GATEWAY_R4_EPHEMERAL_ROUND_TRIP_OK"
echo "R3_SERVER_VERSION=$r3_server_version"
echo "R3_SOURCE_COMMIT=$r3_source_commit"
echo "R4_SERVER_VERSION=$r4_server_version"
echo "R4_SOURCE_COMMIT=$r4_source_commit"
echo "DATABASE_SERVER_VERSION=$database_server_version"
echo "DATABASE_SOURCE_COMMIT=$database_source_commit"
echo "DATABASE_SCHEMA_VERSION=7"
