#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

git diff --check
if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
  echo "Gateway release packaging requires a clean worktree" >&2
  exit 1
fi

source_commit=$(git rev-parse HEAD)
source_epoch=$(git show -s --format=%ct HEAD)
source_short=$(printf '%s' "$source_commit" | cut -c1-12)
build_time=$(node -e "process.stdout.write(new Date(Number(process.argv[1]) * 1000).toISOString())" "$source_epoch")
server_version=$(node -p "require('./gateway/package.json').version")
image="hermes-remote-gateway:${server_version}-${source_short}"

docker build \
  --file deploy/Dockerfile.gateway \
  --build-arg "HERMES_BUILD_COMMIT=$source_commit" \
  --build-arg HERMES_BUILD_DIRTY=0 \
  --build-arg HERMES_REQUIRE_RELEASE_CLEAN=1 \
  --build-arg "SOURCE_DATE_EPOCH=$source_epoch" \
  --build-arg "HERMES_SERVER_VERSION=$server_version" \
  --build-arg "HERMES_BUILD_TIME=$build_time" \
  --tag "$image" \
  .

image_id=$(docker image inspect --format '{{.Id}}' "$image")
echo "GATEWAY_IMAGE_RELEASE_OK"
echo "SERVER_VERSION=$server_version"
echo "SOURCE_COMMIT=$source_commit"
echo "ARTIFACT_IMAGE=$image"
echo "IMAGE_ID=$image_id"
