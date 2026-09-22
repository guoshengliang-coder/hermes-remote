package com.hermes.client.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HG-104: how pages of a transcript are merged into the rows this device holds. */
class TranscriptWindowTest {
    private fun row(id: Long, text: String = "r$id") = buildJsonObject {
        put("id", id)
        put("role", if (id % 2L == 1L) "user" else "assistant")
        put("content", text)
    }

    private fun rows(range: LongRange) = range.map { row(it) }
    private fun List<JsonObject>.ids() = map { (it["id"] as JsonPrimitive).content.toLong() }
    private val limit = 4

    @Test fun a_tail_shorter_than_a_page_is_the_whole_conversation() {
        val known = TranscriptWindow(rows(1L..10L), reachedStart = false)

        val merged = TranscriptWindow.mergeTail(known, rows(8L..10L), limit)

        assertEquals(listOf(8L, 9L, 10L), merged.rows.ids())
        assertTrue(merged.reachedStart)
    }

    @Test fun a_full_tail_keeps_the_older_rows_and_replaces_the_overlap() {
        val known = TranscriptWindow(rows(1L..8L), reachedStart = true)
        // Row 7 was rewritten upstream; 9 and 10 are new.
        val tail = listOf(row(7, "rewritten"), row(8), row(9), row(10))

        val merged = TranscriptWindow.mergeTail(known, tail, limit)

        assertEquals((1L..10L).toList(), merged.rows.ids())
        assertEquals(JsonPrimitive("rewritten"), merged.rows[6]["content"])
        assertTrue("the start stays reached", merged.reachedStart)
    }

    @Test fun a_tail_with_a_gap_after_the_known_rows_drops_them() {
        // More than a page was added since: rows 5..(the tail) are unknown, so 1..4 cannot be
        // shown directly above the tail.
        val known = TranscriptWindow(rows(1L..4L), reachedStart = true)

        val merged = TranscriptWindow.mergeTail(known, rows(20L..23L), limit)

        assertEquals((20L..23L).toList(), merged.rows.ids())
        assertFalse("older rows exist again", merged.reachedStart)
    }

    @Test fun nothing_known_takes_the_tail_as_it_is() {
        val merged = TranscriptWindow.mergeTail(null, rows(7L..10L), limit)
        assertEquals((7L..10L).toList(), merged.rows.ids())
        assertFalse(merged.reachedStart)
    }

    @Test fun an_older_page_is_prepended() {
        val window = TranscriptWindow(rows(7L..10L), reachedStart = false)

        val merge = TranscriptWindow.mergeOlder(window, rows(3L..6L), limit)

        assertEquals((3L..10L).toList(), merge.window.rows.ids())
        assertEquals(4, merge.added)
        assertFalse(merge.window.reachedStart)
    }

    @Test fun an_overlapping_older_page_is_deduplicated_by_id() {
        // Two rows were added upstream after the window loaded, so offset=4 now lands two rows
        // too new: the page repeats 5 and 6.
        val window = TranscriptWindow(rows(5L..8L), reachedStart = false)

        val merge = TranscriptWindow.mergeOlder(window, rows(3L..6L), limit)

        assertEquals((3L..8L).toList(), merge.window.rows.ids())
        assertEquals(2, merge.added)
    }

    @Test fun a_short_older_page_reaches_the_start() {
        val window = TranscriptWindow(rows(3L..6L), reachedStart = false)

        val merge = TranscriptWindow.mergeOlder(window, rows(1L..2L), limit)

        assertEquals((1L..6L).toList(), merge.window.rows.ids())
        assertTrue(merge.window.reachedStart)
    }

    @Test fun a_full_page_that_adds_nothing_is_not_the_start() {
        // More than a page was added upstream since the window loaded: offset=4 now lands on rows
        // the window already holds. Only a short page may say "start reached".
        val window = TranscriptWindow(rows(7L..10L), reachedStart = false)

        val merge = TranscriptWindow.mergeOlder(window, rows(7L..10L), limit)

        assertEquals(0, merge.added)
        assertFalse(merge.window.reachedStart)
    }

    @Test fun a_stored_window_round_trips_with_its_start_marker() {
        val window = TranscriptWindow(rows(1L..3L), reachedStart = true)

        val restored = TranscriptWindow.fromStored(window.payload())!!

        assertEquals(listOf(1L, 2L, 3L), restored.rows.ids())
        assertTrue(restored.reachedStart)
    }

    @Test fun a_payload_from_before_paging_may_have_older_rows() {
        val legacy = """{"messages":[{"id":1,"role":"user","content":"hi"}]}"""
        val restored = TranscriptWindow.fromStored(legacy)!!
        assertEquals(1, restored.serverRows)
        assertFalse(restored.reachedStart)
    }

    @Test fun an_unreadable_payload_is_not_a_window() {
        assertNull(TranscriptWindow.fromStored("""{"messages":"not a list"}"""))
    }
}
