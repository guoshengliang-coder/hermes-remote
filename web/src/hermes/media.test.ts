import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { classifyAttachment, extractMediaTags, inlineImageMime, MEDIA_DELIVERY_EXTENSIONS, parseAttachments } from "./media";

const paths = (s: ReturnType<typeof parseAttachments>, kind: "image" | "download") =>
  s.attachments.filter((a) => a.kind === kind).map((a) => a.path);

// Cases ported from android MappersTest.kt.
describe("MEDIA: tags", () => {
  it("turns a bare MEDIA path into a download and removes it from the text", () => {
    const parsed = parseAttachments(
      "现在直接发 md 原文件：\n\nMEDIA:/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md\n\n这次应该能打开。",
    );
    expect(parsed.text).toBe("现在直接发 md 原文件：\n\n这次应该能打开。");
    expect(parsed.attachments).toEqual([{
      kind: "download",
      path: "/Users/bs/hermes-文生图与安卓图片显示-会话整理-20260830.md",
      name: "hermes-文生图与安卓图片显示-会话整理-20260830.md",
      mimeType: "text/markdown",
      source: "media",
    }]);
  });

  it("keeps a MEDIA keyword explanation while extracting the real directive", () => {
    const parsed = parseAttachments(
      "桌面会话会提取，`MEDIA:` 标签和扩展名都在支持范围内。\n\nMEDIA:/Users/bs/x.md\n\n文件会作为附件推送到客户端。",
    );
    expect(parsed.text).toBe("桌面会话会提取，`MEDIA:` 标签和扩展名都在支持范围内。\n\n文件会作为附件推送到客户端。");
    expect(paths(parsed, "download")).toEqual(["/Users/bs/x.md"]);
  });

  it("routes backtick/quote wrapped paths with spaces by kind and strips size suffix and [[as_document]]", () => {
    const parsed = parseAttachments(
      '结果如下：\n**MEDIA:`/Users/bs/output/戴眼镜的猫 01.png`**\n[[as_document]] MEDIA:"/Users/bs/output/季度 报告.pdf"（7.3 KB）',
    );
    expect(parsed.text).toBe("结果如下：");
    expect(paths(parsed, "image")).toEqual(["/Users/bs/output/戴眼镜的猫 01.png"]);
    expect(paths(parsed, "download")).toEqual(["/Users/bs/output/季度 报告.pdf"]);
  });

  it("extracts adjacent tags independently", () => {
    const parsed = parseAttachments("MEDIA:/Users/bs/a.pngMEDIA:/Users/bs/b.csv");
    expect(parsed.text).toBe("");
    expect(paths(parsed, "image")).toEqual(["/Users/bs/a.png"]);
    expect(paths(parsed, "download")).toEqual(["/Users/bs/b.csv"]);
  });

  it("leaves examples in fenced code and blockquotes visible", () => {
    const raw = "示例：\n```text\nMEDIA:/Users/bs/example.pdf\n```\n> MEDIA:/Users/bs/quoted.png";
    const parsed = parseAttachments(raw);
    expect(parsed.text).toBe(raw);
    expect(parsed.attachments).toEqual([]);
  });

  it("does not remove an incomplete or unknown-extension tag", () => {
    const raw = "`MEDIA:` 标签示例；MEDIA:/Users/bs/source.py";
    expect(parseAttachments(raw)).toEqual({ text: raw, attachments: [] });
  });

  it("accepts file:// URIs, single quotes, bare paths with spaces and a trailing period", () => {
    expect(paths(parseAttachments("MEDIA:file:///Users/bs/a%20b.webp"), "image")).toEqual(["/Users/bs/a b.webp"]);
    expect(paths(parseAttachments("MEDIA:'/tmp/x y.gif'"), "image")).toEqual(["/tmp/x y.gif"]);
    const bare = parseAttachments("See MEDIA:/Users/bs/my report final.pdf.");
    expect(paths(bare, "download")).toEqual(["/Users/bs/my report final.pdf"]);
    expect(bare.text).toBe("See");
    expect(paths(parseAttachments("图：MEDIA:/tmp/a.JPG，好了"), "image")).toEqual(["/tmp/a.JPG"]);
  });

  it("strips [[audio_as_voice]] even without a MEDIA tag", () => {
    expect(extractMediaTags("hi [[audio_as_voice]]").text).toBe("hi ");
  });

  it("mirrors android Mappers.kt MEDIA_DELIVERY_EXTENSIONS item for item", () => {
    const kotlin = readFileSync(resolve(process.cwd(), "../android/app/src/main/java/com/hermes/client/domain/Mappers.kt"), "utf8");
    const block = /MEDIA_DELIVERY_EXTENSIONS = listOf\(([\s\S]*?)\n\)/.exec(kotlin)?.[1] ?? "";
    const android = [...block.replace(/\/\/.*$/gm, "").matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    expect(android.length).toBeGreaterThan(40);
    expect([...MEDIA_DELIVERY_EXTENSIONS]).toEqual(android);
  });
});

describe("@image: / @file: directives", () => {
  it("hides image directives (bare with spaces and quoted)", () => {
    const parsed = parseAttachments('请看这张图\n@image:/Users/me/photo one.png\n@image:"/tmp/second.jpg"');
    expect(parsed.text).toBe("请看这张图");
    expect(paths(parsed, "image")).toEqual(["/Users/me/photo one.png", "/tmp/second.jpg"]);
  });

  it("drops the attached-image placeholder", () => {
    const parsed = parseAttachments("[User attached image: screenshot.png]\n@image:`/tmp/screenshot.png`");
    expect(parsed.text).toBe("");
    expect(paths(parsed, "image")).toEqual(["/tmp/screenshot.png"]);
  });

  it("turns file directives into downloads and drops their placeholder", () => {
    const parsed = parseAttachments("报告已生成\n@file:`/Users/me/report final.pdf`\n[User attached file: report final.pdf]");
    expect(parsed.text).toBe("报告已生成");
    expect(parsed.attachments).toEqual([{
      kind: "download", path: "/Users/me/report final.pdf", name: "report final.pdf", mimeType: "application/pdf", source: "file-directive",
    }]);
  });

  it("an @file: with an image extension is still a download (explicit delivery)", () => {
    expect(parseAttachments("@file:/srv/x.png").attachments[0]).toMatchObject({ kind: "download", path: "/srv/x.png" });
  });
});

describe("classification", () => {
  it("inline-capable images are png/jpeg/gif/webp only", () => {
    expect(["a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp"].map(inlineImageMime)).toEqual(["image/png", "image/jpeg", "image/jpeg", "image/gif", "image/webp"]);
    expect(inlineImageMime("a.svg")).toBeNull();
    expect(inlineImageMime("a.bmp")).toBeNull();
    expect(classifyAttachment("/tmp/a.svg").kind).toBe("download");
    expect(classifyAttachment("/tmp/a.zip")).toMatchObject({ kind: "download", mimeType: "application/zip", name: "a.zip" });
    expect(classifyAttachment("/tmp/a.tiff").mimeType).toBeNull();
  });
});
