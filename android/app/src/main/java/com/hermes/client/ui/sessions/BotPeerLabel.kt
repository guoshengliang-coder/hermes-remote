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

/** The banner's version: channel first, then who, then the read-only note. */
fun botOriginLabel(displayName: String?, chatType: String?, source: String?, language: AppLanguage): String {
    val channel = botSourceLabel(source ?: "")
    val who = displayName?.trim()?.ifBlank { null }
        ?: when (chatType?.trim()?.lowercase()) {
            "dm" -> localized(language, "私聊", "direct message")
            "group" -> localized(language, "群聊", "group")
            else -> null
        }
    val head = localized(language, "来自$channel", "From $channel")
    return listOfNotNull(head, who, localized(language, "只读", "read-only")).joinToString(" · ")
}
