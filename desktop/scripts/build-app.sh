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

validate_package_identifier() {
  value="$1"
  label="$2"
  maximum="$3"
  case "$value" in
    *[!A-Za-z0-9.-]*|.*|*.|*..*)
      echo "$label must contain only letters, digits, dots, and hyphens, with no empty components." >&2
      exit 1
      ;;
  esac
  if [ "${#value}" -gt "$maximum" ]; then
    echo "$label must contain at most $maximum characters." >&2
    exit 1
  fi
}

if [ -n "${HERMES_GO_STORAGE_NAMESPACE:-}" ]; then
  validate_package_identifier "$HERMES_GO_STORAGE_NAMESPACE" HERMES_GO_STORAGE_NAMESPACE 64
fi
if [ -n "${HERMES_GO_BUNDLE_IDENTIFIER:-}" ]; then
  validate_package_identifier "$HERMES_GO_BUNDLE_IDENTIFIER" HERMES_GO_BUNDLE_IDENTIFIER 255
fi
if [ -n "${HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE:-}" ]; then
  if [ ! -f "$HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE" ]; then
    echo "HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE must point to a Google OAuth client JSON file." >&2
    exit 1
  fi
  oauth_file_client_id="$(
    ruby -rjson -e 'print JSON.parse(File.read(ARGV.fetch(0))).fetch("installed").fetch("client_id")' \
      "$HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE"
  )"
  oauth_file_client_secret="$(
    ruby -rjson -e 'print JSON.parse(File.read(ARGV.fetch(0))).fetch("installed").fetch("client_secret")' \
      "$HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE"
  )"
  if [ -z "$oauth_file_client_id" ] || [ -z "$oauth_file_client_secret" ]; then
    echo "The Google OAuth client JSON is missing its installed client ID or client secret." >&2
    exit 1
  fi
  if [ -n "${HERMES_GO_GOOGLE_MACOS_CLIENT_ID:-}" ] && [ "$oauth_file_client_id" != "$HERMES_GO_GOOGLE_MACOS_CLIENT_ID" ]; then
    echo "The Google OAuth client JSON does not match HERMES_GO_GOOGLE_MACOS_CLIENT_ID." >&2
    exit 1
  fi
fi

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
if [ -n "${HERMES_GO_GOOGLE_MACOS_CLIENT_SECRET_FILE:-}" ]; then
  plutil -replace HermesGoGoogleMacOSClientSecret -string "$oauth_file_client_secret" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_STORAGE_NAMESPACE:-}" ]; then
  plutil -replace HermesGoStorageNamespace -string "$HERMES_GO_STORAGE_NAMESPACE" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_BUNDLE_IDENTIFIER:-}" ]; then
  plutil -replace CFBundleIdentifier -string "$HERMES_GO_BUNDLE_IDENTIFIER" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DISPLAY_NAME:-}" ]; then
  plutil -replace CFBundleDisplayName -string "$HERMES_GO_DISPLAY_NAME" "$app/Contents/Info.plist"
  plutil -replace CFBundleName -string "$HERMES_GO_DISPLAY_NAME" "$app/Contents/Info.plist"
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
