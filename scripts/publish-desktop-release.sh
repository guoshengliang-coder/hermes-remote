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

common_required=(
  DESKTOP_RELEASE_SSH_TARGET DESKTOP_RELEASE_REMOTE_ROOT DESKTOP_RELEASE_PUBLIC_ORIGIN
)
for name in "${common_required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 64; }
done
[[ "$DESKTOP_RELEASE_SSH_TARGET" =~ ^[A-Za-z0-9._@:-]+$ ]] || exit 64
[[ "$DESKTOP_RELEASE_REMOTE_ROOT" =~ ^/[A-Za-z0-9._/-]+$ ]] || exit 64
[[ "$DESKTOP_RELEASE_PUBLIC_ORIGIN" =~ ^https://[A-Za-z0-9._:-]+$ ]] || exit 64

repo_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$repo_root"
remote_release="$DESKTOP_RELEASE_REMOTE_ROOT/desktop/releases"
remote_components="$DESKTOP_RELEASE_REMOTE_ROOT/desktop/components"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

readback_indexes() {
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" -o "$work/release-index-readback"
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json" -o "$work/component-index-readback"
}

if [[ "$mode" == rollback ]]; then
  ssh "$DESKTOP_RELEASE_SSH_TARGET" \
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

manifest_files() {
  node - "$1" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const entries = value.artifacts ?? value.components;
if (!Array.isArray(entries) || entries.length === 0) process.exit(65);
for (const entry of entries) {
  if (typeof entry.fileName !== 'string' || !/^[A-Za-z0-9._-]+$/.test(entry.fileName)) process.exit(65);
  process.stdout.write(`${entry.fileName}\n`);
}
NODE
}

release_name="$(basename "$DESKTOP_RELEASE_MANIFEST")"
component_name="$(basename "$DESKTOP_COMPONENT_MANIFEST")"
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

ssh "$DESKTOP_RELEASE_SSH_TARGET" \
  "mkdir -p '$remote_release/$DESKTOP_RELEASE_VERSION' '$remote_components/$DESKTOP_RELEASE_VERSION'"
for name in "$release_name" $(manifest_files "$DESKTOP_RELEASE_MANIFEST"); do
  source="$release_dir/$name"
  [[ -f "$source" && ! -L "$source" ]] || exit 66
  scp "$source" "$DESKTOP_RELEASE_SSH_TARGET:$remote_release/$DESKTOP_RELEASE_VERSION/$name"
  curl -fsS "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/$DESKTOP_RELEASE_VERSION/$name" \
    -o "$work/release-$name"
  cmp -s "$source" "$work/release-$name"
done
for name in "$component_name" $(manifest_files "$DESKTOP_COMPONENT_MANIFEST"); do
  source="$component_dir/$name"
  [[ -f "$source" && ! -L "$source" ]] || exit 66
  scp "$source" "$DESKTOP_RELEASE_SSH_TARGET:$remote_components/$DESKTOP_RELEASE_VERSION/$name"
  curl -fsS "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/$DESKTOP_RELEASE_VERSION/$name" \
    -o "$work/component-$name"
  cmp -s "$source" "$work/component-$name"
done

ssh "$DESKTOP_RELEASE_SSH_TARGET" \
  "cp '$remote_release/index.json' '$remote_release/index.rollback.json' 2>/dev/null || true; cp '$remote_components/index.json' '$remote_components/index.rollback.json' 2>/dev/null || true"
rollback_failed_publish() {
  ssh "$DESKTOP_RELEASE_SSH_TARGET" \
    "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.json'; test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.json'" || true
}
trap 'rollback_failed_publish; rm -rf "$work"' ERR
scp "$work/release-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_release/index.json.next"
scp "$work/component-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_components/index.json.next"
ssh "$DESKTOP_RELEASE_SSH_TARGET" \
  "mv -f '$remote_release/index.json.next' '$remote_release/index.json'; mv -f '$remote_components/index.json.next' '$remote_components/index.json'"
readback_indexes
cmp -s "$work/release-index.json" "$work/release-index-readback"
cmp -s "$work/component-index.json" "$work/component-index-readback"
trap 'rm -rf "$work"' ERR
ssh "$DESKTOP_RELEASE_SSH_TARGET" \
  "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.previous.json'; test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.previous.json'"

git tag -a "$release_tag" -m "Desktop managed release $DESKTOP_RELEASE_VERSION"
git push origin "$release_tag"
printf 'DESKTOP_RELEASE_OK\nTAG=%s\nRELEASE_INDEX=%s\nCOMPONENT_INDEX=%s\n' \
  "$release_tag" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
