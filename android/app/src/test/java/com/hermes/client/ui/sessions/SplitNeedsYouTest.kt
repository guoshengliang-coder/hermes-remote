package com.hermes.client.ui.sessions

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitNeedsYouTest {
    private fun session(id: String, lastActive: Long? = null) = Session(
        id = id, title = id, model = null, provider = null,
        messageCount = 1, profile = "personal", lastActive = lastActive,
    )

    // Sessions blocked on the user (WAITING_*) jump the whole list order; running/idle stay in
    // the normal flow. Within the needs-you group, newest first.
    @Test fun waiting_sessions_split_out_and_sort_by_recency() {
        val phases = mapOf(
            "a" to SessionRunPhase.WAITING_APPROVAL,
            "b" to SessionRunPhase.STREAMING,
            "c" to SessionRunPhase.WAITING_CLARIFICATION,
            "d" to null,
        )
        val (needs, rest) = splitNeedsYou(
            listOf(session("a", 1L), session("b", 4L), session("c", 3L), session("d", 2L)),
        ) { s -> phases[s.id] }
        assertEquals(listOf("c", "a"), needs.map { it.id })
        assertEquals(listOf("b", "d"), rest.map { it.id })
    }

    @Test fun waiting_attention_counts_as_needs_you() {
        val (needs, _) = splitNeedsYou(listOf(session("x"))) { SessionRunPhase.WAITING_ATTENTION }
        assertEquals(listOf("x"), needs.map { it.id })
    }
}
