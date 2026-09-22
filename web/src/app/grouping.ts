import type { SessionListItem } from "../hermes/types";

// Session list grouping (docs/DESIGN.md §5.2 `groupByRecency`): needs-you first, then rolling
// windows in local time — today = [today 00:00, now]; yesterday = [yesterday 00:00, today 00:00),
// where yesterday's midnight is a CALENDAR day (DST-safe); last 7 days = [today 00:00 − 7×24h,
// yesterday 00:00); everything else, including rows without a timestamp, is older. Pinned rows
// (this browser's pins, app/pins.ts) sit between needs-you and today; a pinned session that needs
// you shows under needs-you, once.

export type GroupId = "needs-you" | "pinned" | "today" | "yesterday" | "recent" | "older";

export const GROUP_ORDER: readonly GroupId[] = ["needs-you", "pinned", "today", "yesterday", "recent", "older"];

export interface SessionGroup {
  id: GroupId;
  sessions: SessionListItem[];
}

export interface RecencyBounds {
  todayStart: number;
  yesterdayStart: number;
  weekFloor: number;
}

export function recencyBounds(nowMs: number): RecencyBounds {
  const today = new Date(nowMs);
  today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  const todayStart = today.getTime();
  // weekFloor stays fixed milliseconds: the released 7-day window's semantics (§5.2, HG-52).
  return { todayStart, yesterdayStart: yesterday.getTime(), weekFloor: todayStart - 7 * 24 * 3600 * 1000 };
}

export function lastActiveMs(session: Pick<SessionListItem, "last_active">): number | null {
  const v = session.last_active;
  return typeof v === "number" && Number.isFinite(v) && v > 0 ? v * 1000 : null;
}

export function recencyGroup(ms: number | null, bounds: RecencyBounds): Exclude<GroupId, "needs-you" | "pinned"> {
  if (ms === null) return "older";
  if (ms >= bounds.todayStart) return "today";
  if (ms >= bounds.yesterdayStart) return "yesterday";
  if (ms >= bounds.weekFloor) return "recent";
  return "older";
}

/** Groups in display order; empty groups are omitted; each group newest first. */
export function groupSessions(
  sessions: readonly SessionListItem[],
  needsYou: ReadonlySet<string>,
  nowMs: number,
  isPinned: (session: SessionListItem) => boolean = () => false,
): SessionGroup[] {
  const bounds = recencyBounds(nowMs);
  const buckets = new Map<GroupId, SessionListItem[]>(GROUP_ORDER.map((id) => [id, []]));
  for (const session of sessions) {
    if (session.archived) continue;
    const id = needsYou.has(session.id)
      ? "needs-you"
      : isPinned(session)
        ? "pinned"
        : recencyGroup(lastActiveMs(session), bounds);
    buckets.get(id)!.push(session);
  }
  const byRecency = (a: SessionListItem, b: SessionListItem) => (lastActiveMs(b) ?? 0) - (lastActiveMs(a) ?? 0);
  return GROUP_ORDER.flatMap((id) => {
    const list = buckets.get(id)!;
    return list.length ? [{ id, sessions: [...list].sort(byRecency) }] : [];
  });
}

/** Short relative time for a row: 刚刚 / N 分钟 / HH:mm today / 昨天 / M月D日. */
export function relativeTime(ms: number | null, nowMs: number, language: "zh" | "en"): string {
  if (ms === null) return "";
  const diff = Math.max(0, nowMs - ms);
  const minute = 60_000;
  if (diff < minute) return language === "en" ? "now" : "刚刚";
  if (diff < 60 * minute) {
    const n = Math.floor(diff / minute);
    return language === "en" ? `${n}m` : `${n} 分钟前`;
  }
  const bounds = recencyBounds(nowMs);
  const d = new Date(ms);
  const hhmm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  if (ms >= bounds.todayStart) return hhmm;
  if (ms >= bounds.yesterdayStart) return language === "en" ? "Yesterday" : "昨天";
  const sameYear = d.getFullYear() === new Date(nowMs).getFullYear();
  if (language === "en") {
    const month = d.toLocaleString("en", { month: "short" });
    return sameYear ? `${month} ${d.getDate()}` : `${month} ${d.getDate()}, ${d.getFullYear()}`;
  }
  return sameYear ? `${d.getMonth() + 1}月${d.getDate()}日` : `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}

/** Subline: "repo · model" (DESIGN §5.2 mono data slot); empty parts are dropped. */
export function sessionSubline(session: SessionListItem): string {
  const root = session.git_repo_root || session.cwd || "";
  const repo = root.replace(/\/+$/, "").split("/").pop() ?? "";
  return [repo, session.model ?? ""].filter((part) => part.trim() !== "").join(" · ");
}
