import { beforeEach, describe, expect, it } from "vitest";
import { botOriginLabel, botSections, botSendNoticeTitle, botSourceLabel, botStatusLine, botNoticeSeen, clearBotNotices, markBotNoticeSeen } from "./bots";
import { contractNotice } from "./health";
import { initialInbox, type InboxState } from "./inbox";
import { addRecentSearch, clearLocalPrefs, defaultProjectPath, recentSearches, rememberDefaultProject, removeRecentSearch } from "./localPrefs";
import { matchRoute, routePath } from "./router";
import { rowView } from "./rowStatus";
import { centerSnippet, highlightRanges, titleMatches } from "./searchText";

const inbox = (latest: Record<string, string>, unseen: string[] = []): InboxState => ({
  ...initialInbox,
  latest: Object.fromEntries(Object.entries(latest).map(([id, event], i) => [id, { storedSessionId: id, event, title: null, sequence: i + 1, occurredAtMs: 0 }])),
  unseen: Object.fromEntries(unseen.map((id) => [id, { storedSessionId: id, event: "run.completed", title: null, sequence: 99, occurredAtMs: 0 }])),
});

describe("row status from the lifecycle inbox (SessionsScreen sessionStatusLine / sessionRowTrailing)", () => {
  it("running shows a spinner, waiting an amber dot, completed only while unread", () => {
    expect(rowView("a", inbox({ a: "run.started" }), new Set())).toMatchObject({ status: { zh: "运行中…", paint: "running" }, trailing: "spinner" });
    expect(rowView("a", inbox({ a: "run.waiting" }), new Set(["a"]))).toMatchObject({ status: { zh: "等待你处理", paint: "waiting" }, trailing: "waiting" });
    expect(rowView("a", inbox({ a: "run.completed" }, ["a"]), new Set())).toMatchObject({ status: { zh: "已完成" }, trailing: "unread", unread: true });
    expect(rowView("a", inbox({ a: "run.completed" }), new Set())).toEqual({ status: null, trailing: "none", unread: false });
  });

  it("interrupted keeps its line but no terminal dot; unknown sessions say nothing", () => {
    expect(rowView("a", inbox({ a: "run.interrupted" }), new Set())).toMatchObject({ status: { zh: "已中断" }, trailing: "none" });
    expect(rowView("z", inbox({}), new Set())).toEqual({ status: null, trailing: "none", unread: false });
  });
});

describe("bots (BotSessions.kt, BotPeerLabel.kt)", () => {
  it("names channels and labels the origin", () => {
    expect(botSourceLabel("dingtalk")).toBe("钉钉");
    expect(botSourceLabel("home_assistant")).toBe("Home Assistant");
    expect(botOriginLabel({ source: "feishu", display_name: " 张三 ", chat_type: "dm" }, "zh")).toBe("来自飞书 · 张三");
    expect(botOriginLabel({ source: "dingtalk", chat_type: "group" }, "en")).toBe("From 钉钉 · group");
    expect(botSendNoticeTitle("wecom", "zh")).toBe("这条不会发到企业微信");
  });

  it("groups by channel, channels by latest activity, drops archived and empty", () => {
    const sections = botSections([
      { id: "a", source: "dingtalk", last_active: 10, message_count: 2 },
      { id: "b", source: "feishu", last_active: 30, message_count: 1 },
      { id: "c", source: "dingtalk", last_active: 20, message_count: 3 },
      { id: "d", source: "dingtalk", last_active: 40, message_count: 0 },
      { id: "e", source: "feishu", last_active: 50, archived: true, message_count: 1 },
      { id: "f", source: "tui", last_active: 60, message_count: 1 },
    ]);
    expect(sections.map((s) => [s.source, s.sessions.map((r) => r.id)])).toEqual([["feishu", ["b"]], ["dingtalk", ["c", "a"]]]);
  });

  it("status line: relative time · N 条", () => {
    const now = Date.now();
    expect(botStatusLine({ id: "a", last_active: (now - 30_000) / 1000, message_count: 5 }, now, "zh")).toBe("刚刚 · 5 条");
    expect(botStatusLine({ id: "a", message_count: 1 }, now, "en")).toBe("1 message");
    expect(botStatusLine({ id: "a" }, now, "en")).toBeNull();
  });
});

