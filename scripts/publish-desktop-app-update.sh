#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-desktop-app-update.sh [--rollback]

Publish one already notarized Desktop DMG and its generated app update index
(scripts/desktop-app-update-index.mjs), or atomically restore the previous index.

Configuration:
  DESKTOP_RELEASE_SSH_TARGET, DESKTOP_RELEASE_PUBLIC_ORIGIN   (shared with the release publisher)
  DESKTOP_APP_VERSION, DESKTOP_APP_ARCHITECTURE, DESKTOP_APP_CHANNEL
  DESKTOP_APP_DMG, DESKTOP_APP_UPDATE_INDEX

Remote root:
  Non-protected: DESKTOP_RELEASE_REMOTE_ROOT -> <root>/desktop/apps
  Protected:     DESKTOP_RELEASE_PRESTAGED_PROTECTED=1 and DESKTOP_RELEASE_REMOTE_APP_ROOT
                 (the DMG must already be staged as a root-owned file; this script never uploads
                  into a root-owned store.)

This script uploads one immutable DMG, verifies the public copy byte-for-byte, switches
/desktop/apps/index.json through a .next file, reads it back, preserves the previous index for
--rollback, and only then creates the desktop-app-v<version> tag.
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
  : "${DESKTOP_RELEASE_REMOTE_APP_ROOT:?Missing Desktop app update root}"
  remote_apps="$DESKTOP_RELEASE_REMOTE_APP_ROOT"
else
  : "${DESKTOP_RELEASE_REMOTE_ROOT:?Missing Desktop remote root}"
  remote_apps="$DESKTOP_RELEASE_REMOTE_ROOT/desktop/apps"
fi
[[ "$remote_apps" =~ ^/[A-Za-z0-9._/-]+$ ]] || exit 64

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

readback_index() {
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/index.json" -o "$work/app-index-readback"
}

if [[ "$mode" == rollback ]]; then
  remote_exec \
    "set -eu; test -f '$remote_apps/index.previous.json'; cp '$remote_apps/index.json' '$remote_apps/index.rollback-next.json'; mv -f '$remote_apps/index.previous.json' '$remote_apps/index.json'; mv -f '$remote_apps/index.rollback-next.json' '$remote_apps/index.previous.json'"
  readback_index
  printf 'DESKTOP_APP_UPDATE_ROLLBACK_OK\nAPP_INDEX=%s\n' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/index.json"
  exit 0
fi

