package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk shape of a refused send (HG-49). Mirrors [DraftRecordTest] deliberately: same store
 * pattern, same bounds, so a divergence between the two is a decision someone made rather than a
 * detail that drifted.
 */
class UnsentRecordTest {
    private fun record(
        token: String,
        text: String,
        at: Long,
        attachments: Int = 0,
        code: String = "HR-SESS-007",
    ) = UnsentRecord(
        token = token, messageId = "u-$token", text = text, code = code,
        attachments = attachments, updatedAt = at,
    )

    @Test fun roundTripsThroughJson() {
        val decoded = decodeUnsent(encodeUnsent(listOf(record("p/s1", "巨浪事业群呢", 10L, code = "HR-SESS-013"))))
        assertEquals(1, decoded.size)
        assertEquals("p/s1", decoded[0].token)
        assertEquals("u-p/s1", decoded[0].messageId)
        assertEquals("巨浪事业群呢", decoded[0].text)
        assertEquals("HR-SESS-013", decoded[0].code)
        assertTrue(decoded[0].retryable)
        assertEquals(10L, decoded[0].updatedAt)
    }

    @Test fun corruptOrAbsentPayloadDecodesToNothing() {
        assertEquals(emptyList<UnsentRecord>(), decodeUnsent(null))
        assertEquals(emptyList<UnsentRecord>(), decodeUnsent("not json"))
        assertEquals(emptyList<UnsentRecord>(), decodeUnsent("""[{"v":99,"token":"p/s","messageId":"u-1"}]"""))
        // Without a message id the bubble cannot be restored under the id a retry has to remove.
        assertEquals(emptyList<UnsentRecord>(), decodeUnsent("""[{"v":1,"token":"p/s","messageId":""}]"""))
    }

    /**
     * Unlike a draft, an empty body is a real send when it carried attachments — "nine images and no
     * caption" is a message, and a refused one still has to be reported on the row.
     */
    @Test fun blankTextSurvivesOnlyWhenItCarriedAttachments() {
        val withFiles = decodeUnsent(encodeUnsent(listOf(record("p/s1", "", 1L, attachments = 3))))
        assertEquals(1, withFiles.size)
        assertEquals(3, withFiles[0].attachments)

        val withNothing = decodeUnsent(encodeUnsent(listOf(record("p/s2", "  ", 1L))))
        assertEquals(emptyList<UnsentRecord>(), withNothing)
    }

    @Test fun keepsTheMostRecentlyTouchedRecords() {
        val many = (1..MAX_PERSISTED_UNSENT + 20).map { record("p/s$it", "t$it", it.toLong()) }
        val kept = decodeUnsent(encodeUnsent(many))
        assertEquals(MAX_PERSISTED_UNSENT, kept.size)
        assertEquals("p/s${MAX_PERSISTED_UNSENT + 20}", kept.first().token)
        assertTrue(kept.none { it.updatedAt <= 20L })
    }

    @Test fun overLongMessageIsTruncatedRatherThanDropped() {
        val kept = decodeUnsent(encodeUnsent(listOf(record("p/s1", "x".repeat(MAX_UNSENT_CHARS + 500), 1L))))
        assertEquals(1, kept.size)
        assertEquals(MAX_UNSENT_CHARS, kept[0].text.length)
    }

    @Test fun payloadBudgetStopsBeforeOverflowing() {
        val fat = (1..40).map { record("p/s$it", "x".repeat(MAX_UNSENT_CHARS), it.toLong()) }
        val encoded = encodeUnsent(fat)
        assertTrue(encoded.length <= MAX_UNSENT_PAYLOAD_CHARS)
        assertTrue(decodeUnsent(encoded).size < 40)
    }

    /** One outstanding refusal per conversation: sending again is what clears the previous one. */
    @Test fun writingReplacesTheSameConversationRatherThanAppending() {
        val once = putUnsent(emptyList(), record("p/s1", "一", 1L))
        val twice = putUnsent(once, record("p/s1", "二", 2L))
        assertEquals(1, twice.size)
        assertEquals("二", twice.single().text)
        assertEquals(2L, twice.single().updatedAt)
    }

    @Test fun clearingDropsOnlyThatConversation() {
        val start = listOf(record("p/s1", "一", 1L), record("p/s2", "二", 2L))
        assertEquals(listOf("p/s2"), removeUnsent(start, "p/s1").map { it.token })
        assertEquals(start, removeUnsent(start, "p/never-sent-anything"))
    }
}
