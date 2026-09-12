package com.hermes.client.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HG-39. `NavDestination.route` is the route PATTERN, not the resolved URL, so these are the
 * strings the back handler actually sees.
 */
class ChatBackTargetTest {
    private val chatPattern = "chat/{id}?device={device}&profile={profile}&title={title}&new={new}&q={q}"

    @Test fun chatOpenedFromAnotherChatPopsOneLayer() {
        // The regression: this used to pop through to "sessions", so ＋ from a conversation and
        // then back landed on the list instead of the conversation the user was reading.
        assertEquals(ChatBackTarget.POP_ONE, chatBackTarget(chatPattern))
    }

    @Test fun projectAndArchiveHubsPopOneLayer() {
        assertEquals(ChatBackTarget.POP_ONE, chatBackTarget("projects"))
        assertEquals(ChatBackTarget.POP_ONE, chatBackTarget("archived"))
    }

    @Test fun everythingElsePopsToTheChatsList() {
        // "sessions" may sit several entries down, so it is a pop-TO, not a single pop.
        assertEquals(ChatBackTarget.POP_TO_SESSIONS, chatBackTarget("sessions"))
        assertEquals(ChatBackTarget.POP_TO_SESSIONS, chatBackTarget("search?q={q}"))
        // A stack restored with nothing beneath: the caller's fallback builds a fresh Chats root.
        assertEquals(ChatBackTarget.POP_TO_SESSIONS, chatBackTarget(null))
    }

    @Test fun aRouteMerelyContainingChatIsNotAChat() {
        assertEquals(ChatBackTarget.POP_TO_SESSIONS, chatBackTarget("settings_chat"))
    }
}
