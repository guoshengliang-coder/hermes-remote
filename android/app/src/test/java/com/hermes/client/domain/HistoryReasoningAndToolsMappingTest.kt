package com.hermes.client.domain

import com.hermes.client.data.network.MessagesDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway relays Hermes' `/api/sessions/{id}/messages` rows verbatim, reasoning and tool
 * calls included (verified against the Mac mini's `messages` table on 2026-09-05). Until then the
 * DTO did not declare the fields and `ignoreUnknownKeys` silently dropped them.
 */
class HistoryReasoningAndToolsMappingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Shape taken from a real row of session 20260905_102612_6d5fd4 (ids shortened).
    private val payload = """
        {"messages":[
          {"id":48352,"role":"user","content":"昨天公司数据如何？","reasoning":null,"tool_calls":null},
          {"id":48353,"role":"assistant","content":"",
           "reasoning":"**Planning BI integration**  现在先校准日期",
           "reasoning_content":"**Planning BI integration**  现在先校准日期",
           "tool_calls":[
             {"id":"call_tqFV","call_id":"call_tqFV","type":"function",
              "function":{"name":"terminal","arguments":"{\"command\":\"date '+%Y-%m-%d'\",\"workdir\":\"/tmp\"}"}},
             {"id":"call_ytKB","type":"function","function":{"name":"skill_view","arguments":"{\"name\":\"bi\"}"}},
             {"id":"call_ou4N","type":"function","function":{"name":"tool_call","arguments":"{\"name\":\"mcp__bi_query__query_data\",\"params\":{}}"}},
             {"id":"call_desc","type":"function","function":{"name":"tool_describe","arguments":"{\"name\":\"mcp__bi_query__query_data\"}"}}
           ],"finish_reason":"tool_calls"},
          {"id":48401,"role":"assistant","content":"最终回答","reasoning_content":"**Summarizing**","tool_calls":null,"finish_reason":"stop"}
        ]}
    """.trimIndent()

    @Test fun assistantRowsCarryReasoningAndToolCalls() {
        val messages = json.decodeFromString(MessagesDto.serializer(), payload).messages.map { it.toDomain() }

        assertEquals("", messages[0].thinking)
        assertTrue(messages[0].tools.isEmpty())

        assertEquals("**Planning BI integration**  现在先校准日期", messages[1].thinking)
        // A dynamic tool_call resolves to its real target, as the live tool.start event does;
        // tool_describe keeps its own name — that is also what the live card shows.
        assertEquals(
            listOf("terminal", "skill_view", "mcp__bi_query__query_data", "tool_describe"),
            messages[1].tools.map { it.name },
        )
        assertEquals(listOf("call_tqFV", "call_ytKB", "call_ou4N", "call_desc"), messages[1].tools.map { it.id })
        assertEquals("date '+%Y-%m-%d'", messages[1].tools[0].command)
        assertTrue(messages[1].tools.all { it.status == ToolStatus.DONE })

        assertEquals("**Summarizing**", messages[2].thinking)
        assertTrue(messages[2].tools.isEmpty())
    }

    @Test fun reasoningContentIsPreferredAndReasoningIsTheFallback() {
        val only = """{"messages":[{"role":"assistant","content":"x","reasoning":"fallback"},
                                    {"role":"assistant","content":"y","reasoning":"a","reasoning_content":"b"}]}"""
        val messages = json.decodeFromString(MessagesDto.serializer(), only).messages.map { it.toDomain() }
        assertEquals("fallback", messages[0].thinking)
        assertEquals("b", messages[1].thinking)
    }
}
