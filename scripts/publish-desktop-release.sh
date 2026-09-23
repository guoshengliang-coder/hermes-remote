#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-desktop-release.sh [--rollback]

Publish two already packaged and signed Desktop manifests plus every immutable artifact they name,
or atomically restore the previous release/component index pair. Configuration is supplied through
the DESKTOP_RELEASE_* environment variables documented in docs/DESKTOP_RELEASE_CHANNEL.md.
EOF
}

mode=publish
case "${1:-}" in
  "") ;;
  --rollback) mode=rollback ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 64 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 64; }

common_required=(DESKTOP_RELEASE_SSH_TARGET DESKTOP_RELEASE_PUBLIC_ORIGIN)
for name in "${common_required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 64; }
done
[[ "$DESKTOP_RELEASE_SSH_TARGET" =~ ^[A-Za-z0-9._@:-]+$ ]] || exit 64
[[ "$DESKTOP_RELEASE_PUBLIC_ORIGIN" =~ ^https://[A-Za-z0-9._:-]+$ ]] || exit 64
protected="${DESKTOP_RELEASE_PRESTAGED_PROTECTED:-0}"
[[ "$protected" == 0 || "$protected" == 1 ]] || exit 64
if [[ "$protected" == 1 ]]; then
  : "${DESKTOP_RELEASE_REMOTE_RELEASE_ROOT:?Missing Desktop release root}"
  : "${DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT:?Missing Desktop component root}"
  remote_release="$DESKTOP_RELEASE_REMOTE_RELEASE_ROOT"
  remote_components="$DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT"
else
  : "${DESKTOP_RELEASE_REMOTE_ROOT:?Missing Desktop remote root}"
  remote_release="$DESKTOP_RELEASE_REMOTE_ROOT/desktop/releases"
  remote_components="$DESKTOP_RELEASE_REMOTE_ROOT/desktop/components"
fi
[[ "$remote_release" =~ ^/[A-Za-z0-9._/-]+$ ]] || exit 64
[[ "$remote_components" =~ ^/[A-Za-z0-9._/-]+$ ]] || exit 64

remote_exec() {
  if [[ "$protected" == 1 ]]; then
    printf '%s\n' "$1" | ssh "$DESKTOP_RELEASE_SSH_TARGET" sudo -n bash -s
  else
    ssh "$DESKTOP_RELEASE_SSH_TARGET" "$1"
  fi
}

repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$repo_root"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

readback_indexes() {
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" -o "$work/release-index-readback"
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json" -o "$work/component-index-readback"
}

if [[ "$mode" == rollback ]]; then
  remote_exec \
    "set -eu; test -f '$remote_release/index.previous.json'; test -f '$remote_components/index.previous.json'; cp '$remote_release/index.json' '$remote_release/index.rollback-next.json'; cp '$remote_components/index.json' '$remote_components/index.rollback-next.json'; mv -f '$remote_release/index.previous.json' '$remote_release/index.json'; mv -f '$remote_components/index.previous.json' '$remote_components/index.json'; mv -f '$remote_release/index.rollback-next.json' '$remote_release/index.previous.json'; mv -f '$remote_components/index.rollback-next.json' '$remote_components/index.previous.json'"
  readback_indexes
  printf 'DESKTOP_RELEASE_ROLLBACK_OK\nRELEASE_INDEX=%s\nCOMPONENT_INDEX=%s\n' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
  exit 0
fi

publish_required=(
  DESKTOP_RELEASE_VERSION DESKTOP_RELEASE_ARCHITECTURE DESKTOP_RELEASE_CHANNEL
  DESKTOP_RELEASE_MANIFEST DESKTOP_COMPONENT_MANIFEST DESKTOP_RELEASE_SIGNING_KEY_ID
  DESKTOP_RELEASE_SIGNING_PUBLIC_KEY
)
for name in "${publish_required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 64; }
done
[[ "$DESKTOP_RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 64
[[ "$DESKTOP_RELEASE_ARCHITECTURE" =~ ^(arm64|x86_64|universal)$ ]] || exit 64
[[ "$DESKTOP_RELEASE_CHANNEL" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || exit 64
[[ "$DESKTOP_RELEASE_SIGNING_KEY_ID" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || exit 64
[[ -f "$DESKTOP_RELEASE_MANIFEST" && ! -L "$DESKTOP_RELEASE_MANIFEST" ]] || exit 66
[[ -f "$DESKTOP_COMPONENT_MANIFEST" && ! -L "$DESKTOP_COMPONENT_MANIFEST" ]] || exit 66

[[ -z "$(git status --porcelain)" ]] || { echo "Release worktree is dirty" >&2; exit 65; }
git fetch --no-tags origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
  echo "Release must run from current origin/main" >&2
  exit 65
}

release_tag="desktop-managed-v${DESKTOP_RELEASE_VERSION}"
git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null && {
  echo "Tag already exists: $release_tag" >&2
  exit 65
}
if git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null 2>&1; then
  echo "Remote tag already exists: $release_tag" >&2
  exit 65
fi

release_dir="$(cd "$(dirname "$DESKTOP_RELEASE_MANIFEST")" && pwd -P)"
component_dir="$(cd "$(dirname "$DESKTOP_COMPONENT_MANIFEST")" && pwd -P)"
node scripts/verify-desktop-managed-release.mjs \
  --manifest "$DESKTOP_RELEASE_MANIFEST" --artifacts "$release_dir" \
  --key-id "$DESKTOP_RELEASE_SIGNING_KEY_ID" --public-key "$DESKTOP_RELEASE_SIGNING_PUBLIC_KEY" \
  --origin "$DESKTOP_RELEASE_PUBLIC_ORIGIN" --channel "$DESKTOP_RELEASE_CHANNEL" \
  --architecture "$DESKTOP_RELEASE_ARCHITECTURE" >/dev/null
node scripts/verify-desktop-component-release-v2.mjs \
  --manifest "$DESKTOP_COMPONENT_MANIFEST" --artifacts "$component_dir" \
  --key-id "$DESKTOP_RELEASE_SIGNING_KEY_ID" --public-key "$DESKTOP_RELEASE_SIGNING_PUBLIC_KEY" \
  --origin "$DESKTOP_RELEASE_PUBLIC_ORIGIN" --channel "$DESKTOP_RELEASE_CHANNEL" \
  --architecture "$DESKTOP_RELEASE_ARCHITECTURE" >/dev/null

release_name="$(basename "$DESKTOP_RELEASE_MANIFEST")"
component_name="$(basename "$DESKTOP_COMPONENT_MANIFEST")"
release_files="$(node scripts/desktop-publish-files.mjs "$DESKTOP_RELEASE_MANIFEST")"
component_files="$(node scripts/desktop-publish-files.mjs "$DESKTOP_COMPONENT_MANIFEST")"
release_size="$(stat -f %z "$DESKTOP_RELEASE_MANIFEST")"
component_size="$(stat -f %z "$DESKTOP_COMPONENT_MANIFEST")"
release_sha="$(shasum -a 256 "$DESKTOP_RELEASE_MANIFEST" | awk '{print $1}')"
component_sha="$(shasum -a 256 "$DESKTOP_COMPONENT_MANIFEST" | awk '{print $1}')"
updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

node - "$work/release-index.json" "$DESKTOP_RELEASE_VERSION" "$DESKTOP_RELEASE_CHANNEL" \
  "$DESKTOP_RELEASE_ARCHITECTURE" "$release_name" "$release_size" "$release_sha" \
  "$updated_at" "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases" <<'NODE'
const fs = require('node:fs');
const [out, version, channel, architecture, name, size, sha, updatedAt, base] = process.argv.slice(2);
fs.writeFileSync(out, `${JSON.stringify({
  schemaVersion: 1, channel, architecture, releaseVersion: version,
  manifestURL: `${base}/${version}/${name}`, manifestSizeBytes: Number(size),
  manifestSHA256: sha, updatedAt,
})}\n`, { mode: 0o600 });
NODE
node - "$work/component-index.json" "$DESKTOP_RELEASE_VERSION" "$DESKTOP_RELEASE_CHANNEL" \
  "$DESKTOP_RELEASE_ARCHITECTURE" "$component_name" "$component_size" "$component_sha" \
  "$updated_at" "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components" <<'NODE'
const fs = require('node:fs');
const [out, version, channel, architecture, name, size, sha, updatedAt, base] = process.argv.slice(2);
fs.writeFileSync(out, `${JSON.stringify({
  schemaVersion: 1, channel, architecture, releaseVersion: version,
  manifestURL: `${base}/${version}/${name}`, manifestSizeBytes: Number(size),
  manifestSHA256: sha, updatedAt,
})}\n`, { mode: 0o600 });
NODE

if [[ "$protected" == 1 ]]; then
  remote_exec "set -eu; test -d '$remote_release/$DESKTOP_RELEASE_VERSION'; test -d '$remote_components/$DESKTOP_RELEASE_VERSION'; test -f '$remote_release/index.json'; test -f '$remote_components/index.json'"
else
  remote_exec "mkdir -p '$remote_release/$DESKTOP_RELEASE_VERSION' '$remote_components/$DESKTOP_RELEASE_VERSION'"
fi
for name in "$release_name" $release_files; do
  source="$release_dir/$name"
  [[ -f "$source" && ! -L "$source" ]] || exit 66
  if [[ "$protected" == 1 ]]; then
    remote_exec "test -f '$remote_release/$DESKTOP_RELEASE_VERSION/$name'"
  else
    scp "$source" "$DESKTOP_RELEASE_SSH_TARGET:$remote_release/$DESKTOP_RELEASE_VERSION/$name"
  fi
  curl -fsS "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/$DESKTOP_RELEASE_VERSION/$name" \
    -o "$work/release-$name"
  cmp -s "$source" "$work/release-$name"
done
for name in "$component_name" $component_files; do
  source="$component_dir/$name"
  [[ -f "$source" && ! -L "$source" ]] || exit 66
  if [[ "$protected" == 1 ]]; then
    remote_exec "test -f '$remote_components/$DESKTOP_RELEASE_VERSION/$name'"
  else
    scp "$source" "$DESKTOP_RELEASE_SSH_TARGET:$remote_components/$DESKTOP_RELEASE_VERSION/$name"
  fi
  curl -fsS "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/$DESKTOP_RELEASE_VERSION/$name" \
    -o "$work/component-$name"
  cmp -s "$source" "$work/component-$name"
done

remote_exec \
  "set -eu; test -f '$remote_release/index.json'; test -f '$remote_components/index.json'; cp '$remote_release/index.json' '$remote_release/index.rollback.json'; cp '$remote_components/index.json' '$remote_components/index.rollback.json'"
rollback_failed_publish() {
  remote_exec \
    "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.json'; test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.json'" || true
}
trap 'rollback_failed_publish; rm -rf "$work"' ERR
if [[ "$protected" == 1 ]]; then
  remote_stage="$(ssh "$DESKTOP_RELEASE_SSH_TARGET" mktemp -d /tmp/hermes-desktop-publish.XXXXXXXX)"
  [[ "$remote_stage" =~ ^/tmp/hermes-desktop-publish\.[A-Za-z0-9]+$ ]] || exit 65
  scp "$work/release-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_stage/release-index.json"
  scp "$work/component-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_stage/component-index.json"
  remote_exec "set -eu; install -m 0644 '$remote_stage/release-index.json' '$remote_release/index.json.next'; install -m 0644 '$remote_stage/component-index.json' '$remote_components/index.json.next'; rm -rf '$remote_stage'"
else
  scp "$work/release-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_release/index.json.next"
  scp "$work/component-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_components/index.json.next"
fi
remote_exec \
  "mv -f '$remote_release/index.json.next' '$remote_release/index.json'; mv -f '$remote_components/index.json.next' '$remote_components/index.json'"
readback_indexes
cmp -s "$work/release-index.json" "$work/release-index-readback"
cmp -s "$work/component-index.json" "$work/component-index-readback"
trap 'rm -rf "$work"' ERR
remote_exec \
  "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.previous.json'; test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.previous.json'"

git tag -a "$release_tag" -m "Desktop managed release $DESKTOP_RELEASE_VERSION"
git push origin "$release_tag"
printf 'DESKTOP_RELEASE_OK\nTAG=%s\nRELEASE_INDEX=%s\nCOMPONENT_INDEX=%s\n' \
  "$release_tag" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
