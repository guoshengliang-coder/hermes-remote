#!/usr/bin/env bash
# Inherit the rollback trap inside remote/readback helpers as well as at their call sites.
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-desktop-release.sh [--rollback]

Publish two already packaged and signed Desktop manifests plus every immutable artifact they name,
or restore the previous release/component indexes. DESKTOP_RELEASE_COMPONENTS_ONLY=1 publishes or
rolls back only the schema-v2 component channel; no schema-v1 manifest or Hermes archive is used.
Configuration is supplied through
the DESKTOP_RELEASE_* environment variables documented in docs/DESKTOP_RELEASE_CHANNEL.md.
EOF
}

components_only="${DESKTOP_RELEASE_COMPONENTS_ONLY:-0}"
[[ "$components_only" == 0 || "$components_only" == 1 ]] || exit 64
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
  if [[ "$components_only" == 0 ]]; then
    : "${DESKTOP_RELEASE_REMOTE_RELEASE_ROOT:?Missing Desktop release root}"
  fi
  : "${DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT:?Missing Desktop component root}"
  remote_release="${DESKTOP_RELEASE_REMOTE_RELEASE_ROOT:-$DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT}"
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
  if [[ "$components_only" == 0 ]]; then
    curl -fsS -H 'Cache-Control: no-cache' \
      "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" -o "$work/release-index-readback"
  fi
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json" -o "$work/component-index-readback"
}

if [[ "$mode" == rollback && "$components_only" == 1 ]]; then
  remote_exec "cat '$remote_components/index.previous.json'" > "$work/component-rollback-expected"
  remote_exec "set -eu; test -f '$remote_components/index.previous.json'; cp '$remote_components/index.json' '$remote_components/index.rollback-next.json'; mv -f '$remote_components/index.previous.json' '$remote_components/index.json'; mv -f '$remote_components/index.rollback-next.json' '$remote_components/index.previous.json'"
  if ! readback_indexes || ! cmp -s "$work/component-rollback-expected" "$work/component-index-readback"; then
    remote_exec "set -eu; cp '$remote_components/index.json' '$remote_components/index.rollback-next.json'; mv -f '$remote_components/index.previous.json' '$remote_components/index.json'; mv -f '$remote_components/index.rollback-next.json' '$remote_components/index.previous.json'"
    node --input-type=module -e "import {createReleaseError} from './scripts/lib/release-errors.mjs'; process.stderr.write(JSON.stringify(createReleaseError('desktopPackage', 'component_rollback_readback_mismatch'))+'\n')"
    exit 65
  fi
  printf 'DESKTOP_COMPONENT_RELEASE_ROLLBACK_OK\nCOMPONENT_INDEX=%s\n' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
  exit 0
