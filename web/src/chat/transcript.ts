import type { Language } from "../errors";
import type { ChatItem } from "./model";

// Small pure helpers of the chat page: time separators and the new-chat greeting (Android
// ChatUiState.showsTimeSeparator / ChatComponents.formatTimeSeparator / NewChatGreeting.kt) and
// transcript export (MessageActions.transcriptText, TranscriptExport.kt).

const pad = (n: number) => String(n).padStart(2, "0");
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** A separator above a turn when it is the first stamped one or ≥ 20 minutes after the previous. */
export function showsTimeSeparator(previousMs: number | null, ms: number | null, gapMinutes = 20): boolean {
  if (ms === null) return false;
  if (previousMs === null) return true;
  return ms - previousMs >= gapMinutes * 60_000;
}

export function formatTimeSeparator(ms: number, language: Language, nowMs = Date.now()): string {
  const d = new Date(ms);
  const today = new Date(nowMs);
  today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const zh = language !== "en";
  if (ms >= today.getTime()) return hm;
  if (ms >= yesterday.getTime()) return zh ? `昨天 ${hm}` : `Yesterday ${hm}`;
  if (d.getFullYear() === today.getFullYear()) return zh ? `${d.getMonth() + 1}月${d.getDate()}日 ${hm}` : `${MONTHS[d.getMonth()]} ${d.getDate()}, ${hm}`;
  return zh ? `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${hm}` : `${MONTHS[d.getMonth()]} ${d.getDate()} ${d.getFullYear()}, ${hm}`;
}

export function greetingForHour(hour: number, language: Language): string {
  const zh = language !== "en";
  if (hour >= 5 && hour <= 8) return zh ? "早上好" : "Good morning";
  if (hour >= 9 && hour <= 13) return zh ? "上午好" : "Hello";
  if (hour >= 14 && hour <= 18) return zh ? "下午好" : "Good afternoon";
  if (hour >= 19 && hour <= 23) return zh ? "晚上好" : "Good evening";
  return zh ? "夜深了" : "Up late";
}

function roleLabel(item: ChatItem, language: Language): string {
  const zh = language !== "en";
  if (item.role === "system") return zh ? "系统" : "System";
  if (item.role === "user") return zh ? "你" : "You";
  return zh ? "助手" : "Assistant";
}

function exportable(items: readonly ChatItem[]): ChatItem[] {
  return items.filter((i) => !i.note && !i.streaming && i.send !== "failed");
}

/** Plain, role-labelled transcript; bodies verbatim (Markdown kept); blank turns skipped. */
export function transcriptText(items: readonly ChatItem[], language: Language): string {
  return exportable(items)
    .filter((i) => i.text.trim())
    .map((i) => `${roleLabel(i, language)}:\n${i.text}`)
    .join("\n\n");
}

function stamp(ms: number, withSeconds: boolean): string {
  const d = new Date(ms);
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return withSeconds
    ? `${date.replaceAll("-", "")}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
    : `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** A Markdown document: title, one provenance line, one `##` section per turn. "" when empty. */
export function transcriptMarkdown(title: string | null, items: readonly ChatItem[], language: Language, exportedAtMs: number): string {
  const zh = language !== "en";
  const body = exportable(items).filter((i) => i.text.trim() || i.attachments.some((a) => a.kind === "download") || i.localFiles?.length);
  if (!body.length) return "";
  const heading = title?.trim() || (zh ? "对话记录" : "Chat transcript");
  let out = `# ${heading}\n\n> ${zh ? "导出时间：" : "Exported: "}${stamp(exportedAtMs, false)} · Hermes GO\n`;
  for (const item of body) {
    const files = [...item.attachments.filter((a) => a.kind === "download").map((a) => a.name), ...(item.localFiles ?? [])];
    const parts = [item.text.trim(), ...files.map((name) => `${zh ? "附件：" : "Attachment: "}${name}`)].filter(Boolean);
    out += `\n## ${roleLabel(item, language)}\n\n${parts.join("\n\n")}\n`;
  }
  return out;
}

/** A filename-safe base: `HermesGO-<title ≤40>-<yyyyMMdd-HHmmss>`. */
export function transcriptFileBaseName(title: string | null, exportedAtMs: number): string {
  const cleaned = (title ?? "")
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\.+$/, "")
    .slice(0, 40)
    .trim();
  const when = stamp(exportedAtMs, true);
  return cleaned ? `HermesGO-${cleaned}-${when}` : `HermesGO-${when}`;
}
