package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage

/** Resolve current files rather than retaining a stale image snapshot when the viewer opens. */
internal fun transcriptViewerItems(
    messages: List<ChatMessage>,
    ownerId: String,
    currentImageId: String?,
): List<ImageViewerItem> {
    // The thumbnail belongs to a displayed turn, which may merge several assistant records.
    // Use the same grouping as the transcript so images after tool calls remain reachable.
    val turns = messages.organizedConversationTurns()
    val owner = turns.firstOrNull { it.id == ownerId }
        // History reconciliation can replace message ids or prepend an earlier assistant record.
        // Image ids survive both. Recover only an unambiguous turn; never build a session album.
        ?: currentImageId?.let { imageId ->
            turns.singleOrNull { turn -> turn.images.any { it.id == imageId } }
        }
    return owner?.images.orEmpty().mapNotNull { image ->
        image.localPath?.let { ImageViewerItem(image.id, ImageSource.Path(it), image) }
    }
}
