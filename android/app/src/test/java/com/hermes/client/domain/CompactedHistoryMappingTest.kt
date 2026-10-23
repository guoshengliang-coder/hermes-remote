package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.hermes.client.ui.chat.conversationArtifacts
import com.hermes.client.ui.chat.organizedForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactedHistoryMappingTest {
    @Test fun upstream_display_projection_wins_without_losing_legacy_content() {
        val json = Json { ignoreUnknownKeys = true }
        val projected = json.decodeFromString<MessageDto>(
            """{"id":90,"role":"user","content":"old context and machine summary","display_content":"real live ask","hr_display":{"schema":1,"kind":null,"body":"legacy GO projection"}}""",
        ).toDomain()
        assertEquals("real live ask", projected.text)
        assertEquals("legacy", MessageDto(id = 3, role = "user", content = "legacy").toDomain().text)
        assertEquals("", json.decodeFromString<MessageDto>(
            """{"id":4,"role":"user","content":"machine","display_content":"","hr_display":{"schema":1,"kind":null,"body":"legacy GO projection"}}""",
        ).toDomain().text)
        assertEquals("GO fallback", json.decodeFromString<MessageDto>(
            """{"id":5,"role":"user","content":"raw","hr_display":{"schema":1,"kind":null,"body":"GO fallback"}}""",
        ).toDomain().text)
    }
    @Test fun delivered_files_use_the_same_canonical_or_legacy_projection_as_the_visible_message() {
        val json = Json { ignoreUnknownKeys = true }
        val body = "<untrusted_tool_result source=\"run\">\nMEDIA:/tmp/process.pdf\n</untrusted_tool_result>\nMEDIA:/tmp/result.md"
        for (canonical in listOf(false, true)) {
            val row = buildJsonObject {
                put("id", 91)
                put("role", "assistant")
                put("content", "MEDIA:/tmp/process.pdf")
                if (canonical) put("display_content", body)
                put("hr_display", buildJsonObject {
                    put("schema", 1)
                    put("body", if (canonical) "MEDIA:/tmp/stale.pdf" else body)
                })
            }
            val message = json.decodeFromString<MessageDto>(row.toString()).toDomain().organizedForDisplay()
            assertEquals(listOf("/tmp/result.md"), conversationArtifacts(listOf(message)).map { it.reference })
        }
    }

}