fi

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
  DESKTOP_COMPONENT_MANIFEST DESKTOP_RELEASE_SIGNING_KEY_ID
  DESKTOP_RELEASE_SIGNING_PUBLIC_KEY
)
for name in "${publish_required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 64; }
done
[[ "$DESKTOP_RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 64
[[ "$DESKTOP_RELEASE_ARCHITECTURE" =~ ^(arm64|x86_64|universal)$ ]] || exit 64
[[ "$DESKTOP_RELEASE_CHANNEL" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || exit 64
[[ "$DESKTOP_RELEASE_SIGNING_KEY_ID" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || exit 64
if [[ "$components_only" == 0 ]]; then
  [[ -f "${DESKTOP_RELEASE_MANIFEST:-}" && ! -L "$DESKTOP_RELEASE_MANIFEST" ]] || exit 66
fi
[[ -f "$DESKTOP_COMPONENT_MANIFEST" && ! -L "$DESKTOP_COMPONENT_MANIFEST" ]] || exit 66

[[ -z "$(git status --porcelain)" ]] || { echo "Release worktree is dirty" >&2; exit 65; }
git fetch --no-tags origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
  echo "Release must run from current origin/main" >&2
  exit 65
}

release_tag="desktop-managed-v${DESKTOP_RELEASE_VERSION}"
if [[ "$components_only" == 1 ]]; then release_tag="desktop-components-v${DESKTOP_RELEASE_VERSION}"; fi
git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null && {
  echo "Tag already exists: $release_tag" >&2
  exit 65
}
if git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null 2>&1; then
  echo "Remote tag already exists: $release_tag" >&2
  exit 65
fi

if [[ "$components_only" == 0 ]]; then
  release_dir="$(cd "$(dirname "$DESKTOP_RELEASE_MANIFEST")" && pwd -P)"
fi
component_dir="$(cd "$(dirname "$DESKTOP_COMPONENT_MANIFEST")" && pwd -P)"
if [[ "$components_only" == 1 ]]; then
  curl -fsS -H 'Cache-Control: no-cache' \
    "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json" -o "$work/component-index-before"
  node --input-type=module - "$work/component-index-before" <<'NODE'
import { readFileSync } from 'node:fs';
import { createReleaseError } from './scripts/lib/release-errors.mjs';
const data = readFileSync(process.argv[2]);
const e = process.env;
let valid = false;
try {
  const index = JSON.parse(data);
  const semver = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
  const current = index.releaseVersion;
  const next = e.DESKTOP_RELEASE_VERSION;
  if (data.length <= 4096 && semver.test(current) && semver.test(next)
      && index.channel === e.DESKTOP_RELEASE_CHANNEL && index.architecture === e.DESKTOP_RELEASE_ARCHITECTURE) {
    const left = next.split('.').map(Number), right = current.split('.').map(Number);
    const different = left.findIndex((value, i) => value !== right[i]);
    valid = [...left, ...right].every(Number.isSafeInteger) && different >= 0 && left[different] > right[different];
  }
} catch {}
if (!valid) {
  process.stderr.write(`${JSON.stringify(createReleaseError('desktopPackage', 'publisher_component_index_not_newer_or_invalid'))}\n`);
  process.exit(65);
}
NODE
fi
if [[ "$components_only" == 0 ]]; then
node scripts/verify-desktop-managed-release.mjs \
  --manifest "$DESKTOP_RELEASE_MANIFEST" --artifacts "$release_dir" \
  --key-id "$DESKTOP_RELEASE_SIGNING_KEY_ID" --public-key "$DESKTOP_RELEASE_SIGNING_PUBLIC_KEY" \
  --origin "$DESKTOP_RELEASE_PUBLIC_ORIGIN" --channel "$DESKTOP_RELEASE_CHANNEL" \
  --architecture "$DESKTOP_RELEASE_ARCHITECTURE" >/dev/null
fi
node scripts/verify-desktop-component-release-v2.mjs \
  --manifest "$DESKTOP_COMPONENT_MANIFEST" --artifacts "$component_dir" \
  --key-id "$DESKTOP_RELEASE_SIGNING_KEY_ID" --public-key "$DESKTOP_RELEASE_SIGNING_PUBLIC_KEY" \
  --origin "$DESKTOP_RELEASE_PUBLIC_ORIGIN" --channel "$DESKTOP_RELEASE_CHANNEL" \
  --architecture "$DESKTOP_RELEASE_ARCHITECTURE" >/dev/null

# Bind the verified payload to the exact index namespace and requested version. A valid signature
# alone must not permit publishing an index claiming a different release or different artifact URLs.
node --input-type=module - <<'NODE'
import { readFileSync } from 'node:fs';
import { createReleaseError } from './scripts/lib/release-errors.mjs';
const e = process.env;
const manifests = [[e.DESKTOP_COMPONENT_MANIFEST, 'components', 'components']];
if (e.DESKTOP_RELEASE_COMPONENTS_ONLY !== '1') manifests.push([e.DESKTOP_RELEASE_MANIFEST, 'releases', 'artifacts']);
for (const [file, channel, field] of manifests) {
  const envelope = JSON.parse(readFileSync(file));
  const payload = JSON.parse(Buffer.from(envelope.payload, 'base64url'));
  if (payload.releaseVersion !== e.DESKTOP_RELEASE_VERSION || payload[field].some((item) =>
    item.downloadURL !== `${e.DESKTOP_RELEASE_PUBLIC_ORIGIN}/desktop/${channel}/${e.DESKTOP_RELEASE_VERSION}/${item.fileName}`)) {
    process.stderr.write(`${JSON.stringify(createReleaseError('desktopPackage', 'publisher_manifest_identity_mismatch'))}\n`);
    process.exit(65);
  }
}
NODE

if [[ "$components_only" == 0 ]]; then
  release_name="$(basename "$DESKTOP_RELEASE_MANIFEST")"
  release_files="$(node scripts/desktop-publish-files.mjs "$DESKTOP_RELEASE_MANIFEST")"
  release_size="$(wc -c < "$DESKTOP_RELEASE_MANIFEST" | tr -d '[:space:]')"
  release_sha="$(shasum -a 256 "$DESKTOP_RELEASE_MANIFEST" | awk '{print $1}')"
fi
component_name="$(basename "$DESKTOP_COMPONENT_MANIFEST")"
component_files="$(node scripts/desktop-publish-files.mjs "$DESKTOP_COMPONENT_MANIFEST")"
component_size="$(wc -c < "$DESKTOP_COMPONENT_MANIFEST" | tr -d '[:space:]')"
component_sha="$(shasum -a 256 "$DESKTOP_COMPONENT_MANIFEST" | awk '{print $1}')"
updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Optional update notes carried into both stable indexes. Rejected unless it is exactly a bounded
# array of short single-line strings, so a malformed file cannot publish a bad index.
notes_json='[]'
if [[ -n "${DESKTOP_RELEASE_NOTES_FILE:-}" ]]; then
  [[ -f "$DESKTOP_RELEASE_NOTES_FILE" && ! -L "$DESKTOP_RELEASE_NOTES_FILE" ]] || {
    echo "Desktop release notes file is missing or symlinked" >&2
    exit 66
  }
  notes_json="$(node - "$DESKTOP_RELEASE_NOTES_FILE" <<'NODE'
const fs = require('node:fs');
const notes = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!Array.isArray(notes) || notes.length > 20) process.exit(64);
for (const note of notes) {
  if (typeof note !== 'string' || note.length === 0 || Buffer.byteLength(note) > 500) process.exit(64);
  if (/[\u0000-\u001f\u007f]/.test(note)) process.exit(64);
}
process.stdout.write(JSON.stringify(notes));
NODE
)" || { echo "Desktop release notes file is invalid" >&2; exit 64; }
fi

if [[ "$components_only" == 0 ]]; then
node - "$work/release-index.json" "$DESKTOP_RELEASE_VERSION" "$DESKTOP_RELEASE_CHANNEL" \
  "$DESKTOP_RELEASE_ARCHITECTURE" "$release_name" "$release_size" "$release_sha" \
  "$updated_at" "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases" "$notes_json" <<'NODE'
const fs = require('node:fs');
const [out, version, channel, architecture, name, size, sha, updatedAt, base, notes] = process.argv.slice(2);
fs.writeFileSync(out, `${JSON.stringify({
  schemaVersion: 1, channel, architecture, releaseVersion: version,
  manifestURL: `${base}/${version}/${name}`, manifestSizeBytes: Number(size),
  manifestSHA256: sha, releaseNotes: JSON.parse(notes), updatedAt,
})}\n`, { mode: 0o600 });
NODE
fi
node - "$work/component-index.json" "$DESKTOP_RELEASE_VERSION" "$DESKTOP_RELEASE_CHANNEL" \
  "$DESKTOP_RELEASE_ARCHITECTURE" "$component_name" "$component_size" "$component_sha" \
  "$updated_at" "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components" "$notes_json" <<'NODE'
const fs = require('node:fs');
const [out, version, channel, architecture, name, size, sha, updatedAt, base, notes] = process.argv.slice(2);
fs.writeFileSync(out, `${JSON.stringify({
  schemaVersion: 1, channel, architecture, releaseVersion: version,
  manifestURL: `${base}/${version}/${name}`, manifestSizeBytes: Number(size),
  manifestSHA256: sha, releaseNotes: JSON.parse(notes), updatedAt,
})}\n`, { mode: 0o600 });
NODE

if [[ "$protected" == 1 ]]; then
  remote_exec "set -eu; test -d '$remote_components/$DESKTOP_RELEASE_VERSION'; test -f '$remote_components/index.json'"
  if [[ "$components_only" == 0 ]]; then
    remote_exec "test -d '$remote_release/$DESKTOP_RELEASE_VERSION'; test -f '$remote_release/index.json'"
  fi
else
  remote_exec "mkdir -p '$remote_components/$DESKTOP_RELEASE_VERSION'"
  if [[ "$components_only" == 0 ]]; then remote_exec "mkdir -p '$remote_release/$DESKTOP_RELEASE_VERSION'"; fi
fi
if [[ "$components_only" == 0 ]]; then
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
fi
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

# Repeat the clean/current-main gate immediately before the stable pointer can move.
[[ -z "$(git status --porcelain)" ]] || { echo "Release worktree is dirty" >&2; exit 65; }
git fetch --no-tags origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
  echo "Release must run from current origin/main" >&2; exit 65;
}
remote_exec "set -eu; test -f '$remote_components/index.json'; cp '$remote_components/index.json' '$remote_components/index.rollback.json'"
if [[ "$components_only" == 0 ]]; then
  remote_exec "set -eu; test -f '$remote_release/index.json'; cp '$remote_release/index.json' '$remote_release/index.rollback.json'"
fi
rollback_failed_publish() {
  remote_exec "test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.json'" || true
  if [[ "$components_only" == 0 ]]; then
    remote_exec "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.json'" || true
  fi
}
trap 'rollback_failed_publish; rm -rf "$work"' ERR
if [[ "$protected" == 1 ]]; then
  remote_stage="$(ssh "$DESKTOP_RELEASE_SSH_TARGET" mktemp -d /tmp/hermes-desktop-publish.XXXXXXXX)"
  [[ "$remote_stage" =~ ^/tmp/hermes-desktop-publish\.[A-Za-z0-9]+$ ]] || exit 65
  if [[ "$components_only" == 0 ]]; then
    scp "$work/release-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_stage/release-index.json"
    remote_exec "install -m 0644 '$remote_stage/release-index.json' '$remote_release/index.json.next'"
  fi
  scp "$work/component-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_stage/component-index.json"
  remote_exec "set -eu; install -m 0644 '$remote_stage/component-index.json' '$remote_components/index.json.next'; rm -rf '$remote_stage'"
else
  if [[ "$components_only" == 0 ]]; then
    scp "$work/release-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_release/index.json.next"
  fi
  scp "$work/component-index.json" "$DESKTOP_RELEASE_SSH_TARGET:$remote_components/index.json.next"
fi
if [[ "$components_only" == 0 ]]; then
  remote_exec "mv -f '$remote_release/index.json.next' '$remote_release/index.json'"
fi
remote_exec "mv -f '$remote_components/index.json.next' '$remote_components/index.json'"
readback_indexes
if [[ "$components_only" == 0 ]]; then cmp -s "$work/release-index.json" "$work/release-index-readback"; fi
cmp -s "$work/component-index.json" "$work/component-index-readback"
trap 'rm -rf "$work"' ERR
remote_exec "test ! -f '$remote_components/index.rollback.json' || mv -f '$remote_components/index.rollback.json' '$remote_components/index.previous.json'"
if [[ "$components_only" == 0 ]]; then
  remote_exec "test ! -f '$remote_release/index.rollback.json' || mv -f '$remote_release/index.rollback.json' '$remote_release/index.previous.json'"
fi

git tag -a "$release_tag" -m "Desktop managed release $DESKTOP_RELEASE_VERSION"
git push origin "$release_tag"
if [[ "$components_only" == 1 ]]; then
  printf 'DESKTOP_COMPONENT_RELEASE_OK\nTAG=%s\nCOMPONENT_INDEX=%s\n' \
    "$release_tag" "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
  exit 0
fi
printf 'DESKTOP_RELEASE_OK\nTAG=%s\nRELEASE_INDEX=%s\nCOMPONENT_INDEX=%s\n' \
  "$release_tag" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/releases/index.json" \
  "$DESKTOP_RELEASE_PUBLIC_ORIGIN/desktop/components/index.json"
