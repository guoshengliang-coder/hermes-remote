package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session

/**
 * The `source` values that mean "a person talked to Hermes on some other app". These are the
 * messaging half of [SessionRepository.EXCLUDED_SOURCES] — internal session sources stay out
 * because they are machinery, not conversations.
 *
 * Kept as a derivation rather than a second hand-written list: a platform added to the excluded
 * set upstream then shows up here too, instead of silently belonging to neither surface.
 */
val BOT_SOURCES: Set<String> =
    SessionRepository.EXCLUDED_SOURCES - SessionRepository.INTERNAL_SESSION_SOURCES

/** One channel's conversations, newest first. */
data class BotSection(val source: String, val sessions: List<Session>)

/**
 * Everything the chat screen needs to know that a conversation came from another app: which
 * channel, and who is on the other end. Null for an ordinary local session — the chat screen
 * branches on null, never on a source string of its own.
 */
data class BotOrigin(val source: String, val displayName: String?, val chatType: String?)

/**
 * Whether this `source` means "a person talked to Hermes on some other app".
 *
 * Internal sources are deliberately NOT bot sessions even though they are in
 * [SessionRepository.EXCLUDED_SOURCES]: they are machinery, not conversations with a peer.
 */
fun isBotSession(source: String?): Boolean = (source ?: "") in BOT_SOURCES

/** [BotOrigin] for a session, or null when it is an ordinary local conversation. */
fun botOriginOf(session: Session?): BotOrigin? {
    val source = session?.source
    if (!isBotSession(source)) return null
    return BotOrigin(source!!, session.displayName, session.chatType)
}

/**
 * Groups bot conversations by the channel they came from, channels ordered by their most recent
 * activity. Deliberately NOT the recency buckets the Chats list uses: here the question is
 * "what has Hermes been doing on DingTalk", so the channel is the top tier.
 */
fun botSections(sessions: List<Session>): List<BotSection> =
    sessions
        .filter { (it.source ?: "") in BOT_SOURCES && !it.archived && it.messageCount > 0 }
        .groupBy { it.source!! }
        .map { (source, rows) -> BotSection(source, rows.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }) }
        .sortedByDescending { section -> section.sessions.firstOrNull()?.lastActive ?: Long.MIN_VALUE }

/**
 * Whether the Bots segment exists at all. It appears only once this Hermes actually has a channel
 * configured — someone who never set one up should not carry an empty tab around, and the tab must
 * disappear again if every channel is removed.
 */
fun showBotsTab(configuredChannelCount: Int, botSessionCount: Int): Boolean =
    configuredChannelCount > 0 || botSessionCount > 0

/**
 * Display name for a channel. Hermes' `source` tokens are lowercase ids (`dingtalk`, `wecom`),
 * which read as debug output; the ones a Chinese user is likely to have get their real names,
 * and anything else is title-cased rather than hidden.
 */
fun botSourceLabel(source: String): String = when (source) {
    "dingtalk" -> "钉钉"
    "feishu" -> "飞书"
    "weixin" -> "微信"
    "wecom" -> "企业微信"
    "qqbot" -> "QQ"
    "yuanbao" -> "元宝"
    "email" -> "Email"
    "sms" -> "SMS"
    "webhook" -> "Webhook"
    "api_server" -> "API"
    "bluebubbles" -> "iMessage"
    "whatsapp" -> "WhatsApp"
    "homeassistant" -> "Home Assistant"
    else -> source.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/**
 * The status line under a bot row: `<relative time> · <N messages>` (docs/DESIGN.md §5.16).
 *
 * The Chats list can leave time implicit because its groups ARE the time buckets. The Bots list
 * groups by channel instead, so a row has to say when it last happened or the reader has no way
 * to tell — which is what HG-54 asked for.
 *
 * Pure, with [nowMs] passed in, the same contract as `relativeTimeLabel` — that is what makes both
 * testable without a clock. Returns null when there is nothing to say: §5.2 gates the status line
 * on the TEXT, not on the condition, because a blank Text still costs a full line.
 */
fun botStatusLine(
    session: Session,
    nowMs: Long,
    language: com.hermes.client.ui.localization.AppLanguage,
): String? {
    val zh = language == com.hermes.client.ui.localization.AppLanguage.ZH
    val count = session.messageCount.takeIf { it > 0 }?.let {
        if (zh) "$it 条" else if (it == 1) "1 message" else "$it messages"
    }
    // `relativeTimeLabel` renders a null timestamp as "—", which reads as a value rather than as
    // absence. Upstream omits `last_active` often enough on these rows that the em dash would
    // become the common case, so the segment is dropped instead.
    val since = session.lastActive?.let {
        com.hermes.client.ui.util.relativeTimeLabel(it, nowMs, language)
    }
    return listOfNotNull(since, count).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
