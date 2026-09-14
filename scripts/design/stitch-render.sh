#!/usr/bin/env bash
# Render a Stitch screen snapshot (docs/design/stitch/*.html) to a PNG at a phone viewport,
# using the Chrome already installed on this Mac. No extra dependencies.
#
# Why this exists: a Stitch screen is Tailwind HTML authored for a 390 CSS-px viewport, so
# 1 CSS px == 1 dp. Rendering it at the same width and density as the Roborazzi goldens
# (411dp @ 420dpi = 2.625x → 1078 px wide) gives a reference image the app screenshot can be
# laid over pixel for pixel. Stitch's own thumbnails are 163×512 and useless for this.
#
# Chrome quirk this works around: a desktop Chrome window will not go narrower than ~500 px,
# so a 390-px `--window-size` silently lays the page out wider and clips the right edge. The
# page is therefore loaded inside an <iframe> of the requested width, centred in a wider
# window, and the screenshot is cropped back to the iframe with macOS `sips` (crop is centred).
#
# Usage:
#   scripts/design/stitch-render.sh <in.html> <out.png> [--width 390] [--height 1223] [--scale 2]
#
# Presets used by docs/design/stitch/README.md:
#   canvas    --width 390 --height <stitch canvas height / 2> --scale 2      (design-size reference)
#   roborazzi --width 411 --height 891 --scale 2.625                          (overlay on goldens)
#
# Fonts and Tailwind are fetched from Google's CDNs at render time; without network the page
# falls back to system fonts and the PNG is NOT a faithful reference. Check the fonts loaded
# (Plus Jakarta Sans / JetBrains Mono look nothing like PingFang) before committing an image.
set -euo pipefail

IN="${1:?input html}"; OUT="${2:?output png}"; shift 2
W=390; H=1223; S=2
while [ $# -gt 0 ]; do
  case "$1" in
    --width)  W="$2"; shift 2 ;;
    --height) H="$2"; shift 2 ;;
    --scale)  S="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

CHROME="${STITCH_CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME (set STITCH_CHROME)" >&2; exit 1; }
IN_ABS="$(cd "$(dirname "$IN")" && pwd)/$(basename "$IN")"

WRAP="$(mktemp -t stitch-wrap).html"
RAW="$(mktemp -t stitch-raw).png"
trap 'rm -f "$WRAP" "$RAW"' EXIT
cat > "$WRAP" <<HTML
<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:#fff}iframe{border:0;display:block;margin:0 auto;width:${W}px;height:${H}px}</style>
</head><body><iframe src="file://${IN_ABS}"></iframe></body></html>
HTML

# Window is 200 px wider than the iframe so the iframe is centred with 100 px either side.
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor="$S" --window-size="$((W + 200)),$H" \
  --virtual-time-budget=10000 --screenshot="$RAW" "file://$WRAP" >/dev/null 2>&1

# Floor, not round: Roborazzi's 411dp @ 2.625x golden is 1078 px wide (1078.875 floored).
PW="$(awk -v w="$W" -v s="$S" 'BEGIN{printf "%d", int(w*s)}')"
PH="$(awk -v h="$H" -v s="$S" 'BEGIN{printf "%d", int(h*s)}')"
cp "$RAW" "$OUT"
sips -c "$PH" "$PW" "$OUT" >/dev/null
echo "$OUT ${PW}x${PH} (${W}x${H} css px @ ${S}x)"
