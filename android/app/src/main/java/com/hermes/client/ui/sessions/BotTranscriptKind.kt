package com.hermes.client.ui.sessions

import com.hermes.client.domain.ChatMessage
import com.hermes.client.ui.chat.isHiddenTimelineMessage
import com.hermes.client.ui.chat.timelineNoteFor
import com.hermes.client.ui.chat.withoutCompressionScaffolding

/** How one history row should be drawn in the bot transcript. */
enum class BotTurnKind {
    /** Hermes' internal bookkeeping: not rendered at all. */
    HIDDEN,

    /** Machine scaffolding the chat screen shows as a one-line note — never as somebody speaking. */
    NOTE,

    /** A real turn: the peer's message, or Hermes' reply. */
    TURN,
}

/**
 * Routes a row through the SAME classification the chat screen uses.
 *
 * This screen is a second renderer over the same history, and every time it decided for itself
 * what a row was, it got a class of message wrong that the chat screen had handled for months —
 * compaction handoffs, the todo snapshot, Markdown, and then a delegation report drawn as a chat
 * bubble with the DingTalk peer's name on it, as if a person had typed
 * "[ASYNC DELEGATION COMPLETE …]". Deferring to [timelineNoteFor] and [isHiddenTimelineMessage]
 * means a marker added upstream reaches both screens at once instead of only the one.
 */
fun botTurnKind(message: ChatMessage): BotTurnKind = when {
    isHiddenTimelineMessage(message) -> BotTurnKind.HIDDEN
    timelineNoteFor(message) != null -> BotTurnKind.NOTE
    // A carrier stripped down to nothing is scaffolding too; the chat screen drops it the same way.
    withoutCompressionScaffolding(message.text).isBlank() &&
        message.images.isEmpty() && message.files.isEmpty() -> BotTurnKind.HIDDEN
    else -> BotTurnKind.TURN
}
