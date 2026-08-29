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
) {
    companion object {
        fun existing(session: Session) = ChatLaunch(
            sessionId = session.id,
            profile = session.profile,
            title = session.title,
        )

        fun unknown(sessionId: String, profile: String? = null) = ChatLaunch(
            sessionId = sessionId,
            profile = profile,
        )

        fun new(sessionId: String, profile: String? = null) = ChatLaunch(
            sessionId = sessionId,
            profile = profile,
            isNew = true,
        )
    }
}
