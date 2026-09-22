import { describe, expect, it } from "vitest";
import { buildSearchQuery, containsCjk } from "./search-query";

// Cases ported from android SearchQueryTest.kt.
describe("buildSearchQuery", () => {
  it("passes Latin tokens through", () => {
    expect(buildSearchQuery("  gradle   build ")).toBe("gradle build");
  });
  it("quotes CJK tokens", () => {
    expect(buildSearchQuery("部署脚本")).toBe('"部署脚本"');
    expect(buildSearchQuery("部署 gradle")).toBe('"部署" gradle');
    expect(buildSearchQuery("部署 脚本")).toBe('"部署" "脚本"');
  });
  it("quotes a mixed token containing CJK as a whole", () => {
    expect(buildSearchQuery("apk包")).toBe('"apk包"');
  });
  it("preserves user quotes and wildcards", () => {
    expect(buildSearchQuery('"exact phrase" nimb*')).toBe('"exact phrase" nimb*');
    expect(buildSearchQuery('"部署 脚本"')).toBe('"部署 脚本"');
  });
  it("drops stray quotes inside a CJK token", () => {
    expect(buildSearchQuery('部"署')).toBe('"部署"');
  });
  it("returns empty for blank input", () => {
    expect(buildSearchQuery("   ")).toBe("");
  });
  it("treats an inner ideographic space as part of the token, like Java \\S", () => {
    expect(buildSearchQuery("部署　脚本")).toBe('"部署　脚本"');
  });
});

describe("containsCjk", () => {
  it("covers kana and Hangul", () => {
    expect(containsCjk("テスト")).toBe(true);
    expect(containsCjk("한국어")).toBe(true);
    expect(containsCjk("x汉y")).toBe(true);
    expect(containsCjk("plain ascii 123")).toBe(false);
  });
});
