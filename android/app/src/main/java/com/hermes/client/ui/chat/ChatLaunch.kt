package com.hermes.client.ui.chat

import com.hermes.client.domain.Session

/**
 * Everything the chat screen already knows at navigation time.
 *
 * Passing this context avoids briefly (or permanently, when a metadata refresh fails) labelling an
 * existing conversation as a new one. The route still works with only an id for notifications and
 * older deep links; those callers simply fall back to repository metadata.
 */
data class ChatLaunch(
    val sessionId: String,
    val profile: String? = null,
    val title: String? = null,
    val isNew: Boolean = false,
    /**
     * Search text to carry into the chat: the chat opens with its in-chat search expanded,
     * pre-filled, and positioned on the first hit (search-screen hit → chat handoff).
     */
    val initialQuery: String? = null,
) {
    companion object {
        fun existing(session: Session, initialQuery: String? = null) = ChatLaunch(
            sessionId = session.id,
            profile = session.profile,
            title = session.title,
            initialQuery = initialQuery?.takeIf { it.isNotBlank() },
        )

        fun unknown(sessionId: String, profile: String? = null) = ChatLaunch(
            sessionId = sessionId,
            profile = profile,
        )

        /** A message hit from the gateway search: enough context to open without a lookup. */
        fun searchHit(sessionId: String, profile: String?, title: String?, query: String) = ChatLaunch(
            sessionId = sessionId,
            profile = profile,
            title = title?.takeIf { it.isNotBlank() },
            initialQuery = query.takeIf { it.isNotBlank() },
        )

        fun new(sessionId: String, profile: String? = null) = ChatLaunch(
            sessionId = sessionId,
            profile = profile,
            isNew = true,
        )
    }
}
