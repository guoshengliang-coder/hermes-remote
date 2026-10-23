package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.Json
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
}
