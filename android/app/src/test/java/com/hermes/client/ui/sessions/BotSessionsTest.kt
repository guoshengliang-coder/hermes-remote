package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun session(
    id: String, source: String?, lastActive: Long? = 0L, messageCount: Int = 3,
    archived: Boolean = false,
) = Session(
    id = id, title = id, model = null, provider = null, messageCount = messageCount,
    profile = "default", archived = archived, source = source, lastActive = lastActive,
)

class BotSessionsTest {
    @Test fun bot_sources_are_the_messaging_half_of_the_excluded_set() {
        assertTrue("dingtalk" in BOT_SOURCES)
        assertTrue("slack" in BOT_SOURCES)
        assertTrue("email" in BOT_SOURCES)
        // Machinery, not conversation — these belong to their own surfaces.
        assertFalse("cron" in BOT_SOURCES)
        assertFalse("subagent" in BOT_SOURCES)
        assertFalse("tool" in BOT_SOURCES)
        // The app's own sessions must never be swept in here.
        assertFalse("hermes_remote" in BOT_SOURCES)
        assertFalse("tui" in BOT_SOURCES)
    }

    @Test fun sections_are_grouped_by_channel_and_ordered_by_recency() {
        val sections = botSections(
            listOf(
                session("a", "dingtalk", lastActive = 100),
                session("b", "slack", lastActive = 300),
                session("c", "dingtalk", lastActive = 200),
            ),
        )
        assertEquals(listOf("slack", "dingtalk"), sections.map { it.source })
        assertEquals(listOf("c", "a"), sections[1].sessions.map { it.id })
    }

    @Test fun local_archived_and_empty_sessions_are_left_out() {
        val sections = botSections(
            listOf(
                session("local", "hermes_remote"),
                session("cronjob", "cron"),
                session("archived", "dingtalk", archived = true),
                session("empty", "dingtalk", messageCount = 0),
                session("kept", "dingtalk"),
            ),
        )
        assertEquals(listOf("kept"), sections.single().sessions.map { it.id })
    }

    @Test fun a_session_with_no_source_is_not_a_bot_session() {
        assertTrue(botSections(listOf(session("x", null))).isEmpty())
    }

    /** The tab is conditional in both directions: it appears with the first channel and goes away
     *  again when the last one is removed. */
    @Test fun the_tab_appears_only_when_there_is_something_behind_it() {
        assertFalse(showBotsTab(configuredChannelCount = 0, botSessionCount = 0))
        assertTrue(showBotsTab(configuredChannelCount = 1, botSessionCount = 0))
        // History outlives the configuration: a channel removed today still has yesterday's
        // conversations, and hiding them would look like data loss.
        assertTrue(showBotsTab(configuredChannelCount = 0, botSessionCount = 4))
    }

    /** Without a channel there is only ONE batch of chats, so the row is absent entirely — the
     *  Chats screen is then a plain list (docs/DESIGN.md §5.16). */
    @Test fun the_segment_row_is_empty_without_bots_and_two_wide_with_them() {
        assertTrue(chatsSegmentModes(showBots = false).isEmpty())
        assertEquals(listOf(ViewMode.SESSIONS, ViewMode.BOTS), chatsSegmentModes(showBots = true))
    }

    @Test fun bot_sources_stay_inside_the_excluded_set() {
        assertTrue(SessionRepository.EXCLUDED_SOURCES.containsAll(BOT_SOURCES))
    }
}