publish_required=(
  DESKTOP_APP_VERSION DESKTOP_APP_ARCHITECTURE DESKTOP_APP_CHANNEL
  DESKTOP_APP_DMG DESKTOP_APP_UPDATE_INDEX
)
for name in "${publish_required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 64; }
done
[[ "$DESKTOP_APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 64
[[ "$DESKTOP_APP_ARCHITECTURE" =~ ^(arm64|x86_64|universal)$ ]] || exit 64
[[ "$DESKTOP_APP_CHANNEL" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || exit 64
[[ -f "$DESKTOP_APP_DMG" && ! -L "$DESKTOP_APP_DMG" ]] || exit 66
[[ -f "$DESKTOP_APP_UPDATE_INDEX" && ! -L "$DESKTOP_APP_UPDATE_INDEX" ]] || exit 66

app_name="Hermes-Go-Desktop-${DESKTOP_APP_VERSION}.dmg"
[[ "$(basename "$DESKTOP_APP_DMG")" == "$app_name" ]] || {
  echo "DMG must be named $app_name" >&2
  exit 64
}

[[ -z "$(git status --porcelain)" ]] || { echo "Release worktree is dirty" >&2; exit 65; }
git fetch --no-tags origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
  echo "Release must run from current origin/main" >&2
  exit 65
}

release_tag="desktop-app-v${DESKTOP_APP_VERSION}"
git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null && {
  echo "Tag already exists: $release_tag" >&2
  exit 65
}
git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null 2>&1 && {
  echo "Remote tag already exists: $release_tag" >&2
  exit 65
}

app_size="$(stat -f %z "$DESKTOP_APP_DMG")"
app_sha="$(shasum -a 256 "$DESKTOP_APP_DMG" | awk '{print $1}')"

# The generated index is the only source of the published URL and hashes. Reject anything that does
# not describe exactly this DMG before it can reach the public store.
node - "$DESKTOP_APP_UPDATE_INDEX" "$DESKTOP_APP_VERSION" "$DESKTOP_APP_CHANNEL" \
  "$DESKTOP_APP_ARCHITECTURE" "$app_size" "$app_sha" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/$DESKTOP_APP_VERSION/$app_name" <<'NODE'
const fs = require('node:fs');
const [path, version, channel, architecture, size, sha, expectedURL] = process.argv.slice(2);
const index = JSON.parse(fs.readFileSync(path, 'utf8'));
const fail = (message) => { console.error(message); process.exit(66); };
if (index.schemaVersion !== 1) fail('index schemaVersion must be 1');
if (index.appVersion !== version) fail('index appVersion does not match');
if (index.channel !== channel) fail('index channel does not match');
if (index.architecture !== architecture) fail('index architecture does not match');
if (index.sizeBytes !== Number(size)) fail('index sizeBytes does not match the DMG');
if (index.sha256 !== sha) fail('index sha256 does not match the DMG');
if (index.downloadURL !== expectedURL) fail('index downloadURL is not the expected versioned URL');
if (!Array.isArray(index.releaseNotes)) fail('index releaseNotes must be an array');
NODE
cp "$DESKTOP_APP_UPDATE_INDEX" "$work/app-index.json"

if [[ "$protected" == 1 ]]; then
  remote_exec "set -eu; test -d '$remote_apps/$DESKTOP_APP_VERSION'; test -f '$remote_apps/index.json'"
else
  remote_exec "mkdir -p '$remote_apps/$DESKTOP_APP_VERSION'"
fi
if [[ "$protected" == 1 ]]; then
  remote_exec "test -f '$remote_apps/$DESKTOP_APP_VERSION/$app_name'"
else
  scp "$DESKTOP_APP_DMG" "$DESKTOP_RELEASE_SSH_TARGET:$remote_apps/$DESKTOP_APP_VERSION/$app_name"
fi
curl -fsS "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/$DESKTOP_APP_VERSION/$app_name" \
  -o "$work/$app_name"
cmp -s "$DESKTOP_APP_DMG" "$work/$app_name"

remote_exec "set -eu; test -f '$remote_apps/index.json'; cp '$remote_apps/index.json' '$remote_apps/index.rollback.json'"
rollback_failed_publish() {
  remote_exec \
    "test ! -f '$remote_apps/index.rollback.json' || mv -f '$remote_apps/index.rollback.json' '$remote_apps/index.json'" || true
}
trap 'rollback_failed_publish; rm -rf "$work"' ERR
if [[ "$protected" == 1 ]]; then
  remote_stage="$(ssh "$DESKTOP_RELEASE_SSH_TARGET" mktemp -d /tmp/hermes-desktop-app-publish.XXXXXXXX)"
  [[ "$remote_stage" =~ ^/tmp/hermes-desktop-app-publish\.[A-Za-z0-9]+$ ]] || exit 65
  scp "$work/app-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_stage/app-index.json"
  remote_exec "set -eu; install -m 0644 '$remote_stage/app-index.json' '$remote_apps/index.json.next'; rm -rf '$remote_stage'"
else
  scp "$work/app-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_apps/index.json.next"
fi
remote_exec "mv -f '$remote_apps/index.json.next' '$remote_apps/index.json'"
readback_index
cmp -s "$work/app-index.json" "$work/app-index-readback"
trap 'rm -rf "$work"' ERR
remote_exec \
  "test ! -f '$remote_apps/index.rollback.json' || mv -f '$remote_apps/index.rollback.json' '$remote_apps/index.previous.json'"

git tag -a "$release_tag" -m "Desktop app update $DESKTOP_APP_VERSION"
git push origin "$release_tag"
printf 'DESKTOP_APP_UPDATE_OK\nTAG=%s\nAPP_INDEX=%s\nAPP_DMG=%s\nSIZE_BYTES=%s\nSHA256=%s\n' \
  "$release_tag" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/index.json" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/apps/$DESKTOP_APP_VERSION/$app_name" \
  "$app_size" "$app_sha"
