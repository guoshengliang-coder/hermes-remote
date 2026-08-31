package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.ProfileSessionsDto
import com.hermes.client.data.network.SessionDto
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    private val rest = mockk<HermesRestApi>()
    private val repo = SessionRepository(rest)

    private fun dto(id: String, source: String?, msgs: Int, archived: Boolean = false) =
        SessionDto(sessionId = id, source = source, messageCount = msgs, archived = archived, profile = "personal")

    // Parity with desktop SIDEBAR_EXCLUDED_SOURCES: the list hides cron, subagent, tool, and every
    // messaging-platform source, plus empty (0-message) sessions. Local sources (tui/cli/…), the
    // app's own hermes-dispatch sessions, and unknown/null sources are kept.
    @Test fun listAllProfiles_hides_excluded_sources_and_empty_sessions() = runTest {
        assertFalse(repo.hasLoadedAllProfiles())
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("keep-tui", "tui", 5),
                dto("hide-cron", "cron", 12),           // cron → hidden
                dto("hide-subagent", "subagent", 8),    // subagent → hidden
                dto("hide-tool", "tool", 4),            // tool → hidden
                dto("hide-telegram", "telegram", 6),    // messaging → hidden
                dto("hide-empty", "tui", 0),            // 0 messages → hidden
                dto("keep-dispatch", "hermes-dispatch", 2),
                dto("keep-null-source", null, 3),       // unknown/null source → kept
            ),
        )
        assertEquals(
            listOf("keep-tui", "keep-dispatch", "keep-null-source"),
            repo.listAllProfiles().map { it.id },
        )
        assertTrue(repo.hasLoadedAllProfiles())
    }

    @Test fun archivedAllProfiles_also_hides_cron_and_empty() = runTest {
        coEvery { rest.profileSessions(any(), true) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("a-keep", "cli", 3, archived = true),
                dto("a-cron", "cron", 9, archived = true),
                dto("a-empty", "tui", 0, archived = true),
            ),
        )
        assertEquals(listOf("a-keep"), repo.archivedAllProfiles().map { it.id })
    }

    @Test fun history_removes_every_internal_tool_payload_shape_at_data_boundary() = runTest {
        coEvery { rest.messages("session-1", "default") } returns listOf(
            MessageDto(1, "user", "请检查环境"),
            MessageDto(2, "tool", "<untrusted_tool_result source=\"web_search\">raw</untrusted_tool_result>"),
            MessageDto(3, "tool", "{\"output\":\"health=200\",\"exit_code\":0}"),
            MessageDto(4, "function", "table\\n| host | port |"),
            MessageDto(5, "tool_result", "{\"success\":true,\"content\":\"skill body\"}"),
            MessageDto(6, "tool_call", "internal call arguments"),
            MessageDto(7, "assistant", "环境检查完成。"),
        )

        val history = repo.history("session-1", "default")

        assertEquals(listOf(Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals(listOf("请检查环境", "环境检查完成。"), history.map { it.text })
        assertEquals(listOf("h-0-1", "h-1-7"), history.map { it.id })
    }

    @Test fun history_keeps_non_tool_system_notices() = runTest {
        coEvery { rest.messages("session-2", null) } returns listOf(
            MessageDto(1, "system", "会话已恢复"),
        )

        val history = repo.history("session-2")

        assertEquals(1, history.size)
        assertEquals(Role.SYSTEM, history.single().role)
        assertEquals("会话已恢复", history.single().text)
    }
}
