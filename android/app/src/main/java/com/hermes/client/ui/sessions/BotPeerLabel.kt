package com.hermes.client.ui.sessions

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Who a bot conversation is with, as one short label.
 *
 * Upstream fills `display_name` for a group and leaves it blank for a direct message, so a label
 * built from that field alone rendered every DM as a bare "在钉钉" — which tells the reader nothing
 * about which of their DingTalk conversations the record is. `chat_type` closes the gap: a DM says
 * so, a group says so when it has no name of its own.
 */
fun botPeerLabel(displayName: String?, chatType: String?, source: String?, language: AppLanguage): String {
    val channel = botSourceLabel(source ?: "")
    val who = displayName?.trim()?.ifBlank { null }
        ?: when (chatType?.trim()?.lowercase()) {
            "dm" -> localized(language, "私聊", "Direct message")
            "group" -> localized(language, "群聊", "Group")
            else -> null
        }
    return listOfNotNull(who, localized(language, "在$channel", "on $channel")).joinToString(" · ")
}

/**
 * The subtitle version: channel first, then who.
 *
 * It used to end in "只读 / read-only". That was wrong twice over: the page can be written to now,
 * and the claim it rested on — that anything sent from here would appear as the bot on the other
 * platform — described a delivery that never happens. See docs/DESIGN.md §5.16.
 */
fun botOriginLabel(displayName: String?, chatType: String?, source: String?, language: AppLanguage): String {
    val channel = botSourceLabel(source ?: "")
    val who = displayName?.trim()?.ifBlank { null }
        ?: when (chatType?.trim()?.lowercase()) {
            "dm" -> localized(language, "私聊", "direct message")
            "group" -> localized(language, "群聊", "group")
            else -> null
        }
    val head = localized(language, "来自$channel", "From $channel")
    return listOfNotNull(head, who).joinToString(" · ")
}

/**
 * Title of the one-time dialog shown before the first message a person sends into a bot
 * conversation. Named per channel: this page carries Slack and Feishu conversations too, and a
 * Slack session must not be told about DingTalk.
 */
fun botSendNoticeTitle(source: String?, language: AppLanguage): String {
    val channel = botSourceLabel(source ?: "")
    return localized(language, "这条不会发到$channel", "This won't reach $channel")
}

/**
 * Body of that dialog. Two facts, in the order that matters: the message does not leave Hermes,
 * and it nonetheless joins the context the other side's next question is answered with.
 *
 * The first is a property of where the turn runs — a dashboard `prompt.submit` never touches the
 * platform adapter, which lives in the gateway process — so it holds on every channel. The second
 * is the real cost, and the reason this dialog exists at all.
 */
fun botSendNoticeBody(source: String?, language: AppLanguage): String {
    val channel = botSourceLabel(source ?: "")
    return localized(
        language,
        "你在这里说的话只存在 Hermes 里，不会出现在$channel，对方看不到。\n\n" +
            "但它进了这条会话的上下文：对方下次在$channel 找 Hermes 时，Hermes 会带着这句话回答。",
        "What you say here stays inside Hermes — it never appears on $channel, and the other " +
            "person won't see it.\n\nIt does join this conversation's context, though: next " +
            "time they ask Hermes something on $channel, Hermes answers with your message in mind.",
    )
}
