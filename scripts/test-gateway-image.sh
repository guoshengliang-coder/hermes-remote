#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

reported=0
phase=prerequisite
run_dir=
container_name=
mock_pid=
connector_pid=

report_failure() {
  reported=1
  node scripts/report-release-error.mjs "$1" "$2"
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$connector_pid" ]; then kill "$connector_pid" 2>/dev/null || true; wait "$connector_pid" 2>/dev/null || true; fi
  if [ -n "$mock_pid" ]; then kill "$mock_pid" 2>/dev/null || true; wait "$mock_pid" 2>/dev/null || true; fi
  if [ -n "$container_name" ]; then docker rm --force "$container_name" >/dev/null 2>&1 || true; fi
  if [ -n "$run_dir" ] && [ -d "$run_dir" ]; then rm -rf -- "$run_dir"; fi
  if [ "$status" -ne 0 ] && [ "$reported" -ne 1 ]; then
    node scripts/report-release-error.mjs "$phase" "unexpected_failure_in_$phase"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

for command_name in curl docker node npm openssl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    report_failure prerequisite "missing_command=$command_name"
    exit 1
  fi
done

docker_platform=$(docker info --format '{{.OSType}}/{{.Architecture}}' 2>/dev/null || true)
case "$docker_platform" in
  linux/amd64|linux/x86_64) ;;
  *) report_failure prerequisite "unsupported_docker_platform=$docker_platform"; exit 1 ;;
esac

if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
  report_failure prerequisite "source_worktree_not_clean"
  exit 1
fi

run_dir=$(mktemp -d "${TMPDIR:-/tmp}/hermes-gateway-oci.XXXXXX")
source_commit=$(git rev-parse HEAD)
source_short=$(printf '%s' "$source_commit" | cut -c1-12)
server_version=$(node -p "require('./gateway/package.json').version")
image="hermes-remote-gateway:${server_version}-${source_short}"
container_name="hermes-gateway-oci-${source_short}-$$"
gateway_port=18787
mock_port=19120
app_token=$(openssl rand -hex 32)
connector_token=$(openssl rand -hex 32)
internal_status_token=$(openssl rand -hex 32)

phase=prerequisite
if ! npm ci --ignore-scripts; then
  report_failure prerequisite "npm_ci_failed"
  exit 1
fi
if ! npm run build -w @hermes-remote/protocol || ! npm run build -w @hermes-remote/connector; then
  report_failure prerequisite "host_smoke_build_failed"
  exit 1
fi
if ! ./scripts/package-gateway-image.sh; then
  report_failure prerequisite "gateway_image_package_failed"
  exit 1
fi

phase=candidate
image_revision=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")
image_version=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$image")
image_architecture=$(docker image inspect --format '{{.Architecture}}' "$image")
if [ "$image_revision" != "$source_commit" ] || [ "$image_version" != "$server_version" ] || [ "$image_architecture" != "amd64" ]; then
  report_failure candidate "image_identity_mismatch"
  exit 1
fi

MOCK_HERMES_PORT="$mock_port" MOCK_HERMES_USERNAME=demo MOCK_HERMES_PASSWORD=secret \
  node scripts/mock-hermes.mjs >"$run_dir/mock-hermes.log" 2>&1 &
mock_pid=$!

docker run --detach \
  --name "$container_name" \
  --publish "127.0.0.1:${gateway_port}:8787" \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m,uid=1000,gid=1000 \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --memory=256m \
  --cpus=1 \
  --pids-limit=128 \
  --env APP_TOKEN="$app_token" \
  --env CONNECTOR_TOKEN="$connector_token" \
  --env INTERNAL_STATUS_TOKEN="$internal_status_token" \
  --env ACCOUNT_AUTH_ENABLED=0 \
  --env ACCOUNT_BINDING_ENABLED=0 \
  --env DEFAULT_DEVICE_ID=oci-staging \
  --env LIFECYCLE_EVENT_STORE_FILE=/tmp/lifecycle-events.json \
  "$image" >/dev/null

gateway_ready=0
for _ in $(seq 1 50); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${gateway_port}/healthz" >/dev/null 2>&1; then
    gateway_ready=1
    break
  fi
  sleep 0.2
done
if [ "$gateway_ready" -ne 1 ]; then
  report_failure candidate "gateway_healthz_timeout"
  exit 1
fi

GATEWAY_URL="ws://127.0.0.1:${gateway_port}/v1/connect" \
CONNECTOR_TOKEN="$connector_token" \
DEVICE_ID=oci-staging \
HERMES_MODE=live \
HERMES_BASE_URL="http://127.0.0.1:${mock_port}" \
HERMES_BASIC_AUTH_USERNAME=demo \
HERMES_BASIC_AUTH_PASSWORD=secret \
SESSION_OBSERVER_ENABLED=0 \
FILES_ROOT="$run_dir" \
UPLOAD_ROOT="$run_dir/uploads" \
node connector/dist/index.js >"$run_dir/connector.log" 2>"$run_dir/connector.error.log" &
connector_pid=$!

connector_ready=0
for _ in $(seq 1 75); do
  health=$(curl -fsS --max-time 2 "http://127.0.0.1:${gateway_port}/health" 2>/dev/null || true)
  case "$health" in
    *'"connectors":1'*) connector_ready=1; break ;;
  esac
  sleep 0.2
done
if [ "$connector_ready" -ne 1 ]; then
  report_failure candidate "connector_attach_timeout"
  exit 1
fi

phase=smoke
PUBLIC_GATEWAY_URL="http://127.0.0.1:${gateway_port}" \
APP_TOKEN="$app_token" \
INTERNAL_STATUS_TOKEN="$internal_status_token" \
EXPECTED_SOURCE_COMMIT="$source_commit" \
EXPECTED_SERVER_VERSION="$server_version" \
node scripts/verify-gateway-image-candidate.mjs

image_id=$(docker image inspect --format '{{.Id}}' "$image")
echo "GATEWAY_OCI_RELEASE_OK"
echo "SERVER_VERSION=$server_version"
echo "SOURCE_COMMIT=$source_commit"
echo "ARTIFACT_IMAGE=$image"
echo "IMAGE_ID=$image_id"
