import type { ChatItem } from "./model";

// Turn groups for the "back to this prompt" pill (DESIGN §5.4 上一组对话胶囊, Android TurnJump.kt):
// each real user message opens a group that runs to the next one; injected timeline notes do not
// count; content before the first prompt is the "conversation start" group, anchored at the top.

export const TURN_PILL_IDLE_HIDE_MS = 1500;
export const TURN_PILL_LIST_MIN_GROUPS = 3;

export interface TurnGroup {
  /** The user item that opens the group; null for the conversation-start group. */
  key: string | null;
  summary: { zh: string; en: string };
}

/** First non-empty line; an attachment-only prompt reads 「文件：名」 / 「图片 ×N」. */
export function promptSummary(item: ChatItem): { zh: string; en: string } {
  const line = item.text.split("\n").map((l) => l.trim()).find(Boolean);
  if (line) return { zh: line, en: line };
  const images = (item.localImages?.length ?? 0) + item.attachments.filter((a) => a.kind === "image").length + item.images.length;
  const file = item.localFiles?.[0] ?? item.attachments.find((a) => a.kind === "download")?.name;
  if (file) return { zh: `文件：${file}`, en: `File: ${file}` };
  if (images) return { zh: `图片 ×${images}`, en: `Image ×${images}` };
  return { zh: "（空）", en: "(empty)" };
}

export function turnGroups(items: readonly ChatItem[]): TurnGroup[] {
  const groups: TurnGroup[] = [];
  const firstUser = items.findIndex((i) => i.role === "user" && !i.note);
  if (firstUser !== 0 && items.length) groups.push({ key: null, summary: { zh: "会话开始", en: "Conversation start" } });
  for (const item of items) {
    if (item.role === "user" && !item.note) groups.push({ key: item.key, summary: promptSummary(item) });
  }
  return groups;
}

/**
 * The group whose content is at the top of the viewport, or null when its anchor is on screen (no
 * pill needed). `tops` maps each group key to its bubble's top and bottom inside the scroller;
 * the conversation-start group is anchored at 0.
 */
export function pillGroup(
  groups: readonly TurnGroup[],
  tops: ReadonlyMap<string, { top: number; bottom: number }>,
  scrollTop: number,
): TurnGroup | null {
  let current: TurnGroup | null = null;
  for (const group of groups) {
    const top = group.key === null ? 0 : tops.get(group.key)?.top;
    if (top === undefined) continue;
    if (top <= scrollTop + 1) current = group;
    else break;
  }
  if (!current) return null;
  if (current.key === null) return scrollTop > 0 ? current : null;
  const anchor = tops.get(current.key)!;
  // The bubble is (partly) on screen: the reader can see where this group starts.
  return anchor.bottom > scrollTop ? null : current;
}
