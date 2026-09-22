package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import io.mockk.coEvery
import io.mockk.every

/**
 * `history()` fetches a transcript and parses it as two steps, so [TranscriptStore] can keep the
 * bytes rather than the mapped domain objects. These tests are about the mapping on the far side
 * of that seam, so they stub the parse with the rows directly. Since HG-104 the repository reads
 * the row list itself (to merge pages by id), so the payload is real JSON with one row per DTO;
 * only its ids matter, and they are taken from the rows.
 */
internal fun HermesRestApi.stubTranscript(
    sessionId: String,
    profile: String?,
    rows: List<MessageDto>,
) {
    val payload = payloadFor(rows)
    coEvery { messagesRaw(sessionId, profile, any(), any(), any(), any()) } returns payload
    every { parseMessages(any()) } returns rows
}

/** A REST body carrying one `{"id": …}` object per row. */
internal fun payloadFor(rows: List<MessageDto>): String =
    rows.withIndex().joinToString(",", prefix = """{"messages":[""", postfix = "]}") { (index, row) ->
        """{"id":${row.id ?: index + 1}}"""
    }
