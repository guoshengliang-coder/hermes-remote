#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

reported=0
created_archive=0
archive_tmp=
archive_path=

report_failure() {
  reported=1
  if command -v node >/dev/null 2>&1; then
    node scripts/report-release-error.mjs "$1" "$2"
  else
    printf '%s\n' '{"code":"HR-RELEASE-001","summaryZh":"无法生成可验证的 Gateway 镜像，请检查构建环境和源码状态。","summaryEn":"Could not build a verifiable Gateway image. Check the build environment and source state.","retryable":true,"recoveryAction":"inspect_details_and_retry","technicalCause":"missing_command=node","stage":"gateway_oci_prerequisite"}' >&2
  fi
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$archive_tmp" ]; then rm -f -- "$archive_tmp"; fi
  if [ "$status" -ne 0 ] && [ "$created_archive" -eq 1 ] && [ -n "$archive_path" ]; then rm -f -- "$archive_path"; fi
  if [ "$status" -ne 0 ] && [ "$reported" -ne 1 ]; then report_failure prerequisite "gateway_bundle_unexpected_failure"; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

if [ "$#" -ne 1 ]; then
  report_failure prerequisite "usage_requires_output_directory"
  exit 2
fi

output_arg=$1
case "$output_arg" in
  *[!A-Za-z0-9._/-]*)
    report_failure prerequisite "bundle_output_path_unsafe"
    exit 1
    ;;
  ..|.|../*|./*|*/..|*/.|*/../*|*/./*|*//*)
    report_failure prerequisite "bundle_output_path_unsafe"
    exit 1
    ;;
esac
case "$output_arg" in
  outputs/*)
    output_name=${output_arg#outputs/}
    case "$output_name" in
      ""|*[!A-Za-z0-9._-]*)
        report_failure prerequisite "bundle_output_relative_path_invalid"
        exit 1
        ;;
    esac
    if [ -L "$repo_root/outputs" ] || [ -L "$repo_root/outputs/$output_name" ]; then
      report_failure prerequisite "bundle_output_path_unsafe"
      exit 1
    fi
    output_dir="$repo_root/outputs/$output_name"
    if ! mkdir -p -- "$output_dir"; then
      report_failure prerequisite "bundle_output_directory_unavailable"
      exit 1
    fi
    ;;
  /*)
    output_dir=$output_arg
    if [ "$output_dir" = "/" ] || [ ! -d "$output_dir" ] || [ -L "$output_dir" ]; then
      report_failure prerequisite "external_bundle_output_must_be_existing_directory"
      exit 1
    fi
    ;;
  *)
    report_failure prerequisite "bundle_output_relative_path_must_use_outputs"
    exit 1
    ;;
esac
if ! output_dir=$(CDPATH= cd -- "$output_dir" && pwd -P); then
  report_failure prerequisite "bundle_output_directory_unresolvable"
  exit 1
fi
case "$output_dir" in
  "$repo_root/outputs"|"$repo_root/outputs/"*) ;;
  "$repo_root"|"$repo_root/"*)
    report_failure prerequisite "bundle_output_inside_source_tree"
    exit 1
    ;;
esac

for command_name in docker git node tar; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    report_failure prerequisite "missing_command=$command_name"
    exit 1
  fi
done

if ! package_output=$(./scripts/package-gateway-image.sh); then
  report_failure prerequisite "gateway_image_package_failed"
  exit 1
fi
printf '%s\n' "$package_output"

server_version=$(printf '%s\n' "$package_output" | sed -n 's/^SERVER_VERSION=//p')
source_commit=$(printf '%s\n' "$package_output" | sed -n 's/^SOURCE_COMMIT=//p')
image_reference=$(printf '%s\n' "$package_output" | sed -n 's/^ARTIFACT_IMAGE=//p')
image_id=$(printf '%s\n' "$package_output" | sed -n 's/^IMAGE_ID=//p')
source_short=$(printf '%s' "$source_commit" | cut -c1-12)
source_epoch=$(git show -s --format=%ct "$source_commit")
created_at=$(node -e 'process.stdout.write(new Date(Number(process.argv[1]) * 1000).toISOString())' "$source_epoch")
if ! architecture=$(docker image inspect --format '{{.Architecture}}' "$image_reference"); then
  report_failure candidate "bundle_image_inspect_failed"
  exit 1
fi

if [ "$architecture" != "amd64" ]; then
  report_failure candidate "bundle_architecture=$architecture"
  exit 1
fi

archive_file="Hermes-Gateway-${server_version}-${source_short}-linux-amd64.tar"
manifest_file="Hermes-Gateway-${server_version}-${source_short}-linux-amd64.manifest.json"
archive_path="$output_dir/$archive_file"
manifest_path="$output_dir/$manifest_file"
archive_tmp="$archive_path.tmp.$$"

if [ -e "$archive_path" ] || [ -L "$archive_path" ] || \
   [ -e "$manifest_path" ] || [ -L "$manifest_path" ] || \
   [ -e "$archive_tmp" ] || [ -L "$archive_tmp" ]; then
  report_failure prerequisite "bundle_output_already_exists"
  exit 1
fi

if ! docker image save --output "$archive_tmp" "$image_reference"; then
  report_failure candidate "bundle_archive_save_failed"
  exit 1
fi
chmod 0644 "$archive_tmp"
mv -- "$archive_tmp" "$archive_path"
created_archive=1

if ! containerd_image_id=$(node scripts/inspect-gateway-archive-identity.mjs \
  "$archive_path" \
  "$image_reference" \
  "$image_id"); then
  report_failure candidate "bundle_archive_identity_invalid"
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  archive_sha256=$(sha256sum "$archive_path" | awk '{print $1}')
else
  if ! command -v shasum >/dev/null 2>&1; then
    report_failure prerequisite "missing_sha256_command"
    exit 1
  fi
  archive_sha256=$(shasum -a 256 "$archive_path" | awk '{print $1}')
fi

if ! node scripts/write-gateway-bundle-manifest.mjs \
    "$manifest_path" \
    "$archive_file" \
    "$archive_sha256" \
    "$image_reference" \
    "$image_id" \
    "$containerd_image_id" \
    "$architecture" \
    "$server_version" \
    "$source_commit" \
    "$created_at" \
    "$repo_root/gateway/release-contract.json"; then
  report_failure prerequisite "bundle_manifest_write_failed"
  exit 1
fi

trap - EXIT HUP INT TERM
echo "GATEWAY_BUNDLE_RELEASE_OK"
echo "SERVER_VERSION=$server_version"
echo "SOURCE_COMMIT=$source_commit"
echo "ARTIFACT_IMAGE=$image_reference"
echo "IMAGE_ID=$image_id"
echo "CONTAINERD_IMAGE_ID=$containerd_image_id"
echo "ARCHIVE=$archive_path"
echo "ARCHIVE_SHA256=$archive_sha256"
echo "MANIFEST=$manifest_path"
