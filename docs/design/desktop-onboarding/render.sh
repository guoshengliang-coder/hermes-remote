#!/bin/sh
# Renders every screen of onboarding.html to its own PNG with headless Chrome (macOS).
# Usage: docs/design/desktop-onboarding/render.sh
set -eu
cd "$(dirname "$0")"
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
page="file://$(pwd)/onboarding.html"
screens="launch-checking launch-error signin-email signin-code signin-expired signin-email-dark
choice-new-mac choice-full remove-mac-sheet onboard-hermes-found onboard-hermes-missing
onboard-connect onboard-phone onboard-phone-done manage-only-overview menubar"
n=0
for s in $screens; do
  n=$((n + 1))
  height=880
  [ "$s" = menubar ] && height=560
  out=$(printf "%02d-%s.png" "$n" "$s")
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=2 --window-size=1260,"$height" \
    --screenshot="$(pwd)/$out" "$page?s=$s" >/dev/null 2>&1
  echo "$out"
done
