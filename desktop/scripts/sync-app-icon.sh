#!/bin/sh
set -eu

desktop_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
project_root="$(CDPATH= cd -- "$desktop_dir/.." && pwd)"

cp \
  "$project_root/android/app/src/main/ic_launcher-playstore.png" \
  "$desktop_dir/Packaging/AppIcon.png"

echo "Desktop icon synchronized from the canonical Hermes GO app icon."
