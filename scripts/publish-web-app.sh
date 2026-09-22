#!/bin/bash
# Publishes the Web app (web/dist) to the Gateway host, or rolls it back. Mirrors
# scripts/publish-android-apk.sh: a clean tree at origin/main, the package gate run here, the
# artifact and the reviewed installer copied into a private temporary directory on the host, and the
# installer from this exact commit (never a stale host copy) run there under an exclusive lock.
#
#   scripts/publish-web-app.sh             build, test, package, install, switch `current`
#   scripts/publish-web-app.sh --rollback  point `current` back at the release it replaced
#
# The Gateway serves <root>/current from a read-only bind mount and reads files per request, so
# neither path restarts it. Before the R5-F7 rollout the edge does not route /app/ to the Gateway,
# so a first publish is dark; set WEB_PUBLISH_VERIFY_PUBLIC=1 once /app/ is live to compare the
# public bytes with the local build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${WEB_PUBLISH_SSH_HOST:-mrlgs.net}"
USER="${WEB_PUBLISH_SSH_USER:-kkk}"
REMOTE_ROOT="${WEB_PUBLISH_REMOTE_ROOT:-/opt/hermes-go/web}"
PUBLIC_BASE="${WEB_PUBLISH_PUBLIC_BASE_URL:-https://mrlgs.net}"
[[ "$USER" =~ ^[A-Za-z0-9._-]+$ && "$USER" != -* ]] || { echo "Invalid WEB_PUBLISH_SSH_USER" >&2; exit 1; }
[[ "$HOST" == "mrlgs.net" ]] || { echo "Invalid WEB_PUBLISH_SSH_HOST" >&2; exit 1; }
[[ "$REMOTE_ROOT" == "/opt/hermes-go/web" ]] || { echo "Invalid WEB_PUBLISH_REMOTE_ROOT" >&2; exit 1; }
[[ "$PUBLIC_BASE" == "https://mrlgs.net" ]] || { echo "Invalid WEB_PUBLISH_PUBLIC_BASE_URL" >&2; exit 1; }

MODE="publish"
if [[ $# -gt 0 ]]; then
  [[ $# -eq 1 && "$1" == "--rollback" ]] || { echo "usage: $0 [--rollback]" >&2; exit 2; }
  MODE="rollback"
fi

OUT="$(mktemp -d)"
REMOTE_TMP=""
cleanup() {
  rm -rf "$OUT"
  if [[ -n "$REMOTE_TMP" ]]; then ssh "$USER@$HOST" "rm -rf -- '$REMOTE_TMP'" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT INT TERM

dirty() { git -C "$ROOT" status --porcelain; }
[[ -z "$(dirty)" ]] || { echo "Publishing requires a clean worktree; these paths are not clean:" >&2; dirty >&2; exit 1; }
git -C "$ROOT" fetch origin main
HEAD_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
[[ "$HEAD_COMMIT" == "$(git -C "$ROOT" rev-parse origin/main)" ]] || { echo "HEAD must be pushed to origin/main before publishing" >&2; exit 1; }

REMOTE_TMP="/tmp/hermes-web-${HEAD_COMMIT}-$$"
ssh "$USER@$HOST" "umask 077; test ! -e '$REMOTE_TMP'; mkdir -- '$REMOTE_TMP'"
scp "$ROOT/deploy/publish-web-app.mjs" "$USER@$HOST:$REMOTE_TMP/publish-web-app.mjs"
LOCK="sudo -n flock --timeout 120 '$REMOTE_ROOT/.publish.lock'"

if [[ "$MODE" == "rollback" ]]; then
  ssh "$USER@$HOST" "$LOCK node '$REMOTE_TMP/publish-web-app.mjs' rollback --root '$REMOTE_ROOT'"
  exit 0
fi

# The package gate: the same checks CI's `web` job runs, then a reproducible artifact.
(cd "$ROOT/web" && npm ci --ignore-scripts && npm run typecheck && npm test && npm run build)
VERSION="$(node -p 'require(process.argv[1]).version' "$ROOT/web/package.json")"
PACKAGED="$(node "$ROOT/scripts/package-web-app.mjs" "$ROOT/web/dist" "$OUT" "$VERSION" "$HEAD_COMMIT")"
ARCHIVE="$(sed -n 's/^ARCHIVE=//p' <<<"$PACKAGED")"
MANIFEST="$(sed -n 's/^MANIFEST=//p' <<<"$PACKAGED")"
RELEASE_ID="$(sed -n 's/^RELEASE_ID=//p' <<<"$PACKAGED")"
[[ -z "$(dirty)" && "$(git -C "$ROOT" rev-parse HEAD)" == "$HEAD_COMMIT" ]] || { echo "Worktree changed during the package gate" >&2; exit 1; }

scp "$ARCHIVE" "$USER@$HOST:$REMOTE_TMP/web.tar.gz"
scp "$MANIFEST" "$USER@$HOST:$REMOTE_TMP/web.manifest.json"
ssh "$USER@$HOST" "$LOCK node '$REMOTE_TMP/publish-web-app.mjs' publish --root '$REMOTE_ROOT' --archive '$REMOTE_TMP/web.tar.gz' --manifest '$REMOTE_TMP/web.manifest.json'"

if [[ "${WEB_PUBLISH_VERIFY_PUBLIC:-0}" == 1 ]]; then
  expected="$(shasum -a 256 "$ROOT/web/dist/index.html" | cut -d' ' -f1)"
  actual="$(curl -fsS "$PUBLIC_BASE/app/" | shasum -a 256 | cut -d' ' -f1)"
  [[ "$expected" == "$actual" ]] || { echo "Public /app/ does not serve the published index.html" >&2; exit 1; }
fi
echo "WEB_RELEASE_OK=$RELEASE_ID"
