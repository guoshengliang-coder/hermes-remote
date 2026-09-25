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

if [ "$(plutil -extract CFBundleIconFile raw "$app/Contents/Info.plist")" != "AppIcon" ]; then
  echo "Packaged Desktop app must declare AppIcon as its bundle icon." >&2
  exit 1
fi
if [ "$(plutil -extract LSUIElement raw "$app/Contents/Info.plist")" != "false" ]; then
  echo "Packaged Desktop app must remain visible in the Dock." >&2
  exit 1
fi

if [ -n "${HERMES_GO_ACCOUNT_GATEWAY_URL:-}" ]; then
  plutil -replace HermesGoAccountGatewayURL -string "$HERMES_GO_ACCOUNT_GATEWAY_URL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_GOOGLE_MACOS_CLIENT_ID:-}" ]; then
  plutil -replace HermesGoGoogleMacOSClientID -string "$HERMES_GO_GOOGLE_MACOS_CLIENT_ID" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_MANAGED_BOOTSTRAP_ENABLED:-}" ]; then
  case "$HERMES_GO_MANAGED_BOOTSTRAP_ENABLED" in
    0) plutil -replace HermesGoManagedBootstrapEnabled -bool false "$app/Contents/Info.plist" ;;
    1) plutil -replace HermesGoManagedBootstrapEnabled -bool true "$app/Contents/Info.plist" ;;
    *) echo "HERMES_GO_MANAGED_BOOTSTRAP_ENABLED must be 0 or 1." >&2; exit 1 ;;
  esac
fi
if [ -n "${HERMES_GO_COMPONENT_PREFLIGHT_ENABLED:-}" ]; then
  case "$HERMES_GO_COMPONENT_PREFLIGHT_ENABLED" in
    0) plutil -replace HermesGoComponentPreflightEnabled -bool false "$app/Contents/Info.plist" ;;
    1) plutil -replace HermesGoComponentPreflightEnabled -bool true "$app/Contents/Info.plist" ;;
    *) echo "HERMES_GO_COMPONENT_PREFLIGHT_ENABLED must be 0 or 1." >&2; exit 1 ;;
  esac
fi
if [ -n "${HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL:-}" ]; then
  plutil -replace HermesGoDesktopComponentManifestURL -string "$HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_MANIFEST_URL:-}" ]; then
  plutil -replace HermesGoDesktopReleaseManifestURL -string "$HERMES_GO_DESKTOP_RELEASE_MANIFEST_URL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN:-}" ]; then
  plutil -replace HermesGoDesktopReleaseArtifactOrigin -string "$HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_CHANNEL:-}" ]; then
  plutil -replace HermesGoDesktopReleaseChannel -string "$HERMES_GO_DESKTOP_RELEASE_CHANNEL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE:-}" ]; then
  plutil -replace HermesGoDesktopReleaseArchitecture -string "$HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID:-}" ]; then
  plutil -replace HermesGoDesktopReleaseSigningKeyID -string "$HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY:-}" ]; then
  plutil -replace HermesGoDesktopReleaseSigningPublicKey -string "$HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_RELEASE_SIGNING_KEYS:-}" ]; then
  plutil -replace HermesGoDesktopReleaseSigningKeys -string "$HERMES_GO_DESKTOP_RELEASE_SIGNING_KEYS" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT:-}" ]; then
  plutil -replace HermesGoDesktopHermesRuntimeContract -string "$HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_APP_UPDATE_ENABLED:-}" ]; then
  case "$HERMES_GO_APP_UPDATE_ENABLED" in
    0) plutil -replace HermesGoDesktopAppUpdateEnabled -bool false "$app/Contents/Info.plist" ;;
    1) plutil -replace HermesGoDesktopAppUpdateEnabled -bool true "$app/Contents/Info.plist" ;;
    *) echo "HERMES_GO_APP_UPDATE_ENABLED must be 0 or 1." >&2; exit 1 ;;
  esac
fi
if [ -n "${HERMES_GO_APP_UPDATE_INDEX_URL:-}" ]; then
  plutil -replace HermesGoDesktopAppUpdateIndexURL -string "$HERMES_GO_APP_UPDATE_INDEX_URL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_APP_UPDATE_CHANNEL:-}" ]; then
  plutil -replace HermesGoDesktopAppUpdateChannel -string "$HERMES_GO_APP_UPDATE_CHANNEL" "$app/Contents/Info.plist"
fi
if [ -n "${HERMES_GO_APP_UPDATE_ARCHITECTURE:-}" ]; then
  plutil -replace HermesGoDesktopAppUpdateArchitecture -string "$HERMES_GO_APP_UPDATE_ARCHITECTURE" "$app/Contents/Info.plist"
fi

