package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRecordTest {
    private fun record(token: String, text: String, at: Long) =
        DraftRecord(token = token, text = text, updatedAt = at)

    @Test fun roundTripsThroughJson() {
        val decoded = decodeDrafts(encodeDrafts(listOf(record("p/s1", "半句话", 10L))))
        assertEquals(1, decoded.size)
        assertEquals("p/s1", decoded[0].token)
        assertEquals("半句话", decoded[0].text)
        assertEquals(10L, decoded[0].updatedAt)
    }

    @Test fun corruptOrAbsentPayloadDecodesToNothing() {
        assertEquals(emptyList<DraftRecord>(), decodeDrafts(null))
        assertEquals(emptyList<DraftRecord>(), decodeDrafts("not json"))
        assertEquals(emptyList<DraftRecord>(), decodeDrafts("""[{"v":99,"token":"p/s","text":"x"}]"""))
        // A record with no text is not a draft; it would mark a row for nothing.
        assertEquals(emptyList<DraftRecord>(), decodeDrafts("""[{"v":1,"token":"p/s","text":""}]"""))
    }

    @Test fun keepsTheMostRecentlyTouchedDrafts() {
        val many = (1..MAX_PERSISTED_DRAFTS + 20).map { record("p/s$it", "t$it", it.toLong()) }
        val kept = decodeDrafts(encodeDrafts(many))
        assertEquals(MAX_PERSISTED_DRAFTS, kept.size)
        // Newest first, and the oldest 20 are the ones gone.
        assertEquals("p/s${MAX_PERSISTED_DRAFTS + 20}", kept.first().token)
        assertTrue(kept.none { it.updatedAt <= 20L })
    }

    @Test fun overLongDraftIsTruncatedRatherThanDropped() {
        val kept = decodeDrafts(encodeDrafts(listOf(record("p/s1", "x".repeat(MAX_DRAFT_CHARS + 500), 1L))))
        assertEquals(1, kept.size)
        assertEquals(MAX_DRAFT_CHARS, kept[0].text.length)
    }

    @Test fun payloadBudgetStopsBeforeOverflowing() {
        val fat = (1..40).map { record("p/s$it", "x".repeat(MAX_DRAFT_CHARS), it.toLong()) }
        val encoded = encodeDrafts(fat)
        assertTrue(encoded.length <= MAX_DRAFT_PAYLOAD_CHARS)
        assertTrue(decodeDrafts(encoded).size < 40)
    }

    @Test fun blankTextRemovesTheRecordInsteadOfStoringEmpty() {
        val start = listOf(record("p/s1", "半句话", 1L), record("p/s2", "别的", 2L))
        val cleared = putDraft(start, "p/s1", "   ", now = 5L)
        assertEquals(listOf("p/s2"), cleared.map { it.token })
    }

    @Test fun writingReplacesTheSameTokenRatherThanAppending() {
        val once = putDraft(emptyList(), "p/s1", "一", now = 1L)
        val twice = putDraft(once, "p/s1", "二", now = 2L)
        assertEquals(1, twice.size)
        assertEquals("二", twice.single().text)
        assertEquals(2L, twice.single().updatedAt)
        assertNull(twice.firstOrNull { it.text == "一" })
    }
}
