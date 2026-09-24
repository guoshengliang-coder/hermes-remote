#!/bin/sh
set -eu

desktop_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
build_root="$desktop_dir/build"
app="$build_root/Hermes Go Desktop.app"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$desktop_dir/Packaging/Info.plist")"
case "${DESKTOP_DMG_KIND:-dev}" in
  dev)
    dmg="$build_root/Hermes-Go-Desktop-$version-dev.dmg"
    ;;
  official)
    if [ -z "${SIGNING_IDENTITY:-}" ] || [ "$SIGNING_IDENTITY" = "-" ]; then
      echo "Official DMG requires a Developer ID signing identity." >&2
      exit 1
    fi
    dmg="$build_root/Hermes-Go-Desktop-$version.dmg"
    if [ -e "$dmg" ]; then
      echo "Official DMG already exists; refusing to overwrite it." >&2
      exit 1
    fi
    ;;
  *)
    echo "DESKTOP_DMG_KIND must be dev or official." >&2
    exit 1
    ;;
esac

"$desktop_dir/scripts/build-app.sh"

if [ "${DESKTOP_DMG_KIND:-dev}" = dev ]; then
  rm -f "$dmg"
fi
set -- -volname "Hermes Go Desktop" -srcfolder "$app" -format UDZO
if [ "${DESKTOP_DMG_KIND:-dev}" = dev ]; then
  set -- "$@" -ov
fi
hdiutil create "$@" "$dmg"
hdiutil verify "$dmg"

echo "DMG=$dmg"
