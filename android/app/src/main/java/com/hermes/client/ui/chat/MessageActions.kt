package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/** The text of the most recent USER message, or null if there is none (used to re-ask). */
fun lastUserMessageText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }

/**
 * Render the conversation to a plain-text, role-labeled transcript. Body text is verbatim
 * (markdown preserved). Blank-text turns (tool-only / still-streaming stubs) are skipped.
 */
fun transcriptText(messages: List<ChatMessage>, language: AppLanguage = AppLanguage.EN): String =
    messages
        .filter { it.text.isNotBlank() }
        .joinToString("\n\n") { m ->
            val label = when {
                m.isError -> localized(language, "错误", "Error")
                m.role == Role.SYSTEM -> localized(language, "系统", "System")
                m.role == Role.USER -> localized(language, "你", "You")
                else -> localized(language, "助手", "Assistant")
            }
            "$label:\n${m.text}"
        }
