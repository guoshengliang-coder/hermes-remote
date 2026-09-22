package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * HG-104: a paged transcript and a history snapshot no longer start at the same row. Identity,
 * timestamps and the older rows on screen must be matched from where the two windows meet.
 */
class PagedTranscriptAlignmentTest {
    private fun row(serverId: Long?, id: String, role: Role, text: String = id, ts: Long? = null) =
        ChatMessage(id = id, role = role, text = text, timestamp = ts, serverId = serverId)

    @Test fun ids_are_aligned_from_where_the_windows_meet_when_history_reaches_further_back() {
        val current = listOf(
            row(5, "u-0", Role.USER),
            row(6, "a-1", Role.ASSISTANT),
        )
        val history = listOf(
            row(3, "h-0-3", Role.USER),
            row(4, "h-1-4", Role.ASSISTANT),
            row(5, "h-2-5", Role.USER),
            row(6, "h-3-6", Role.ASSISTANT),
        )

        val aligned = alignMessageIds(history, current)

        // Per-role ordinals from the top would have handed u-0 to row 3.
        assertEquals(listOf("h-0-3", "h-1-4", "u-0", "a-1"), aligned.map { it.id })
    }

    @Test fun ids_are_aligned_from_where_the_windows_meet_when_the_screen_reaches_further_back() {
        val current = listOf(
            row(1, "old-u", Role.USER),
            row(2, "old-a", Role.ASSISTANT),
            row(null, "u-2", Role.USER),
            row(null, "a-3", Role.ASSISTANT),
        )
        val history = listOf(
            row(40, "h-0-40", Role.USER),
            row(41, "h-1-41", Role.ASSISTANT),
        )

        assertEquals(listOf("u-2", "a-3"), alignMessageIds(history, current).map { it.id })
    }

    @Test fun lists_that_start_together_align_exactly_as_before() {
        val current = listOf(row(1, "u-0", Role.USER), row(2, "a-1", Role.ASSISTANT))
        val history = listOf(row(1, "h-0-1", Role.USER), row(2, "h-1-2", Role.ASSISTANT), row(3, "h-2-3", Role.USER))
        assertEquals(listOf("u-0", "a-1", "h-2-3"), alignMessageIds(history, current).map { it.id })
    }

    @Test fun timestamps_are_inherited_from_the_matching_row_not_the_same_position() {
        val current = listOf(
            row(1, "a", Role.USER, ts = 100L),
            row(2, "b", Role.ASSISTANT, ts = 200L),
            row(3, "c", Role.USER, ts = 300L),
        )
        val history = listOf(row(3, "h3", Role.USER))

        assertEquals(300L, inheritTimestamps(history, current).single().timestamp)
    }

    @Test fun a_tail_is_grafted_under_the_older_rows_it_overlaps() {
        val current = (1L..6L).map { row(it, "r$it", if (it % 2L == 1L) Role.USER else Role.ASSISTANT) }
        val tail = (5L..8L).map { row(it, "t$it", if (it % 2L == 1L) Role.USER else Role.ASSISTANT) }

        val grafted = graftOlderHead(tail, current)

        assertEquals((1L..8L).toList(), grafted.map { it.serverId })
        assertSame(current[0], grafted[0])
        assertEquals("t5", grafted[4].id)
    }

    @Test fun a_tail_that_does_not_reach_the_screen_is_not_grafted() {
        val current = (1L..4L).map { row(it, "r$it", Role.USER) }
        val tail = (50L..51L).map { row(it, "t$it", Role.USER) }
        assertEquals(tail, graftOlderHead(tail, current))
    }

    @Test fun rows_without_ids_are_never_grafted() {
        val current = listOf(row(1, "r1", Role.USER))
        val tail = listOf(row(null, "t", Role.USER))
        assertEquals(tail, graftOlderHead(tail, current))
    }

    @Test fun only_rows_older_than_the_screen_are_taken_from_an_older_page() {
        val current = listOf(row(5, "u-0", Role.USER), row(6, "a-1", Role.ASSISTANT))
        val merged = (1L..6L).map { row(it, "h-$it", if (it % 2L == 1L) Role.USER else Role.ASSISTANT) }

        assertEquals((1L..4L).toList(), olderRowsFor(merged, current).map { it.serverId })
        assertEquals(emptyList<ChatMessage>(), olderRowsFor(merged, listOf(row(null, "u-0", Role.USER))))
    }
}
