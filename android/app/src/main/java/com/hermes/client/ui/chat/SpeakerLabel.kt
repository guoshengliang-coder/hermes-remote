package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.sessions.BotOrigin
import com.hermes.client.ui.sessions.botPeerLabel

/**
 * Who the `role=user` turns in this conversation belong to.
 *
 * In an ordinary chat that is always you, and the app says so everywhere it names a speaker —
 * copied transcripts, Markdown and image exports, the prompts list. In a bot conversation it is
 * whoever was talking to Hermes on the other app, and calling their words yours is wrong in a way
 * that survives export: a transcript shared with a colleague would attribute their own messages to
 * you.
 */
fun userSpeakerLabel(origin: BotOrigin?, language: AppLanguage): String =
    if (origin == null) {
        localized(language, "你", "You")
    } else {
        botPeerLabel(origin.displayName, origin.chatType, origin.source, language)
    }

/** Title of the prompts list: they are not "your" prompts when someone else asked them. */
fun promptListTitle(origin: BotOrigin?, language: AppLanguage): String =
    if (origin == null) {
        localized(language, "我的提问", "Your prompts")
    } else {
        localized(language, "对方的提问", "Their prompts")
    }

/**
 * The label drawn above one user bubble, or null when the turn needs no attribution.
 *
 * Necessary because the composer works in bot conversations now: the right-hand column carries
 * both the peer's messages and the ones you type here, and a blanket peer label would sign your
 * own words with someone else's name.
 *
 * Known limit: [locallySentIds] lives in memory for the life of the view model. After 刷新对话
 * replaces a locally sent turn with its server row, that turn is attributed to the peer again.
 * Tolerable — the send dialog has already said the message stays inside Hermes — and preferable to
 * persisting a second source of truth about who said what.
 */
fun userTurnLabel(
    messageId: String,
    locallySentIds: Set<String>,
    origin: BotOrigin?,
    language: AppLanguage,
): String? = when {
    origin == null -> null
    messageId in locallySentIds -> localized(language, "你", "You")
    else -> botPeerLabel(origin.displayName, origin.chatType, origin.source, language)
}

/**
 * The bot conversation the chat screen is currently showing, or null for an ordinary one.
 *
 * A CompositionLocal rather than a parameter because the label is needed deep in the message list
 * (`ChatMessageList` → `MessageBubble` → `UserBubble`) and in the image exporter, and threading it
 * through would widen three signatures that are already long. Same pattern as [LocalChatSearch].
 */
val LocalBotOrigin = androidx.compose.runtime.compositionLocalOf<BotOrigin?> { null }

/** Ids of the turns sent from this device in this view-model's lifetime. See [userTurnLabel]. */
val LocalLocallySentIds = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }
