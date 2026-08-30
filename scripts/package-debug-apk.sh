#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
GRADLE_FILE="$ANDROID_DIR/app/build.gradle.kts"
ANDROID_README="$ANDROID_DIR/README.md"

read_version() {
  python3 - "$GRADLE_FILE" "$1" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
key = sys.argv[2]
pattern = rf'^val\s+{re.escape(key)}\s*=\s*(?:"([^"]+)"|(\d+))\s*$'
match = re.search(pattern, text, re.MULTILINE)
if not match:
    raise SystemExit(f"missing {key} in {sys.argv[1]}")
print(match.group(1) or match.group(2))
PY
}

VERSION_NAME="$(read_version appVersionName)"
VERSION_CODE="$(read_version appVersionCode)"
EXPECTED_CERT_SHA256="$(python3 - "$GRADLE_FILE" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(
    r'expectedDebugCertificateSha256\s*=\s*"([0-9A-Fa-f]+)"',
    text,
    re.MULTILINE,
)
if not match:
    raise SystemExit("missing expectedDebugCertificateSha256 in build.gradle.kts")
print(match.group(1).lower())
PY
)"
ARTIFACT="$ANDROID_DIR/app/build/outputs/apk/distribution/debug/Hermes-Remote-${VERSION_NAME}-debug.apk"

python3 - "$ANDROID_README" "$VERSION_NAME" <<'PY'
import sys

path, version = sys.argv[1:]
text = open(path, encoding="utf-8").read()
required = [
    f"Version {version}",
    f"Hermes-Remote-{version}-debug.apk",
]
missing = [value for value in required if value not in text]
if missing:
    raise SystemExit(f"android/README.md missing release references: {', '.join(missing)}")
PY

git -C "$ROOT" diff --check
(
  cd "$ANDROID_DIR"
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
)

if [[ ! -f "$ARTIFACT" ]]; then
  echo "Versioned artifact missing: $ARTIFACT" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT="$(python3 - "$SDK_ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1]) / "build-tools"
candidates = sorted(root.glob("*/aapt"), reverse=True)
if not candidates:
    raise SystemExit(f"aapt not found under {root}")
print(candidates[0])
PY
)"
APKSIGNER="${AAPT%/aapt}/apksigner"
BADGING="$(mktemp)"
SIGNING="$(mktemp)"
trap 'rm -f "$BADGING" "$SIGNING"' EXIT
"$AAPT" dump badging "$ARTIFACT" > "$BADGING"

python3 - "$BADGING" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
import re
import sys

path, expected_name, expected_code = sys.argv[1:]
text = open(path, encoding="utf-8").read()
match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text)
if not match:
    raise SystemExit("unable to read APK package metadata")
package, code, name = match.groups()
if package != "com.hermes.remote":
    raise SystemExit(f"unexpected applicationId: {package}")
if name != expected_name or code != expected_code:
    raise SystemExit(
        f"APK version mismatch: got {name}/{code}, expected {expected_name}/{expected_code}"
    )
PY

"$APKSIGNER" verify --print-certs "$ARTIFACT" > "$SIGNING"
ACTUAL_CERT_SHA256="$(python3 - "$SIGNING" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", text)
if not match:
    raise SystemExit("unable to read APK signing certificate SHA-256")
print(match.group(1).lower())
PY
)"
if [[ "$ACTUAL_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "APK signing certificate mismatch: got $ACTUAL_CERT_SHA256, expected $EXPECTED_CERT_SHA256" >&2
  exit 1
fi
SHA_LINE="$(shasum -a 256 "$ARTIFACT")"
SHA256="${SHA_LINE%% *}"
BYTES="$(stat -f '%z' "$ARTIFACT")"

echo
echo "APK_RELEASE_OK"
echo "VERSION_NAME=$VERSION_NAME"
echo "VERSION_CODE=$VERSION_CODE"
echo "ARTIFACT=$ARTIFACT"
echo "BYTES=$BYTES"
echo "CERT_SHA256=$ACTUAL_CERT_SHA256"
echo "SHA256=$SHA256"

if [[ -n "${APK_RELEASE_METADATA_FILE:-}" ]]; then
  umask 077
  python3 - "$APK_RELEASE_METADATA_FILE" "$VERSION_NAME" "$VERSION_CODE" "$ARTIFACT" "$BYTES" "$ACTUAL_CERT_SHA256" "$SHA256" <<'PY'
import json, os, sys, tempfile
target, name, code, artifact, size, cert, sha = sys.argv[1:]
directory = os.path.dirname(os.path.abspath(target))
fd, temporary = tempfile.mkstemp(dir=directory, prefix='.apk-release-', text=True)
try:
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        json.dump({'gate':'APK_RELEASE_OK','versionName':name,'versionCode':int(code),'artifact':artifact,'sizeBytes':int(size),'certificateSha256':cert,'sha256':sha}, stream)
        stream.write('\n')
    os.replace(temporary, target)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
PY
fi
