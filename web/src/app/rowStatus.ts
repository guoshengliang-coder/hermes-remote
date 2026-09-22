import type { InboxState } from "./inbox";

// A session row's status line and trailing indicator (Android SessionsScreen sessionStatusLine /
// sessionRowTrailing / sessionStatusPaint). Android learns each run's live phase from every
// session's WebSocket; the Web list has only the Relay lifecycle inbox, so the phases collapse to
// what the inbox says: running, waiting, completed (while unread), interrupted.

export type RowPaint = "running" | "waiting" | "completed" | "failed" | "interrupted";
export type RowTrailing = "spinner" | "waiting" | "unread" | "none";

export interface RowStatus {
  zh: string;
  en: string;
  paint: RowPaint;
}

export interface RowView {
  status: RowStatus | null;
  trailing: RowTrailing;
  unread: boolean;
}

export function rowView(sessionId: string, inbox: InboxState, needsYou: ReadonlySet<string>): RowView {
  const latest = inbox.latest[sessionId];
  const unread = sessionId in inbox.unseen;
  if (needsYou.has(sessionId)) return { status: { zh: "等待你处理", en: "Needs your attention", paint: "waiting" }, trailing: "waiting", unread };
  switch (latest?.event) {
    case "run.started":
    case "run.resumed":
      return { status: { zh: "运行中…", en: "Running…", paint: "running" }, trailing: "spinner", unread };
    case "run.completed":
      // 已完成 only while unread, like Android's COMPLETED_UNREAD; the unread dot wins the slot.
      return unread ? { status: { zh: "已完成", en: "Completed", paint: "completed" }, trailing: "unread", unread } : { status: null, trailing: "none", unread };
    case "run.interrupted":
      // No dot: a terminal dot next to the unread dot read as unread (DESIGN §5.2, 2026-09-02).
      return { status: { zh: "已中断", en: "Interrupted", paint: "interrupted" }, trailing: unread ? "unread" : "none", unread };
    default:
      return { status: null, trailing: unread ? "unread" : "none", unread };
  }
}
