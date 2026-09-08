package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session

/**
 * The `source` values that mean "a person talked to Hermes on some other app". These are the
 * messaging half of [SessionRepository.EXCLUDED_SOURCES] — cron, subagent and tool stay out
 * because they are machinery, not conversations.
 *
 * Kept as a derivation rather than a second hand-written list: a platform added to the excluded
 * set upstream then shows up here too, instead of silently belonging to neither surface.
 */
val BOT_SOURCES: Set<String> = SessionRepository.EXCLUDED_SOURCES - setOf("cron", "subagent", "tool")

/** One channel's conversations, newest first. */
data class BotSection(val source: String, val sessions: List<Session>)

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
