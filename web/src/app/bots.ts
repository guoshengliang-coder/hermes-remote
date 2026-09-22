import type { Language } from "../errors";
import type { SessionListItem } from "../hermes/types";
import { lastActiveMs, relativeTime } from "./grouping";
import { isBotSession } from "./sources";

// Bot conversations (Android ui/sessions/BotSessions.kt, BotPeerLabel.kt, DESIGN §5.16): a person
// talked to Hermes on another app. Their own segment, grouped by channel, not by recency.

/** Real names for the channels a Chinese user is likely to have; others are title-cased. */
export function botSourceLabel(source: string): string {
  const known: Record<string, string> = {
    dingtalk: "钉钉",
    feishu: "飞书",
    weixin: "微信",
    wecom: "企业微信",
    qqbot: "QQ",
    yuanbao: "元宝",
    email: "Email",
    sms: "SMS",
    webhook: "Webhook",
    api_server: "API",
    bluebubbles: "iMessage",
    whatsapp: "WhatsApp",
    homeassistant: "Home Assistant",
  };
  return known[source] ?? source.split("_").map((p) => (p ? p[0]!.toUpperCase() + p.slice(1) : p)).join(" ");
}

function peer(displayName: string | null | undefined, chatType: string | null | undefined, language: Language, lower: boolean): string | null {
  const name = displayName?.trim();
  if (name) return name;
  const type = chatType?.trim().toLowerCase();
  if (type === "dm") return language === "en" ? (lower ? "direct message" : "Direct message") : "私聊";
  if (type === "group") return language === "en" ? (lower ? "group" : "Group") : "群聊";
  return null;
}

/** Chat subtitle: 「来自钉钉 · 张三」 / "From DingTalk · group". */
export function botOriginLabel(session: Pick<SessionListItem, "display_name" | "chat_type" | "source">, language: Language): string {
  const channel = botSourceLabel(session.source ?? "");
  const head = language === "en" ? `From ${channel}` : `来自${channel}`;
  return [head, peer(session.display_name, session.chat_type, language, true)].filter(Boolean).join(" · ");
}

export function botSendNoticeTitle(source: string | null | undefined, language: Language): string {
  const channel = botSourceLabel(source ?? "");
  return language === "en" ? `This won't reach ${channel}` : `这条不会发到${channel}`;
}

export function botSendNoticeBody(source: string | null | undefined, language: Language): string {
  const channel = botSourceLabel(source ?? "");
  return language === "en"
    ? `What you say here stays inside Hermes — it never appears on ${channel}, and the other person won't see it.\n\nIt does join this conversation's context, though: next time they ask Hermes something on ${channel}, Hermes answers with your message in mind.`
    : `你在这里说的话只存在 Hermes 里，不会出现在${channel}，对方看不到。\n\n但它进了这条会话的上下文：对方下次在${channel} 找 Hermes 时，Hermes 会带着这句话回答。`;
}

export interface BotSection {
  source: string;
  sessions: SessionListItem[];
}

/** One section per channel, channels by latest activity, rows newest first. */
export function botSections(sessions: readonly SessionListItem[]): BotSection[] {
  const bySource = new Map<string, SessionListItem[]>();
  for (const s of sessions) {
    if (!isBotSession(s) || s.archived || (typeof s.message_count === "number" && s.message_count <= 0)) continue;
    const list = bySource.get(s.source!) ?? [];
    list.push(s);
    bySource.set(s.source!, list);
  }
  const newest = (s: SessionListItem) => lastActiveMs(s) ?? -Infinity;
  return [...bySource.entries()]
    .map(([source, rows]) => ({ source, sessions: [...rows].sort((a, b) => newest(b) - newest(a)) }))
    .sort((a, b) => newest(b.sessions[0]!) - newest(a.sessions[0]!));
}

/** `<relative time> · <N 条>`; null when there is nothing to say. */
export function botStatusLine(session: SessionListItem, nowMs: number, language: Language): string | null {
  const n = typeof session.message_count === "number" && session.message_count > 0 ? session.message_count : null;
  const count = n === null ? null : language === "en" ? (n === 1 ? "1 message" : `${n} messages`) : `${n} 条`;
  const since = lastActiveMs(session) !== null ? relativeTime(lastActiveMs(session), nowMs, language) : null;
  const parts = [since, count].filter((p): p is string => Boolean(p));
  return parts.length ? parts.join(" · ") : null;
}

const NOTICE_KEY = "hermes-go.botNoticeSeen";

/** The one-time "this won't reach DingTalk" notice, per channel (Android BotSendNoticeStore). */
export function botNoticeSeen(source: string): boolean {
  try {
    const seen: unknown = JSON.parse(localStorage.getItem(NOTICE_KEY) ?? "[]");
    return Array.isArray(seen) && seen.includes(source);
  } catch {
    return false;
  }
}

export function markBotNoticeSeen(source: string): void {
  try {
    const seen: unknown = JSON.parse(localStorage.getItem(NOTICE_KEY) ?? "[]");
    const list = Array.isArray(seen) ? seen.filter((v): v is string => typeof v === "string") : [];
    if (!list.includes(source)) localStorage.setItem(NOTICE_KEY, JSON.stringify([...list, source]));
  } catch {
    /* shown again next time */
  }
}

export function clearBotNotices(): void {
  try {
    localStorage.removeItem(NOTICE_KEY);
  } catch {
    /* nothing stored */
  }
}
