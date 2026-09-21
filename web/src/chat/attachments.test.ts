import { describe, expect, it } from "vitest";
import { attachmentKind, checkAttachments, jpegName, MAX_ATTACHMENT_BYTES, needsReencode, scaledSize } from "./attachments";

function file(name: string, size: number, type: string): File {
  const f = new File(["x"], name, { type });
  Object.defineProperty(f, "size", { value: size });
  return f;
}

describe("attachment validation", () => {
  it("accepts at most 9 in total", () => {
    const picked = Array.from({ length: 5 }, (_, i) => file(`f${i}.txt`, 10, "text/plain"));
    const result = checkAttachments(6, picked);
    expect(result.accepted.map((f) => f.name)).toEqual(["f0.txt", "f1.txt", "f2.txt"]);
    expect(result.rejected.map((r) => r.problem)).toEqual(["too-many", "too-many"]);
  });

  it("refuses non-images over 6 MB and empty files, but keeps large re-encodable images", () => {
    const result = checkAttachments(0, [
      file("big.pdf", MAX_ATTACHMENT_BYTES + 1, "application/pdf"),
      file("empty.txt", 0, "text/plain"),
      file("huge.jpg", MAX_ATTACHMENT_BYTES * 2, "image/jpeg"),
      file("anim.gif", MAX_ATTACHMENT_BYTES + 1, "image/gif"),
      file("ok.pdf", MAX_ATTACHMENT_BYTES, "application/pdf"),
    ]);
    expect(result.accepted.map((f) => f.name)).toEqual(["huge.jpg", "ok.pdf"]);
    expect(result.rejected).toEqual([
      { name: "big.pdf", problem: "too-large" },
      { name: "empty.txt", problem: "empty" },
      { name: "anim.gif", problem: "too-large" },
    ]);
  });

  it("routes images to image.attach and the rest to file.attach", () => {
    expect(attachmentKind("image/png")).toBe("image");
    expect(attachmentKind("IMAGE/JPEG")).toBe("image");
    expect(attachmentKind("application/pdf")).toBe("file");
    expect(attachmentKind("")).toBe("file");
  });
});

describe("image downscale", () => {
  it("fits the longest side into 2560 px keeping the aspect ratio", () => {
    expect(scaledSize(4032, 3024)).toEqual({ width: 2560, height: 1920 });
    expect(scaledSize(1000, 6000)).toEqual({ width: 427, height: 2560 });
    expect(scaledSize(2560, 100)).toEqual({ width: 2560, height: 100 });
  });

  it("re-encodes only oversized, overweight or HEIC images, never GIF", () => {
    expect(needsReencode({ size: 100, type: "image/png" }, 3000, 10)).toBe(true);
    expect(needsReencode({ size: MAX_ATTACHMENT_BYTES + 1, type: "image/jpeg" }, 100, 100)).toBe(true);
    expect(needsReencode({ size: 100, type: "image/heic" }, 100, 100)).toBe(true);
    expect(needsReencode({ size: 100, type: "image/png" }, 2560, 2560)).toBe(false);
    expect(needsReencode({ size: MAX_ATTACHMENT_BYTES * 3, type: "image/gif" }, 4000, 4000)).toBe(false);
  });

  it("renames a re-encoded image to .jpg", () => {
    expect(jpegName("IMG_0001.HEIC")).toBe("IMG_0001.jpg");
    expect(jpegName("shot.png")).toBe("shot.jpg");
    expect(jpegName("noext")).toBe("noext.jpg");
  });
});
