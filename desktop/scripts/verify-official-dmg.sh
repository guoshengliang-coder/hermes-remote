#!/bin/sh
set -eu

if [ "$#" -ne 8 ]; then
  echo "Usage: verify-official-dmg.sh DMG VERSION BUILD TEAM_ID RELEASE_INDEX_URL COMPONENT_INDEX_URL KEY_ID PUBLIC_KEY" >&2
  exit 64
fi

dmg="$1"
expected_version="$2"
expected_build="$3"
expected_team="$4"
expected_release_index="$5"
expected_component_index="$6"
expected_key_id="$7"
expected_public_key="$8"

case "$expected_version" in
  *[!0-9.]*|'' ) echo "Invalid expected version." >&2; exit 64 ;;
esac
case "$expected_build" in
  *[!0-9]*|'' ) echo "Invalid expected build." >&2; exit 64 ;;
esac
case "$expected_team" in
  *[!A-Z0-9]*|'' ) echo "Invalid expected team ID." >&2; exit 64 ;;
esac
case "$dmg" in
  /*) ;;
  *) echo "DMG path must be absolute." >&2; exit 64 ;;
esac
[ -f "$dmg" ] && [ ! -L "$dmg" ] || { echo "DMG is missing or a symlink." >&2; exit 66; }
[ "$(basename "$dmg")" = "Hermes-Go-Desktop-$expected_version.dmg" ] || {
  echo "DMG filename does not match the expected official version." >&2
  exit 65
}

verification_root="$(mktemp -d "${TMPDIR:-/tmp}/hermes-official-dmg.XXXXXXXX")"
mount_point="$verification_root/mount"
mkdir "$mount_point"
mounted=0
cleanup() {
  if [ "$mounted" -eq 1 ]; then
    hdiutil detach "$mount_point" -quiet || true
  fi
  rm -rf "$verification_root"
}
trap cleanup EXIT HUP INT TERM

xcrun stapler validate "$dmg"
hdiutil verify "$dmg"
hdiutil attach -readonly -nobrowse -mountpoint "$mount_point" "$dmg" >/dev/null
mounted=1
app="$mount_point/Hermes Go Desktop.app"
plist="$app/Contents/Info.plist"
[ -f "$plist" ] || { echo "Packaged app is missing Info.plist." >&2; exit 65; }

check_plist() {
  actual="$(/usr/libexec/PlistBuddy -c "Print :$1" "$plist")"
  [ "$actual" = "$2" ] || {
    echo "Packaged Info.plist field $1 does not match the release input." >&2
    exit 65
  }
}
check_plist CFBundleShortVersionString "$expected_version"
check_plist CFBundleVersion "$expected_build"
check_plist HermesGoDesktopReleaseManifestURL "$expected_release_index"
check_plist HermesGoDesktopComponentManifestURL "$expected_component_index"
check_plist HermesGoDesktopReleaseSigningKeyID "$expected_key_id"
check_plist HermesGoDesktopReleaseSigningPublicKey "$expected_public_key"
check_plist HermesGoManagedBootstrapEnabled true
check_plist HermesGoComponentPreflightEnabled true

codesign --verify --deep --strict --verbose=2 "$app"
signature_details="$(codesign --display --verbose=4 "$app" 2>&1)"
printf '%s\n' "$signature_details" | grep -Fx "TeamIdentifier=$expected_team" >/dev/null || {
  echo "App is not signed by the expected Developer ID team." >&2
  exit 65
}
printf '%s\n' "$signature_details" | grep -F 'Authority=Developer ID Application:' >/dev/null || {
  echo "App is not Developer ID Application signed." >&2
  exit 65
}
printf '%s\n' "$signature_details" | grep -F '(runtime)' >/dev/null || {
  echo "App is missing the hardened runtime signing option." >&2
  exit 65
}
printf '%s\n' "$signature_details" | grep -F 'Timestamp=' >/dev/null || {
  echo "App is missing a secure signing timestamp." >&2
  exit 65
}
spctl --assess --type execute --verbose=2 "$app"

echo "OFFICIAL_DESKTOP_DMG_VERIFIED"
