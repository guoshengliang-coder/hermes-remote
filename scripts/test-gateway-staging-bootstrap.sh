#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

service_name=hermes-go-gateway-ephemeral
container_name=hermes-go-gateway-ephemeral
server_name=staging.hermes.invalid
gateway_port=28787
edge_port=28443
mock_port=29001
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
  docker rm --force "$container_name" >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

if [ "$(uname -s)" != "Linux" ] || [ "$(uname -m)" != "x86_64" ]; then
  report_failure prerequisite "requires_linux_x86_64"
  exit 1
fi

for command_name in curl docker git nginx node npm openssl ss sudo systemctl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    report_failure prerequisite "missing_command=$command_name"
    exit 1
  fi
done

run_dir=$(mktemp -d "${TMPDIR:-/tmp}/hermes-r3-ephemeral.XXXXXX")
chmod 0700 "$run_dir"
mkdir -m 0700 "$run_dir/inputs" "$run_dir/runtime" "$run_dir/runtime/uploads"

umask 077
openssl rand -hex 32 >"$run_dir/inputs/app-token"
openssl rand -hex 32 >"$run_dir/inputs/connector-token"
openssl rand -hex 32 >"$run_dir/inputs/internal-status-token"

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -subj "/CN=Hermes R3 Ephemeral Root" \
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
  "$run_dir/inputs/privkey.pem"
chmod 0644 "$run_dir/inputs/fullchain.pem" "$run_dir/inputs/ca.crt"

sudo install -m 0644 "$run_dir/inputs/ca.crt" /usr/local/share/ca-certificates/hermes-r3-ephemeral.crt
sudo update-ca-certificates >/dev/null
printf '127.0.0.1 %s\n' "$server_name" | sudo tee -a /etc/hosts >/dev/null

npm ci --ignore-scripts
npm run build

output_name="gateway-staging-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
case "$output_name" in
  *[!A-Za-z0-9._-]*)
    report_failure prerequisite "output_identity_invalid"
    exit 1
    ;;
esac
if ! bundle_output=$(./scripts/package-gateway-bundle.sh "outputs/$output_name"); then
  report_failure prerequisite "bundle_package_failed"
  exit 1
fi
printf '%s\n' "$bundle_output"
manifest_path=$(printf '%s\n' "$bundle_output" | sed -n 's/^MANIFEST=//p')
server_version=$(printf '%s\n' "$bundle_output" | sed -n 's/^SERVER_VERSION=//p' | tail -n 1)
source_commit=$(printf '%s\n' "$bundle_output" | sed -n 's/^SOURCE_COMMIT=//p' | tail -n 1)
if [ -z "$manifest_path" ] || [ -z "$server_version" ] || [ -z "$source_commit" ]; then
  report_failure candidate "bundle_identity_missing"
  exit 1
fi

config_path="$run_dir/inputs/staging.json"
cat >"$config_path" <<EOF
{
  "schemaVersion": 1,
  "environment": "staging",
  "operator": "github-actions",
  "artifactManifest": "$manifest_path",
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
EXPECTED_SOURCE_COMMIT="$source_commit" \
EXPECTED_SERVER_VERSION="$server_version" \
  node scripts/verify-gateway-image-candidate.mjs

sudo env "PATH=$PATH" node scripts/hermesctl.mjs status --config "$config_path"
doctor_path="$run_dir/runtime/doctor.json"
sudo env "PATH=$PATH" node scripts/hermesctl.mjs doctor --config "$config_path" --output "$doctor_path"
if [ "$(sudo stat -c '%a' "$doctor_path")" != "600" ]; then
  report_failure candidate "doctor_permissions_invalid"
  exit 1
fi

audit_path=/var/lib/hermes-go-ephemeral/ops/operations.jsonl
if [ "$(sudo stat -c '%a' "$audit_path")" != "600" ]; then
  report_failure candidate "audit_permissions_invalid"
  exit 1
fi
sudo env "PATH=$PATH" \
  "APP_TOKEN=$app_token" \
  "CONNECTOR_TOKEN=$connector_token" \
  "INTERNAL_STATUS_TOKEN=$internal_status_token" \
  "DOCTOR_PATH=$doctor_path" \
  "AUDIT_PATH=$audit_path" \
  node --input-type=module -e '
    import { readFileSync } from "node:fs";
    const doctorText = readFileSync(process.env.DOCTOR_PATH, "utf8");
    const auditText = readFileSync(process.env.AUDIT_PATH, "utf8");
    for (const name of ["APP_TOKEN", "CONNECTOR_TOKEN", "INTERNAL_STATUS_TOKEN"]) {
      if (doctorText.includes(process.env[name]) || auditText.includes(process.env[name])) {
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
    if (audit.length !== 4 || audit.some((entry, index) =>
      entry.environment !== "staging" || entry.operator !== "github-actions" ||
      entry.result !== (index % 2 === 0 ? "started" : "success"))) {
      throw new Error("audit_sequence_invalid");
    }
  '

echo "GATEWAY_EPHEMERAL_STAGING_OK"
echo "SERVER_VERSION=$server_version"
echo "SOURCE_COMMIT=$source_commit"
