import { describe, expect, it } from "vitest";
import type { SessionListItem } from "../hermes/types";
import { groupSessions, recencyBounds, recencyGroup, relativeTime, sessionSubline } from "./grouping";

// Local-time tests: construct instants with the local Date constructor so they hold in any TZ.
const at = (y: number, m: number, d: number, h = 12, min = 0) => new Date(y, m - 1, d, h, min).getTime();
const row = (id: string, ms: number | null, extra: Partial<SessionListItem> = {}): SessionListItem => ({ id, last_active: ms === null ? null : ms / 1000, ...extra });

describe("recency windows (DESIGN §5.2)", () => {
  const now = at(2026, 9, 21, 15, 30);
  const bounds = recencyBounds(now);

  it("today is [today 00:00, now]", () => {
    expect(recencyGroup(at(2026, 9, 21, 0, 0), bounds)).toBe("today");
    expect(recencyGroup(now, bounds)).toBe("today");
  });

  it("yesterday is [yesterday 00:00, today 00:00)", () => {
    expect(recencyGroup(at(2026, 9, 20, 23, 59), bounds)).toBe("yesterday");
    expect(recencyGroup(at(2026, 9, 20, 0, 0), bounds)).toBe("yesterday");
  });

  it("previous 7 days is [today 00:00 − 7×24h, yesterday 00:00)", () => {
    expect(recencyGroup(at(2026, 9, 19, 23, 0), bounds)).toBe("recent");
    expect(recencyGroup(bounds.weekFloor, bounds)).toBe("recent");
    expect(recencyGroup(bounds.weekFloor - 1, bounds)).toBe("older");
  });

  it("no timestamp is older", () => {
    expect(recencyGroup(null, bounds)).toBe("older");
  });

  it("yesterday's midnight is a calendar day, not today − 24h", () => {
    const b = recencyBounds(at(2026, 3, 9, 10));
    const expected = new Date(2026, 2, 8, 0, 0).getTime();
    expect(b.yesterdayStart).toBe(expected);
  });
});

describe("groupSessions", () => {
  const now = at(2026, 9, 21, 15, 30);

  it("orders needs-you → today → yesterday → recent → older, omits empty groups, newest first", () => {
    const groups = groupSessions(
      [
        row("old", at(2026, 8, 1)),
        row("t1", at(2026, 9, 21, 9)),
        row("t2", at(2026, 9, 21, 14)),
        row("y", at(2026, 9, 20, 9)),
        row("wait", at(2026, 8, 2)),
        row("none", null),
      ],
      new Set(["wait"]),
      now,
    );
    expect(groups.map((g) => g.id)).toEqual(["needs-you", "today", "yesterday", "older"]);
    expect(groups[1]!.sessions.map((s) => s.id)).toEqual(["t2", "t1"]);
    expect(groups[3]!.sessions.map((s) => s.id)).toEqual(["old", "none"]);
  });

  it("drops archived rows", () => {
    expect(groupSessions([row("a", now, { archived: true })], new Set(), now)).toEqual([]);
  });
});

describe("row text", () => {
  const now = at(2026, 9, 21, 15, 30);

  it("relative time", () => {
    expect(relativeTime(now - 10_000, now, "zh")).toBe("刚刚");
    expect(relativeTime(now - 5 * 60_000, now, "zh")).toBe("5 分钟前");
    expect(relativeTime(now - 5 * 60_000, now, "en")).toBe("5m");
    expect(relativeTime(at(2026, 9, 21, 9, 5), now, "zh")).toBe("09:05");
    expect(relativeTime(at(2026, 9, 20, 9, 5), now, "zh")).toBe("昨天");
    expect(relativeTime(at(2026, 9, 2), now, "zh")).toBe("9月2日");
    expect(relativeTime(at(2025, 9, 2), now, "zh")).toBe("2025/9/2");
    expect(relativeTime(null, now, "zh")).toBe("");
  });

  it("subline is repo · model", () => {
    expect(sessionSubline({ id: "x", git_repo_root: "/Users/me/code/hermes-remote/", model: "claude-opus-5" })).toBe("hermes-remote · claude-opus-5");
    expect(sessionSubline({ id: "x", cwd: "/Users/me", model: null })).toBe("me");
    expect(sessionSubline({ id: "x" })).toBe("");
  });
});
