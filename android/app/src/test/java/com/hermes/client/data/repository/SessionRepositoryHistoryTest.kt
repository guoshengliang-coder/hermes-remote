package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History used to drop every role="tool" row before mapping, so a rebuilt turn had calls without
 * outcomes while the live turn had both. The rows are still not turns, but they are joined back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryHistoryTest {
    private fun calls(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test fun historyJoinsToolResultRowsOntoTheAssistantTurn() = runTest {
        val rest = mockk<HermesRestApi>()
        coEvery { rest.messages("s1", "personal") } returns listOf(
            MessageDto(id = 1, role = "user", content = "昨天数据如何？"),
            MessageDto(
                id = 2, role = "assistant", content = "",
                toolCalls = calls("""[{"id":"call_a","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"date\"}"}}]"""),
            ),
            MessageDto(id = 3, role = "tool", toolCallId = "call_a", toolName = "terminal",
                content = """{"output": "2026-09-05 Saturday CST", "exit_code": 0}"""),
            MessageDto(id = 4, role = "assistant", content = "完成"),
        )
        val repository = SessionRepository(rest, this)

        val history = repository.history("s1", "personal")

        // The tool row is not a turn …
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.ASSISTANT), history.map { it.role })
        // … but its outcome is on the card.
        val call = history[1].tools.single()
        assertEquals("terminal", call.name)
        assertEquals("date", call.command)
        assertEquals(0, call.exitCode)
        assertTrue(call.output.contains("2026-09-05 Saturday CST"))
    }
}