describe("browser prefs", () => {
  beforeEach(() => localStorage.clear());

  it("recent searches: newest first, deduped, capped at 8, per Mac", () => {
    for (let i = 0; i < 10; i++) addRecentSearch("mac", `q${i}`);
    addRecentSearch("mac", "q5");
    expect(recentSearches("mac")).toEqual(["q5", "q9", "q8", "q7", "q6", "q4", "q3", "q2"]);
    expect(removeRecentSearch("mac", "q9")).not.toContain("q9");
    expect(recentSearches("other")).toEqual([]);
  });

  it("default project from a top-level create; bot notices per channel; all cleared on sign-out", () => {
    rememberDefaultProject("mac", "/Users/me/");
    expect(defaultProjectPath("mac")).toBe("/Users/me");
    markBotNoticeSeen("dingtalk");
    expect(botNoticeSeen("dingtalk")).toBe(true);
    expect(botNoticeSeen("feishu")).toBe(false);
    addRecentSearch("mac", "x");
    clearLocalPrefs();
    clearBotNotices();
    expect(defaultProjectPath("mac")).toBeNull();
    expect(recentSearches("mac")).toEqual([]);
    expect(botNoticeSeen("dingtalk")).toBe(false);
  });
});

describe("search text (SearchText.kt)", () => {
  it("finds every occurrence, case-insensitive", () => {
    expect(highlightRanges("Nginx and nginx", "NGINX")).toEqual([[0, 5], [10, 15]]);
    expect(highlightRanges("abc", " ")).toEqual([]);
  });

  it("centres the snippet on the first match with ellipses", () => {
    const text = `${"a".repeat(60)} needle ${"b".repeat(60)}`;
    const snippet = centerSnippet(text, "needle", 10);
    expect(snippet.startsWith("…")).toBe(true);
    expect(snippet.endsWith("…")).toBe(true);
    expect(snippet).toContain("needle");
    expect(centerSnippet("short\n\ntext", "zzz")).toBe("short text");
  });

  it("title matches the title or the project label", () => {
    expect(titleMatches("Refactor router", "hermes-remote", "HERMES")).toBe(true);
    expect(titleMatches("Refactor", null, "x")).toBe(false);
  });
});

describe("Hermes contract notice (HermesContract.kt)", () => {
  it("maps breaking/degraded to HR-COMPAT codes; unknown and other schemas show nothing", () => {
    expect(contractNotice({ schema: 1, status: "breaking", hermesVersion: "0.21.0", missing: [{ method: "GET", path: "/api/sessions", tier: "required" }] })).toMatchObject({
      severity: "breaking",
      error: { code: "HR-COMPAT-001", details: expect.stringContaining("GET /api/sessions (required)") },
    });
    expect(contractNotice({ schema: 1, status: "degraded", code: "HR-COMPAT-003" })?.error.code).toBe("HR-COMPAT-003");
    expect(contractNotice({ schema: 1, status: "degraded", code: "HR-NOPE-1" })?.error.code).toBe("HR-COMPAT-002");
    expect(contractNotice({ schema: 1, status: "unknown" })).toBeNull();
    expect(contractNotice({ schema: 2, status: "breaking" })).toBeNull();
    expect(contractNotice(null)).toBeNull();
  });
});

describe("router", () => {
  it("has an archived page", () => {
    expect(matchRoute("/app/archived")).toEqual({ name: "archived" });
    expect(routePath({ name: "archived" })).toBe("/app/archived");
  });
});
