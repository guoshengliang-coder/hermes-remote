#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${RELEASE_SSH_HOST:-mrlgs.net}"
USER="${RELEASE_SSH_USER:-kkk}"
REMOTE_ROOT="${RELEASE_DATA_ROOT:-/srv/hermes-releases}"
PUBLIC_BASE="${RELEASE_PUBLIC_BASE_URL:-https://mrlgs.net}"
[[ "$USER" =~ ^[A-Za-z0-9._-]+$ && "$USER" != -* ]] || { echo "Invalid RELEASE_SSH_USER" >&2; exit 1; }
[[ "$HOST" == "mrlgs.net" ]] || { echo "Invalid RELEASE_SSH_HOST" >&2; exit 1; }
[[ "$REMOTE_ROOT" == "/srv/hermes-releases" ]] || { echo "Invalid RELEASE_DATA_ROOT" >&2; exit 1; }
[[ "$PUBLIC_BASE" == "https://mrlgs.net" || "$PUBLIC_BASE" == "https://mrlgs.net:443" ]] || { echo "Invalid RELEASE_PUBLIC_BASE_URL" >&2; exit 1; }
if [[ -n "${APK_RELEASE_GATE_FILE:-}" ]]; then
  GATE="$APK_RELEASE_GATE_FILE"
  GATE_OWNED=0
  [[ -f "$GATE" ]] || { echo "APK_RELEASE_GATE_FILE does not exist" >&2; exit 1; }
else
  GATE="$(mktemp)"
  GATE_OWNED=1
fi
META="$(mktemp)"; DOWNLOADED="$(mktemp)"; INDEX="$(mktemp)"
REMOTE_TMP=""
cleanup() {
  if [[ "$GATE_OWNED" == 1 ]]; then rm -f "$GATE"; fi
  rm -f "$META" "$DOWNLOADED" "$INDEX"
  if [[ -n "$REMOTE_TMP" ]]; then ssh "$USER@$HOST" "rm -rf -- '$REMOTE_TMP'" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT INT TERM

[[ -z "$(git -C "$ROOT" status --porcelain)" ]] || { echo "Publishing requires a clean worktree" >&2; exit 1; }
git -C "$ROOT" fetch origin main
HEAD_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
[[ "$HEAD_COMMIT" == "$(git -C "$ROOT" rev-parse origin/main)" ]] || { echo "HEAD must be pushed to origin/main before publishing" >&2; exit 1; }

if [[ "$GATE_OWNED" == 1 ]]; then
  APK_RELEASE_METADATA_FILE="$GATE" "$ROOT/scripts/package-debug-apk.sh"
fi
PUBLISHED_AT="$(git -C "$ROOT" show -s --format=%cI "$HEAD_COMMIT" | python3 -c 'import datetime,sys; print(datetime.datetime.fromisoformat(sys.stdin.read().strip()).astimezone(datetime.timezone.utc).isoformat().replace("+00:00","Z"))')"
# Metadata is derived only from the gate output and the reviewed release description; minSdk comes
# from the packaged APK, never from a constant here.
python3 "$ROOT/scripts/lib/release_metadata.py" "$GATE" "$ROOT/android/releases" "$PUBLIC_BASE" "$META" "$HEAD_COMMIT" "$PUBLISHED_AT"
ARTIFACT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["artifact"])' "$GATE")"
FILE_NAME="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["fileName"])' "$META")"
VERSION_CODE="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["versionCode"])' "$META")"
REMOTE_TMP="/tmp/hermes-release-${VERSION_CODE}-${HEAD_COMMIT}"
[[ -z "$(git -C "$ROOT" status --porcelain)" ]] || { echo "Worktree changed during package gate" >&2; exit 1; }
[[ "$(git -C "$ROOT" rev-parse HEAD)" == "$HEAD_COMMIT" && "$HEAD_COMMIT" == "$(git -C "$ROOT" rev-parse origin/main)" ]] || { echo "HEAD changed during package gate" >&2; exit 1; }
ssh "$USER@$HOST" "umask 077; test ! -e '$REMOTE_TMP'; mkdir -- '$REMOTE_TMP'; mkdir -p -- '$REMOTE_TMP/deploy' '$REMOTE_TMP/release-server/src'"
scp "$ARTIFACT" "$USER@$HOST:$REMOTE_TMP/$FILE_NAME"
scp "$META" "$USER@$HOST:$REMOTE_TMP/metadata.json"
# Execute the publisher and schema from the exact clean origin/main commit being released. The
# server-installed copy may lag the repository; silently running it would bypass newly reviewed lock
# and durability fixes even though local tests passed.
scp "$ROOT/deploy/publish-release.mjs" "$USER@$HOST:$REMOTE_TMP/deploy/publish-release.mjs"
scp "$ROOT/release-server/src/schema.mjs" "$USER@$HOST:$REMOTE_TMP/release-server/src/schema.mjs"
ssh "$USER@$HOST" "flock --timeout 120 '$REMOTE_ROOT/.publish.kernel.lock' env PUBLISH_FLOCK_HELD=1 RELEASE_DATA_ROOT='$REMOTE_ROOT' node '$REMOTE_TMP/deploy/publish-release.mjs' '$REMOTE_TMP/$FILE_NAME' '$REMOTE_TMP/metadata.json'"

HTTP_CODE="$(curl --fail --silent --show-error --location --output "$DOWNLOADED" --write-out '%{http_code}' "$PUBLIC_BASE/releases/$FILE_NAME")"
[[ "$HTTP_CODE" == 200 ]]
python3 - "$GATE" "$DOWNLOADED" <<'PY'
import hashlib,json,os,sys
gate=json.load(open(sys.argv[1])); path=sys.argv[2]
digest=hashlib.sha256()
with open(path,'rb') as stream:
    for chunk in iter(lambda:stream.read(1024*1024),b''): digest.update(chunk)
if os.stat(path).st_size!=gate['sizeBytes'] or digest.hexdigest()!=gate['sha256']: raise SystemExit('public APK verification failed')
PY
curl --fail --silent --show-error "$PUBLIC_BASE/releases/index.json" > "$INDEX"
python3 - "$META" "$INDEX" <<'PY'
import json,sys
metadata=json.load(open(sys.argv[1])); index=json.load(open(sys.argv[2]))
versions=index.get('versions',[])
if not versions or index.get('latestVersionCode')!=max(v['versionCode'] for v in versions): raise SystemExit('public latestVersionCode mismatch')
entry=next((v for v in versions if v.get('versionCode')==metadata['versionCode']),None)
if entry != metadata: raise SystemExit('public index entry mismatch')
PY
echo "PUBLISH_RELEASE_OK URL=$PUBLIC_BASE/releases/$FILE_NAME"
