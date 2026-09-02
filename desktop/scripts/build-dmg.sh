#!/bin/sh
set -eu

desktop_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
build_root="$desktop_dir/build"
app="$build_root/Hermes Go Desktop.app"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$desktop_dir/Packaging/Info.plist")"
dmg="$build_root/Hermes-Go-Desktop-$version-dev.dmg"

"$desktop_dir/scripts/build-app.sh"

rm -f "$dmg"
hdiutil create \
  -volname "Hermes Go Desktop" \
  -srcfolder "$app" \
  -format UDZO \
  -ov \
  "$dmg"
hdiutil verify "$dmg"

echo "DMG=$dmg"