bootstrap_enabled="$(plutil -extract HermesGoManagedBootstrapEnabled raw "$app/Contents/Info.plist")"
component_enabled="$(plutil -extract HermesGoComponentPreflightEnabled raw "$app/Contents/Info.plist")"
release_index_url="$(plutil -extract HermesGoDesktopReleaseManifestURL raw "$app/Contents/Info.plist")"
component_index_url="$(plutil -extract HermesGoDesktopComponentManifestURL raw "$app/Contents/Info.plist")"
artifact_origin="$(plutil -extract HermesGoDesktopReleaseArtifactOrigin raw "$app/Contents/Info.plist")"
release_channel="$(plutil -extract HermesGoDesktopReleaseChannel raw "$app/Contents/Info.plist")"
release_architecture="$(plutil -extract HermesGoDesktopReleaseArchitecture raw "$app/Contents/Info.plist")"
legacy_key_id="$(plutil -extract HermesGoDesktopReleaseSigningKeyID raw "$app/Contents/Info.plist")"
legacy_public_key="$(plutil -extract HermesGoDesktopReleaseSigningPublicKey raw "$app/Contents/Info.plist")"
signing_keys="$(plutil -extract HermesGoDesktopReleaseSigningKeys raw "$app/Contents/Info.plist")"
app_update_enabled="$(plutil -extract HermesGoDesktopAppUpdateEnabled raw "$app/Contents/Info.plist")"
app_update_index_url="$(plutil -extract HermesGoDesktopAppUpdateIndexURL raw "$app/Contents/Info.plist")"
app_update_channel="$(plutil -extract HermesGoDesktopAppUpdateChannel raw "$app/Contents/Info.plist")"
app_update_architecture="$(plutil -extract HermesGoDesktopAppUpdateArchitecture raw "$app/Contents/Info.plist")"

if [ "$bootstrap_enabled" = "true" ] && [ -z "$release_index_url" ]; then
  echo "Managed bootstrap is enabled but its stable release index URL is empty." >&2
  exit 1
fi
if [ "$component_enabled" = "true" ] && [ -z "$component_index_url" ]; then
  echo "Component preflight is enabled but its stable component index URL is empty." >&2
  exit 1
fi
if [ "$bootstrap_enabled" = "true" ] || [ "$component_enabled" = "true" ]; then
  if [ -z "$artifact_origin" ] || [ -z "$release_channel" ] || [ -z "$release_architecture" ]; then
    echo "Enabled Desktop release discovery requires origin, channel, and architecture." >&2
    exit 1
  fi
  if [ -n "$signing_keys" ] && { [ -n "$legacy_key_id" ] || [ -n "$legacy_public_key" ]; }; then
    echo "Configure either the multi-key trust set or the legacy single key, never both." >&2
    exit 1
  fi
  if [ -z "$signing_keys" ] && { [ -z "$legacy_key_id" ] || [ -z "$legacy_public_key" ]; }; then
    echo "Enabled Desktop release discovery requires a complete signing trust configuration." >&2
    exit 1
  fi
fi
if [ "$app_update_enabled" = "true" ]; then
  if [ -z "$app_update_index_url" ] || [ -z "$app_update_channel" ] || [ -z "$app_update_architecture" ]; then
    echo "App update checking is enabled but its index URL, channel, and architecture are not all set." >&2
    exit 1
  fi
fi

echo "DESKTOP_RELEASE_CONFIGURATION"
echo "MANAGED_BOOTSTRAP_ENABLED=$bootstrap_enabled"
echo "COMPONENT_PREFLIGHT_ENABLED=$component_enabled"
echo "RELEASE_INDEX_URL=$release_index_url"
echo "COMPONENT_INDEX_URL=$component_index_url"
echo "ARTIFACT_ORIGIN=$artifact_origin"
echo "RELEASE_CHANNEL=$release_channel"
echo "RELEASE_ARCHITECTURE=$release_architecture"
echo "APP_UPDATE_ENABLED=$app_update_enabled"
echo "APP_UPDATE_INDEX_URL=$app_update_index_url"
if [ -n "$signing_keys" ]; then
  echo "SIGNING_TRUST=multi-key"
elif [ -n "$legacy_key_id" ]; then
  echo "SIGNING_TRUST=legacy-single-key"
else
  echo "SIGNING_TRUST=disabled"
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

if [ ! -s "$app/Contents/Resources/AppIcon.icns" ]; then
  echo "Packaged Desktop app is missing its AppIcon.icns resource." >&2
  exit 1
fi

signing_identity="${SIGNING_IDENTITY:--}"
if [ "$signing_identity" = "-" ]; then
  codesign --force --deep --sign - "$app"
else
  codesign --force --deep --options runtime --timestamp --sign "$signing_identity" "$app"
fi
codesign --verify --deep --strict "$app"

echo "APP=$app"
