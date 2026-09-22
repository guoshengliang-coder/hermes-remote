import type { SessionListItem } from "../hermes/types";

// Which list a session belongs in, by its `source` (Android SessionRepository.EXCLUDED_SOURCES /
// INTERNAL_SESSION_SOURCES and ui/sessions/BotSessions.kt BOT_SOURCES). Upstream owns these
// strings; a new platform shows up as an ordinary row until it is listed here.

/** Machinery, not conversations: never listed. */
export const INTERNAL_SESSION_SOURCES: ReadonlySet<string> = new Set(["cron", "subagent", "tool", "kanban", "oneshot"]);

/** "A person talked to Hermes on some other app": their own surface, not the chat list. */
export const BOT_SOURCES: ReadonlySet<string> = new Set([
  "telegram", "discord", "slack", "mattermost", "matrix", "signal", "whatsapp",
  "bluebubbles", "homeassistant", "email", "sms", "webhook", "api_server",
  "weixin", "wecom", "qqbot", "yuanbao", "dingtalk", "feishu",
]);

export function isInternalSession(session: Pick<SessionListItem, "source">): boolean {
  return INTERNAL_SESSION_SOURCES.has(session.source ?? "");
}

export function isBotSession(session: Pick<SessionListItem, "source">): boolean {
  return BOT_SOURCES.has(session.source ?? "");
}

/**
 * Worth a row at all: not machinery and not an empty scratch session. A Hermes that does not
 * report `message_count` keeps every row.
 */
export function isListable(session: Pick<SessionListItem, "source" | "message_count">): boolean {
  if (isInternalSession(session)) return false;
  return typeof session.message_count !== "number" || session.message_count > 0;
}
