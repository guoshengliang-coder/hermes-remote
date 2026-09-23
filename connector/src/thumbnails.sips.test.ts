import assert from "node:assert/strict";
import test from "node:test";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { deflateSync } from "node:zlib";
import { JPEG_THUMBNAIL, PNG_THUMBNAIL, SIPS, isWorthDownscaling, sipsTools } from "./thumbnails.js";

// The half of the preview path that is a real subprocess: whether sips accepts the flags we pass,
// whether its `-g hasAlpha` output parses, and whether the result is actually smaller. The pure
// decisions are in thumbnails.test.ts.
//
// Skipped where sips does not exist — CI's node job is ubuntu, and this is a macOS-only tool by
// design (see thumbnails.ts for why the Connector shells out instead of taking a dependency).
const available = existsSync(SIPS);

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type: string, body: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(body.length);
  const typed = Buffer.concat([Buffer.from(type, "ascii"), body]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typed));
  return Buffer.concat([length, typed, crc]);
}

/** A gradient PNG large enough that downscaling has something to do. */
function png(width: number, height: number, withAlpha: boolean): Buffer {
  const channels = withAlpha ? 4 : 3;
  const raw = Buffer.alloc(height * (1 + width * channels));
  let at = 0;
  for (let y = 0; y < height; y += 1) {
    raw[at++] = 0; // filter: none
    for (let x = 0; x < width; x += 1) {
      raw[at++] = (x * 255) / width;
      raw[at++] = (y * 255) / height;
      raw[at++] = ((x + y) * 255) / (width + height);
      if (withAlpha) raw[at++] = x < width / 2 ? 0 : 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = withAlpha ? 6 : 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

test("sips answers hasAlpha for both kinds of PNG", { skip: !available }, async () => {
  const dir = await mkdtemp(join(tmpdir(), "hg115-"));
  try {
    const opaque = join(dir, "opaque.png");
    const transparent = join(dir, "transparent.png");
    await writeFile(opaque, png(300, 200, false));
    await writeFile(transparent, png(300, 200, true));

    assert.equal((await sipsTools.describe(opaque)).hasAlpha, false);
    assert.equal((await sipsTools.describe(transparent)).hasAlpha, true);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("a downscaled JPEG comes back much smaller and is a JPEG", { skip: !available }, async () => {
  const dir = await mkdtemp(join(tmpdir(), "hg115-"));
  try {
    const source = join(dir, "shot.png");
    const out = join(dir, "shot.jpg");
    await writeFile(source, png(2400, 1600, false));
    const before = (await stat(source)).size;

    await sipsTools.convert(source, out, 1080, JPEG_THUMBNAIL);

    const after = await readFile(out);
    assert.ok(after.length < before / 2, `expected a much smaller file, got ${after.length} from ${before}`);
    // SOI marker: the bytes really are a JPEG, not a renamed PNG.
    assert.deepEqual(after.subarray(0, 2), Buffer.from([0xff, 0xd8]));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("a transparent source keeps its alpha as PNG", { skip: !available }, async () => {
  const dir = await mkdtemp(join(tmpdir(), "hg115-"));
  try {
    const source = join(dir, "logo.png");
    const out = join(dir, "logo-small.png");
    await writeFile(source, png(2400, 1600, true));

    await sipsTools.convert(source, out, 1080, PNG_THUMBNAIL);

    assert.equal((await sipsTools.describe(out)).hasAlpha, true);
    assert.deepEqual((await readFile(out)).subarray(1, 4), Buffer.from("PNG", "ascii"));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("sips reports the dimensions the tier is compared against", { skip: !available }, async () => {
  const dir = await mkdtemp(join(tmpdir(), "hg115-"));
  try {
    const wide = join(dir, "wide.png");
    const tall = join(dir, "tall.png");
    await writeFile(wide, png(2400, 600, false));
    await writeFile(tall, png(600, 2400, false));

    // -Z fits the LONGER side, so that is what has to be measured, whichever way round it is.
    assert.equal((await sipsTools.describe(wide)).longestSide, 2400);
    assert.equal((await sipsTools.describe(tall)).longestSide, 2400);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

/**
 * The assumption this test was written to confirm turned out to be false, which is the reason
 * `isWorthDownscaling` exists: `-Z` is not a downscale, it is a fit, and it enlarges a small
 * source to the tier. Left here asserting what sips ACTUALLY does, so the guard above cannot be
 * removed by someone who assumes what I assumed.
 */
test("-Z enlarges a source below the tier, so the guard must keep it away", { skip: !available }, async () => {
  const dir = await mkdtemp(join(tmpdir(), "hg115-"));
  try {
    const source = join(dir, "small.png");
    const out = join(dir, "small.jpg");
    await writeFile(source, png(120, 80, false));

    const described = await sipsTools.describe(source);
    assert.equal(described.longestSide, 120);
    assert.equal(isWorthDownscaling(described, 1080), false);

    await sipsTools.convert(source, out, 1080, JPEG_THUMBNAIL);
    assert.equal((await sipsTools.describe(out)).longestSide, 1080);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
