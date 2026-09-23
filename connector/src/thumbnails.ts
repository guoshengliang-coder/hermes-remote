import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { extname } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);

// Downscaled previews for chat images (HG-115).
//
// The bubble shows a thumbnail; both clients fetched the full-size original for it. Measured on
// 2026-09-23 over the 103 images this repository's own Hermes still has on disk: 87.9 MB total,
// median 416 KB, and one conversation of five screenshots weighed 6.93 MB against 2,310 bytes of
// text. At 1080px the same sample comes back 11.9x smaller.
//
// Why `sips` and not an image library. The Connector depends on `ws` and this repo's protocol
// package, and nothing else — and it is not packaged with `npm install`: `stageConnectorV2` copies
// exactly those two by hand, so a new dependency would be silently ABSENT from the shipped
// component. `defaultInspectPortability` additionally rejects any dylib outside /System/Library
// and /usr/lib, which rules out sharp's libvips. `/usr/bin/sips` ships with macOS, is on the bare
// launchd PATH the managed service runs with, and costs the packaging path nothing.

export const SIPS = "/usr/bin/sips";

/**
 * The tiers a caller may ask for. An allowlist rather than a free integer: the cache is keyed by
 * width, so an unbounded range lets one caller fill the disk with near-identical copies.
 */
export const THUMBNAIL_WIDTHS = [1080] as const;

/** Sources worth downscaling. SVG is deliberately absent — it is markup, and sips would rasterize it. */
const RASTER_EXTENSIONS = new Set([".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".tif", ".heic", ".heif"]);

/** Formats that can carry transparency, and therefore need asking before a JPEG re-encode. */
const MAY_HAVE_ALPHA = new Set([".png", ".gif", ".webp", ".tiff", ".tif"]);

export function isThumbnailable(path: string): boolean {
  return RASTER_EXTENSIONS.has(extname(path).toLowerCase());
}

/**
 * The tier to serve for a requested width, or null when the request is not a width at all.
 *
 * Snapping up rather than rejecting: a client asking for 800 gets 1080 and a smaller payload,
 * where a 400 would leave it fetching the original. Anything above the largest tier is that tier —
 * asking for more than we make is asking for the biggest preview, not for the original.
 */
export function snapThumbnailWidth(raw: string | undefined): number | null {
  if (raw === undefined || raw.length === 0) return null;
  if (!/^\d{1,5}$/.test(raw)) return null;
  const requested = Number.parseInt(raw, 10);
  if (requested <= 0) return null;
  return THUMBNAIL_WIDTHS.find((width) => width >= requested) ?? THUMBNAIL_WIDTHS[THUMBNAIL_WIDTHS.length - 1]!;
}

export interface ThumbnailFormat {
  /** What to pass to `sips -s format`. */
  sips: "jpeg" | "png";
  extension: ".jpg" | ".png";
  contentType: "image/jpeg" | "image/png";
}

export const JPEG_THUMBNAIL: ThumbnailFormat = { sips: "jpeg", extension: ".jpg", contentType: "image/jpeg" };
export const PNG_THUMBNAIL: ThumbnailFormat = { sips: "png", extension: ".png", contentType: "image/png" };

/**
 * JPEG unless the source actually carries transparency.
 *
 * Measured on the same sample: PNG sources re-encoded to JPEG come back at 4.8% of their original
 * bytes, and kept as PNG at 24.3% — five times worse. But 1 of 60 PNGs here does have an alpha
 * channel, and flattening that one onto black is a visibly wrong picture, not a smaller one. So
 * the question is asked, and only for the formats that can answer yes.
 */
export function thumbnailFormatFor(path: string, hasAlpha: boolean): ThumbnailFormat {
  return MAY_HAVE_ALPHA.has(extname(path).toLowerCase()) && hasAlpha ? PNG_THUMBNAIL : JPEG_THUMBNAIL;
}

/** Parse `sips -g hasAlpha` output. Anything unrecognized is treated as "might have alpha". */
export function parseHasAlpha(output: string): boolean {
  const line = output.split("\n").find((candidate) => candidate.includes("hasAlpha"));
  if (line === undefined) return true;
  return line.split(":").pop()!.trim().toLowerCase() !== "no";
}

/**
 * Cache file name for one source at one width.
 *
 * The source path is hashed rather than embedded: the cache directory would otherwise spell out
 * the layout of the owner's disk. Size and mtime are in the digest so an edited image regenerates
 * instead of serving a stale preview, and the format is in it so the alpha answer cannot go stale
 * either.
 */
export function thumbnailFileName(
  canonicalPath: string,
  source: { size: number; mtimeMs: number },
  width: number,
  format: ThumbnailFormat,
): string {
  const digest = createHash("sha256")
    .update(`${canonicalPath}\n${source.size}\n${Math.trunc(source.mtimeMs)}\n${width}\n${format.sips}`)
    .digest("hex");
  return `${digest}${format.extension}`;
}

export interface SourceImage {
  hasAlpha: boolean;
  /** Pixels on the longer side, which is what `-Z` fits. Null when sips would not say. */
  longestSide: number | null;
}

export interface ThumbnailTools {
  describe(path: string): Promise<SourceImage>;
  convert(source: string, destination: string, width: number, format: ThumbnailFormat): Promise<void>;
}

/** Parse the `-g pixelWidth -g pixelHeight` half of the same call. */
export function parseLongestSide(output: string): number | null {
  const read = (key: string): number | null => {
    const line = output.split("\n").find((candidate) => candidate.includes(key));
    if (line === undefined) return null;
    const value = Number.parseInt(line.split(":").pop()!.trim(), 10);
    return Number.isFinite(value) && value > 0 ? value : null;
  };
  const width = read("pixelWidth");
  const height = read("pixelHeight");
  if (width === null || height === null) return null;
  return Math.max(width, height);
}

/**
 * Whether making a preview at [width] would be worth it.
 *
 * `sips -Z` does NOT only downscale — measured 2026-09-23, a 120x80 source comes back as
 * 1080x720. So an image already at or below the tier must be left alone here rather than sent
 * through: upscaling costs bytes AND produces a preview blurrier than the original it replaces.
 * When sips will not give dimensions the generated size still has to prove itself, so this
 * answers yes and the caller's size comparison decides.
 */
export function isWorthDownscaling(source: SourceImage, width: number): boolean {
  return source.longestSide === null || source.longestSide > width;
}

/** JPEG quality. 70 is where the sample stopped getting smaller without starting to look it. */
const JPEG_QUALITY = "70";

/** A preview has no business taking longer than this; past it the original is the better answer. */
const SIPS_TIMEOUT_MS = 20_000;

export const sipsTools: ThumbnailTools = {
  async describe(path) {
    // One spawn for both questions; the answers are only needed together.
    const { stdout } = await run(
      SIPS,
      ["-g", "hasAlpha", "-g", "pixelWidth", "-g", "pixelHeight", path],
      { timeout: SIPS_TIMEOUT_MS },
    );
    return { hasAlpha: parseHasAlpha(stdout), longestSide: parseLongestSide(stdout) };
  },
  async convert(source, destination, width, format) {
    const quality = format.sips === "jpeg" ? ["-s", "formatOptions", JPEG_QUALITY] : [];
    // -Z fits the longest side into `width`, in BOTH directions — see isWorthDownscaling.
    await run(
      SIPS,
      ["-s", "format", format.sips, ...quality, "-Z", String(width), source, "--out", destination],
      { timeout: SIPS_TIMEOUT_MS },
    );
  },
};
