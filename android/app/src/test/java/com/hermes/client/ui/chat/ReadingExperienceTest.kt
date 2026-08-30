package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingExperienceTest {
    private fun msg(
        id: String,
        text: String = "",
        thinking: String = "",
        tools: List<ToolCall> = emptyList(),
        ts: Long? = null,
        role: Role = Role.ASSISTANT,
    ) = ChatMessage(id = id, role = role, text = text, thinking = thinking, tools = tools, timestamp = ts)

    // --- search hits ---

    @Test fun hitsCoverTextThinkingAndTools() {
        val messages = listOf(
            msg("1", text = "网关配置检查通过"),
            msg("2", thinking = "先检查网关再看证书"),
            msg("3", tools = listOf(ToolCall("t", "Bash", ToolStatus.DONE, output = "网关重启成功"))),
        )
        val hits = searchHits(messages, "网关")
        assertEquals(3, hits.size)
        assertEquals(SearchSource.TEXT, hits[0].source)
        assertEquals(SearchSource.THINKING, hits[1].source)
        assertEquals(SearchSource.TOOL, hits[2].source)
        assertEquals(listOf(0, 1, 2), hits.map { it.turnIndex })
    }

    @Test fun multipleOccurrencesInOneTurnAreSeparateHits() {
        val hits = searchHits(listOf(msg("1", text = "错误A，然后错误B，最后错误C，还有错误D")), "错误")
        assertEquals(3, hits.size) // capped at 3 per field
        assertTrue(hits.all { it.turnIndex == 0 })
    }

    @Test fun snippetCarriesContextAndEllipses() {
        val body = "前" .repeat(40) + "目标词" + "后".repeat(40)
        val s = snippetAround(body, body.indexOf("目标词"), 3)
        assertTrue(s.contains("目标词"))
        assertTrue(s.startsWith("…"))
        assertTrue(s.endsWith("…"))
    }

    @Test fun blankQueryYieldsNoHits() {
        assertEquals(0, searchHits(listOf(msg("1", text = "abc")), "  ").size)
    }

    // --- time separators ---

    @Test fun separatorRules() {
        assertFalse(showsTimeSeparator(previousTs = null, ts = null))
        assertTrue(showsTimeSeparator(previousTs = null, ts = 1_000L))
        assertFalse(showsTimeSeparator(previousTs = 1_000L, ts = 1_000L + 19 * 60_000))
        assertTrue(showsTimeSeparator(previousTs = 1_000L, ts = 1_000L + 21 * 60_000))
    }

    @Test fun reconciledHistoryInheritsLiveStamps() {
        val live = listOf(
            msg("u", role = Role.USER, text = "hi", ts = 111L),
            msg("a", text = "reply", ts = 222L),
        )
        val history = listOf(
            msg("h-0", role = Role.USER, text = "hi"),
            msg("h-1", text = "reply refreshed"),
        )
        val merged = inheritTimestamps(history, live)
        assertEquals(111L, merged[0].timestamp)
        assertEquals(222L, merged[1].timestamp)
        // role mismatch never inherits a wrong stamp
        val mismatched = inheritTimestamps(listOf(msg("x", role = Role.USER, text = "q")), listOf(msg("y", text = "a", ts = 9L)))
        assertEquals(null, mismatched[0].timestamp)
        // server-provided stamps win
        val stamped = inheritTimestamps(listOf(msg("s", text = "a", ts = 5L)), live)
        assertEquals(5L, stamped[0].timestamp)
    }
}
