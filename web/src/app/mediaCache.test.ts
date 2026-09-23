import { describe, expect, it } from "vitest";
import { clearMediaCache, entriesToEvict, mediaKey, readCachedMedia, writeCachedMedia } from "./mediaCache";

// HG-108. The eviction policy and the key are pure, so they are tested directly; the IndexedDB
// wrapper around them is deliberately thin, and neither happy-dom nor jsdom provides IndexedDB —
// the no-store path below is what this environment can assert about it. The cache itself is
// verified in a browser (see the item's verification notes).

describe("mediaKey", () => {
  it("scopes an entry to its device", () => {
    expect(mediaKey("dev-1", "/Users/x/a.png")).toBe("dev-1\n/Users/x/a.png");
    expect(mediaKey("dev-1", "/a")).not.toBe(mediaKey("dev-2", "/a"));
  });
});

describe("entriesToEvict", () => {
  const entry = (key: string, bytes: number, at: number) => ({ key, bytes, at });

  it("keeps everything while under both ceilings", () => {
    expect(entriesToEvict([entry("a", 10, 3), entry("b", 10, 2)], 5, 100)).toEqual([]);
  });

  it("drops the least recently used first when over the file count", () => {
    const entries = [entry("old", 1, 1), entry("mid", 1, 2), entry("new", 1, 3)];
    expect(entriesToEvict(entries, 2, 1000)).toEqual(["old"]);
  });

  it("drops the least recently used first when over the byte ceiling", () => {
    const entries = [entry("old", 60, 1), entry("new", 60, 2)];
    expect(entriesToEvict(entries, 100, 100)).toEqual(["old"]);
  });

  it("skips an oversized entry without dropping the newer ones that still fit", () => {
    // "huge" cannot fit at all; "small" must survive rather than being evicted behind it.
    const entries = [entry("small", 10, 1), entry("huge", 500, 2)];
    expect(entriesToEvict(entries, 100, 100)).toEqual(["huge"]);
  });

  it("orders ties deterministically so a trim is reproducible", () => {
    const entries = [entry("b", 1, 5), entry("a", 1, 5)];
    expect(entriesToEvict(entries, 1, 1000)).toEqual(["b"]);
  });
});

describe("without IndexedDB", () => {
  it("degrades to no cache instead of throwing", async () => {
    expect(typeof indexedDB).toBe("undefined");
    await expect(writeCachedMedia("dev-1", "/a", new Blob(["x"]))).resolves.toBeUndefined();
    await expect(readCachedMedia("dev-1", "/a")).resolves.toBeNull();
    await expect(clearMediaCache()).resolves.toBeUndefined();
  });
});
