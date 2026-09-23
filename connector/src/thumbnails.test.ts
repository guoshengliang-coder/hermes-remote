import assert from "node:assert/strict";
import test from "node:test";
import {
  JPEG_THUMBNAIL,
  PNG_THUMBNAIL,
  THUMBNAIL_WIDTHS,
  isThumbnailable,
  isWorthDownscaling,
  parseHasAlpha,
  parseLongestSide,
  snapThumbnailWidth,
  thumbnailFileName,
  thumbnailFormatFor,
} from "./thumbnails.js";

const source = { size: 1_400_000, mtimeMs: 1_758_000_000_123.4 };

test("only raster images are downscaled", () => {
  for (const path of ["/a/b.png", "/a/b.JPG", "/a/b.jpeg", "/a/b.gif", "/a/b.webp", "/a/b.heic", "/a/b.tiff"]) {
    assert.equal(isThumbnailable(path), true, path);
  }
  // SVG is markup: rasterizing it is a different picture, not a smaller one. The rest are not
  // images at all and must reach the client untouched.
  for (const path of ["/a/b.svg", "/a/b.pdf", "/a/b.txt", "/a/b", "/a/b.mp4", "/a/b.zip"]) {
    assert.equal(isThumbnailable(path), false, path);
  }
});

test("a requested width snaps to a tier instead of being honoured verbatim", () => {
  const largest = THUMBNAIL_WIDTHS[THUMBNAIL_WIDTHS.length - 1]!;
  // Between tiers, round up: the caller gets a smaller payload rather than an error.
  assert.equal(snapThumbnailWidth("800"), THUMBNAIL_WIDTHS.find((w) => w >= 800));
  assert.equal(snapThumbnailWidth(String(largest)), largest);
  // Above every tier is the biggest preview, not the original — the caller asked to be spared it.
  assert.equal(snapThumbnailWidth("99999"), largest);
});

test("anything that is not a width means no thumbnail at all", () => {
  for (const raw of [undefined, "", "0", "-1", "12.5", "1e3", "abc", " 1080", "1080px", "999999"]) {
    assert.equal(snapThumbnailWidth(raw), null, JSON.stringify(raw));
  }
});

test("JPEG unless the source really carries transparency", () => {
  // Measured 2026-09-23: PNG sources land at 4.8% of their bytes as JPEG and 24.3% as PNG, so the
  // 59-of-60 without alpha must not pay for the one that has it.
  assert.deepEqual(thumbnailFormatFor("/a/shot.png", false), JPEG_THUMBNAIL);
  assert.deepEqual(thumbnailFormatFor("/a/logo.png", true), PNG_THUMBNAIL);
  assert.deepEqual(thumbnailFormatFor("/a/sticker.webp", true), PNG_THUMBNAIL);
  // A format that cannot hold alpha is never asked, so a stray "yes" cannot widen the output.
  assert.deepEqual(thumbnailFormatFor("/a/photo.jpg", true), JPEG_THUMBNAIL);
  assert.deepEqual(thumbnailFormatFor("/a/photo.HEIC", true), JPEG_THUMBNAIL);
});

test("an unreadable alpha answer is treated as having alpha", () => {
  assert.equal(parseHasAlpha("/a/b.png\n  hasAlpha: no\n"), false);
  assert.equal(parseHasAlpha("/a/b.png\n  hasAlpha: yes\n"), true);
  // Rather than flatten a picture we could not ask about onto black.
  assert.equal(parseHasAlpha(""), true);
  assert.equal(parseHasAlpha("Error 4: no such file"), true);
});

test("dimensions are read off the same sips output as the alpha answer", () => {
  const output = "/a/b.png\n  hasAlpha: no\n  pixelWidth: 2400\n  pixelHeight: 1600\n";
  assert.equal(parseLongestSide(output), 2400);
  assert.equal(parseLongestSide("/a/b.png\n  pixelWidth: 600\n  pixelHeight: 2400\n"), 2400);
  // Unreadable dimensions are not zero dimensions.
  assert.equal(parseLongestSide("/a/b.png\n  hasAlpha: no\n"), null);
  assert.equal(parseLongestSide("/a/b.png\n  pixelWidth: 0\n  pixelHeight: 0\n"), null);
  assert.equal(parseLongestSide(""), null);
});

test("an image already at or below the tier is left alone", () => {
  // `sips -Z` is a FIT, not a downscale: measured 2026-09-23, a 120x80 source comes back 1080x720.
  // Sending one through would cost bytes and hand back a blurrier picture than the original.
  assert.equal(isWorthDownscaling({ hasAlpha: false, longestSide: 2400 }, 1080), true);
  assert.equal(isWorthDownscaling({ hasAlpha: false, longestSide: 1081 }, 1080), true);
  assert.equal(isWorthDownscaling({ hasAlpha: false, longestSide: 1080 }, 1080), false);
  assert.equal(isWorthDownscaling({ hasAlpha: false, longestSide: 120 }, 1080), false);
  // Dimensions unknown: try, and let the size comparison be the judge rather than guessing.
  assert.equal(isWorthDownscaling({ hasAlpha: false, longestSide: null }, 1080), true);
});

test("the cache name changes with everything that changes the bytes", () => {
  const base = thumbnailFileName("/u/a.png", source, 1080, JPEG_THUMBNAIL);
  assert.match(base, /^[0-9a-f]{64}\.jpg$/);
  assert.equal(thumbnailFileName("/u/a.png", source, 1080, JPEG_THUMBNAIL), base);

  for (const [what, other] of [
    ["path", thumbnailFileName("/u/b.png", source, 1080, JPEG_THUMBNAIL)],
    ["size", thumbnailFileName("/u/a.png", { ...source, size: source.size + 1 }, 1080, JPEG_THUMBNAIL)],
    ["mtime", thumbnailFileName("/u/a.png", { ...source, mtimeMs: source.mtimeMs + 1000 }, 1080, JPEG_THUMBNAIL)],
    ["width", thumbnailFileName("/u/a.png", source, 720, JPEG_THUMBNAIL)],
    ["format", thumbnailFileName("/u/a.png", source, 1080, PNG_THUMBNAIL)],
  ] as const) {
    assert.notEqual(other, base, what);
  }
});

test("the cache name never spells out the source path", () => {
  const name = thumbnailFileName("/Users/someone/Secret Project/plan.png", source, 1080, JPEG_THUMBNAIL);
  assert.equal(name.includes("someone"), false);
  assert.equal(name.includes("Secret"), false);
  assert.equal(name.includes("plan"), false);
});

test("sub-millisecond mtime jitter does not invalidate the cache", () => {
  // stat() on some filesystems returns a float; truncating keeps one image to one cache entry.
  assert.equal(
    thumbnailFileName("/u/a.png", { ...source, mtimeMs: 1_758_000_000_123.9 }, 1080, JPEG_THUMBNAIL),
    thumbnailFileName("/u/a.png", { ...source, mtimeMs: 1_758_000_000_123.1 }, 1080, JPEG_THUMBNAIL),
  );
});
