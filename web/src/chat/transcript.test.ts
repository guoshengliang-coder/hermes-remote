import { describe, expect, it } from "vitest";
import type { ChatItem } from "./model";
import { formatTimeSeparator, greetingForHour, showsTimeSeparator, transcriptAttachmentName, transcriptFileBaseName, transcriptMarkdown, transcriptMarkdownForAttachment, transcriptText } from "./transcript";

const at = (y: number, m: number, d: number, h = 12, min = 0) => new Date(y, m - 1, d, h, min).getTime();
const item = (role: ChatItem["role"], text: string, extra: Partial<ChatItem> = {}): ChatItem => ({
  key: `${role}-${text}`,
  role,
  text,
  attachments: [],
  images: [],
  reasoning: "",
  tools: [],
  streaming: false,
  timestampMs: null,
  ...extra,
});

describe("time separators (ChatUiState.showsTimeSeparator)", () => {
  it("first stamped turn, then only after a 20-minute gap", () => {
    expect(showsTimeSeparator(null, null)).toBe(false);
    expect(showsTimeSeparator(null, 1)).toBe(true);
    expect(showsTimeSeparator(0, 19 * 60_000)).toBe(false);
    expect(showsTimeSeparator(0, 20 * 60_000)).toBe(true);
  });

  it("formats today / yesterday / this year / older", () => {
    const now = at(2026, 9, 22, 15);
    expect(formatTimeSeparator(at(2026, 9, 22, 9, 5), "zh", now)).toBe("09:05");
    expect(formatTimeSeparator(at(2026, 9, 21, 23, 0), "zh", now)).toBe("昨天 23:00");
    expect(formatTimeSeparator(at(2026, 3, 8, 7, 30), "zh", now)).toBe("3月8日 07:30");
    expect(formatTimeSeparator(at(2025, 12, 31, 7, 30), "zh", now)).toBe("2025年12月31日 07:30");
    expect(formatTimeSeparator(at(2026, 3, 8, 7, 30), "en", now)).toBe("Mar 8, 07:30");
  });

  it("greets by hour like Android", () => {
    expect([3, 6, 10, 15, 21].map((h) => greetingForHour(h, "zh"))).toEqual(["夜深了", "早上好", "上午好", "下午好", "晚上好"]);
  });
});

describe("transcript export (MessageActions.transcriptText / TranscriptExport.kt)", () => {
  const items = [
    item("user", "question"),
    item("assistant", "**answer**", { attachments: [{ kind: "download", path: "/tmp/r.pdf", name: "r.pdf" } as ChatItem["attachments"][number]] }),
    item("user", "note", { note: { glyph: "⇄", zh: "已切换模型", en: "Model switched", expandable: false } }),
    item("assistant", "", {}),
    item("user", "unsent", { send: "failed" }),
  ];

  it("plain text: role-labelled, Markdown kept, notes / blanks / unsent skipped", () => {
    expect(transcriptText(items, "zh")).toBe("你:\nquestion\n\n助手:\n**answer**");
  });

  it("markdown: title, provenance line, a section per turn with attachment lines", () => {
    const md = transcriptMarkdown("My chat", items, "zh", at(2026, 9, 22, 8, 7));
    expect(md).toBe("# My chat\n\n> 导出时间：2026-09-22 08:07 · Hermes GO\n\n## 你\n\nquestion\n\n## 助手\n\n**answer**\n\n附件：r.pdf\n");
    expect(transcriptMarkdown(null, [], "en", 0)).toBe("");
  });

  it("file names are filesystem-safe and stamped", () => {
    expect(transcriptFileBaseName('a/b: "c"?', at(2026, 9, 22, 8, 7, ))).toBe("HermesGO-a b c-20260922-080700");
    expect(transcriptFileBaseName("  ", at(2026, 9, 22, 8, 7))).toBe("HermesGO-20260922-080700");
  });
});

describe("conversation as an attachment (SESSION_EXCHANGE §4.2)", () => {
  const turns = Array.from({ length: 10 }, (_, i) => [item("user", `question ${i} ${"x".repeat(200)}`), item("assistant", `answer ${i} ${"y".repeat(200)}`)]).flat();
  const now = at(2026, 9, 22, 15);

  it("returns the whole document when it fits", () => {
    const doc = transcriptMarkdownForAttachment("T", turns, "zh", now, 1_000_000);
    expect(doc).toBe(transcriptMarkdown("T", turns, "zh", now));
    expect(doc).not.toContain("已省略");
  });

  it("drops the earliest turns, says how many, and fits the cap", () => {
    const doc = transcriptMarkdownForAttachment("T", turns, "zh", now, 2_000);
    expect(new TextEncoder().encode(doc).length).toBeLessThanOrEqual(2_000);
    const dropped = Number(/已省略最早 (\d+) 轮，原对话共 10 轮/.exec(doc)?.[1]);
    expect(dropped).toBeGreaterThan(0);
    expect(doc).toContain(`question ${dropped} `);
    expect(doc).not.toContain(`question ${dropped - 1} `);
    expect(doc).toContain("question 9 ");
  });

  it("is empty when not even the last turn fits", () => {
    expect(transcriptMarkdownForAttachment("T", turns, "en", now, 200)).toBe("");
  });

  it("names the file by the conversation title", () => {
    expect(transcriptAttachmentName("部署 / 回滚", now)).toBe("部署 回滚.md");
    expect(transcriptAttachmentName(null, now)).toBe("HermesGO-20260922-150000.md");
  });
});
