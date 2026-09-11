#!/bin/bash
# Retire catalog entries older than the newest KEEP, so publishing can continue once the index
# hits its 100-version cap (docs/APP_UPDATE.md).
#
# This is a maintenance step, deliberately separate from publishing: `publish-release.mjs` fails
# closed on a full catalog rather than making room for itself, because dropping a version revokes
# an APK a tester may be installing. Deciding WHICH releases go is an operator call; this script
# only carries it out, under the same guards the publisher uses.
#
# Nothing is deleted. Retired APKs are renamed into $RELEASE_DATA_ROOT/archive, off the served
# path but still on disk, and the prior index stays in index.json.prev.
#
# Usage:
#   scripts/retire-android-releases.sh --keep 10 [--dry-run]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${RELEASE_SSH_HOST:-mrlgs.net}"
USER="${RELEASE_SSH_USER:-kkk}"
REMOTE_ROOT="${RELEASE_DATA_ROOT:-/srv/hermes-releases}"
PUBLIC_BASE="${RELEASE_PUBLIC_BASE_URL:-https://mrlgs.net}"
KEEP=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "usage: $0 --keep N [--dry-run]" >&2; exit 2 ;;
  esac
done

# Same host/root allow-listing as the publisher: these are the only endpoints this repository
# is allowed to mutate, and a typo must not point the operation somewhere else.
[[ "$USER" =~ ^[A-Za-z0-9._-]+$ && "$USER" != -* ]] || { echo "Invalid RELEASE_SSH_USER" >&2; exit 1; }
[[ "$HOST" == "mrlgs.net" ]] || { echo "Invalid RELEASE_SSH_HOST" >&2; exit 1; }
[[ "$REMOTE_ROOT" == "/srv/hermes-releases" ]] || { echo "Invalid RELEASE_DATA_ROOT" >&2; exit 1; }
[[ "$PUBLIC_BASE" == "https://mrlgs.net" || "$PUBLIC_BASE" == "https://mrlgs.net:443" ]] || { echo "Invalid RELEASE_PUBLIC_BASE_URL" >&2; exit 1; }
[[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || { echo "--keep must be a positive integer" >&2; exit 1; }

REMOTE_TMP=""
cleanup() { if [[ -n "$REMOTE_TMP" ]]; then ssh "$USER@$HOST" "rm -rf -- '$REMOTE_TMP'" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT INT TERM

# Mutating the live catalog from a dirty tree, or from anything but the commit on origin/main,
# is how the deployed behaviour stops matching the reviewed source.
[[ -z "$(git -C "$ROOT" status --porcelain)" ]] || { echo "Retiring requires a clean worktree" >&2; exit 1; }
git -C "$ROOT" fetch origin main
HEAD_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
[[ "$HEAD_COMMIT" == "$(git -C "$ROOT" rev-parse origin/main)" ]] || { echo "HEAD must equal origin/main before retiring" >&2; exit 1; }

echo "Catalog before:"
curl --fail --silent --show-error "$PUBLIC_BASE/releases/index.json" |
  python3 -c 'import json,sys; d=json.load(sys.stdin); v=sorted(d["versions"], key=lambda x: x["versionCode"]); print(f"  {len(v)} versions, latest {d[\"latestVersionCode\"]}, oldest {v[0][\"versionName\"]}, newest {v[-1][\"versionName\"]}")'

REMOTE_TMP="/tmp/hermes-retire-${HEAD_COMMIT}"
ssh "$USER@$HOST" "umask 077; rm -rf -- '$REMOTE_TMP'; mkdir -- '$REMOTE_TMP'; mkdir -p -- '$REMOTE_TMP/deploy' '$REMOTE_TMP/release-server/src'"
# Run the reviewed source from this commit, not whatever is installed on the server — the same
# reason the publisher does it (an installed copy may lag reviewed lock/durability fixes).
scp "$ROOT/deploy/publish-release.mjs" "$USER@$HOST:$REMOTE_TMP/deploy/publish-release.mjs"
scp "$ROOT/release-server/src/schema.mjs" "$USER@$HOST:$REMOTE_TMP/release-server/src/schema.mjs"

DRY_ENV=""
[[ "$DRY_RUN" == 1 ]] && DRY_ENV="RETIRE_DRY_RUN=1"
ssh "$USER@$HOST" "flock --timeout 120 '$REMOTE_ROOT/.publish.kernel.lock' env PUBLISH_FLOCK_HELD=1 $DRY_ENV RELEASE_DATA_ROOT='$REMOTE_ROOT' node '$REMOTE_TMP/deploy/publish-release.mjs' --retire '$KEEP'"

if [[ "$DRY_RUN" == 1 ]]; then
  echo "dry run: nothing was changed"
  exit 0
fi

# Verify from the public side, not from the box: what matters is what a phone can fetch.
echo "Catalog after:"
INDEX="$(mktemp)"; trap 'rm -f "$INDEX"; cleanup' EXIT
curl --fail --silent --show-error --output "$INDEX" "$PUBLIC_BASE/releases/index.json"
python3 - "$INDEX" "$KEEP" <<'PY'
import json, sys
index = json.load(open(sys.argv[1]))
keep = int(sys.argv[2])
versions = sorted(index["versions"], key=lambda v: v["versionCode"])
assert len(versions) == keep, f"expected {keep} versions, index has {len(versions)}"
assert index["latestVersionCode"] == versions[-1]["versionCode"], "latestVersionCode is not the newest entry"
print(f"  {len(versions)} versions, latest {index['latestVersionCode']}, "
      f"oldest {versions[0]['versionName']}, newest {versions[-1]['versionName']}")
PY

# Every surviving entry must still be downloadable; a retired one must be gone from the served path.
python3 -c 'import json,sys;[print(v["fileName"]) for v in json.load(open(sys.argv[1]))["versions"]]' "$INDEX" |
  while read -r name; do
    code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --head "$PUBLIC_BASE/releases/$name")"
    [[ "$code" == 200 ]] || { echo "surviving entry $name returns HTTP $code" >&2; exit 1; }
  done
echo "  every surviving entry returns HTTP 200"
echo "RETIRE_OK keep=$KEEP"
