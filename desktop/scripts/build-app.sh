#!/bin/sh
set -eu

desktop_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
project_root="$(CDPATH= cd -- "$desktop_dir/.." && pwd)"
build_root="$desktop_dir/build"
swift_scratch="$build_root/swift"
app="$build_root/Hermes Go Desktop.app"
icon_source="$desktop_dir/Packaging/AppIcon.png"
canonical_icon="$project_root/android/app/src/main/ic_launcher-playstore.png"
module_cache="$build_root/module-cache"
swiftpm_module_cache="$build_root/swiftpm-module-cache"

if ! cmp -s "$canonical_icon" "$icon_source"; then
  echo "Desktop AppIcon.png differs from the canonical Android app icon." >&2
  echo "Run desktop/scripts/sync-app-icon.sh before packaging." >&2
  exit 1
fi

mkdir -p "$build_root" "$module_cache" "$swiftpm_module_cache"

CLANG_MODULE_CACHE_PATH="$module_cache" \
SWIFTPM_MODULECACHE_OVERRIDE="$swiftpm_module_cache" \
swift build \
  --package-path "$desktop_dir" \
  --scratch-path "$swift_scratch" \
  -c release

bin_dir="$(
  CLANG_MODULE_CACHE_PATH="$module_cache" \
  SWIFTPM_MODULECACHE_OVERRIDE="$swiftpm_module_cache" \
  swift build \
    --package-path "$desktop_dir" \
    --scratch-path "$swift_scratch" \
    -c release \
    --show-bin-path
)"

rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
install -m 755 "$bin_dir/HermesGoDesktop" "$app/Contents/MacOS/HermesGoDesktop"
install -m 644 "$desktop_dir/Packaging/Info.plist" "$app/Contents/Info.plist"

if [ -n "${HERMES_GO_ACCOUNT_GATEWAY_URL:-}" ]; then
  plutil -replace HermesGoAccountGatewayURL -string "$HERMES_GO_ACCOUNT_GATEWAY_URL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_GOOGLE_MACOS_CLIENT_ID:-}" ]; then
  plutil -replace HermesGoGoogleMacOSClientID -string "$HERMES_GO_GOOGLE_MACOS_CLIENT_ID" "$app/Contents/Info.plist"
fi

iconset="$build_root/AppIcon.iconset"
rm -rf "$iconset"
mkdir -p "$iconset"
sips -z 16 16 "$icon_source" --out "$iconset/icon_16x16.png" >/dev/null
sips -z 32 32 "$icon_source" --out "$iconset/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$icon_source" --out "$iconset/icon_32x32.png" >/dev/null
sips -z 64 64 "$icon_source" --out "$iconset/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$icon_source" --out "$iconset/icon_128x128.png" >/dev/null
sips -z 256 256 "$icon_source" --out "$iconset/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$icon_source" --out "$iconset/icon_256x256.png" >/dev/null
sips -z 512 512 "$icon_source" --out "$iconset/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$icon_source" --out "$iconset/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$icon_source" --out "$iconset/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"

signing_identity="${SIGNING_IDENTITY:--}"
if [ "$signing_identity" = "-" ]; then
  codesign --force --deep --sign - "$app"
else
  codesign --force --deep --options runtime --timestamp --sign "$signing_identity" "$app"
fi
codesign --verify --deep --strict "$app"

echo "APP=$app"
