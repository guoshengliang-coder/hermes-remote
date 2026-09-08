package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import io.mockk.coEvery
import io.mockk.every

/**
 * `history()` fetches a transcript and parses it as two steps, so [TranscriptStore] can keep the
 * bytes rather than the mapped domain objects. These tests are about the mapping on the far side
 * of that seam, so they stub both halves with an opaque payload instead of hand-writing JSON.
 */
internal fun HermesRestApi.stubTranscript(
    sessionId: String,
    profile: String?,
    rows: List<MessageDto>,
) {
    val payload = payloadFor(sessionId, profile)
    coEvery { messagesRaw(sessionId, profile) } returns payload
    every { parseMessages(payload) } returns rows
}

internal fun payloadFor(sessionId: String, profile: String?): String = "payload:$sessionId:$profile"
