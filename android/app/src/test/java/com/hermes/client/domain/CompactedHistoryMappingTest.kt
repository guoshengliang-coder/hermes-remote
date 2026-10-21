package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactedHistoryMappingTest {
    @Test fun upstream_display_projection_wins_without_losing_legacy_content() {
        val json = Json { ignoreUnknownKeys = true }
        val projected = json.decodeFromString<MessageDto>(
            """{"id":90,"role":"user","content":"old context and machine summary","display_content":"real live ask"}""",
        ).toDomain()
        assertEquals("real live ask", projected.text)
        assertEquals("legacy", MessageDto(id = 3, role = "user", content = "legacy").toDomain().text)
        assertEquals("", json.decodeFromString<MessageDto>(
            """{"id":4,"role":"user","content":"machine","display_content":""}""",
        ).toDomain().text)
    }
}
