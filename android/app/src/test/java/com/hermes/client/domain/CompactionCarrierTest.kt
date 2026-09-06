package com.hermes.client.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val HEADER = "[PRIOR CONTEXT — for reference only; not a new message]"
private const val DELIM = "[END OF PRIOR CONTEXT — COMPACTION SUMMARY BELOW]"
private const val END =
    "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---"

class CompactionCarrierTest {
    /** The shape that reached a real device: header, nothing merged, then the summary. */
    @Test fun a_pure_handoff_renders_nothing() {
        val carrier = """
            $HEADER

            $DELIM

            [CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below.
            ## What happened
            - the agent did things
        """.trimIndent()
        assertNull(CompactionCarrier.project(carrier))
    }

    @Test fun a_bare_compaction_notice_renders_nothing() {
        assertNull(CompactionCarrier.project("[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns…"))
        assertNull(CompactionCarrier.project("[CONTEXT SUMMARY]: earlier turns were compacted"))
    }

    /** Real conversation merged in front of the summary must survive. */
    @Test fun the_prior_tail_merged_into_a_carrier_is_kept() {
        val carrier = "$HEADER\n昨天的口径按新版走。\n\n$DELIM\n[CONTEXT COMPACTION] …"
        assertEquals("昨天的口径按新版走。", CompactionCarrier.project(carrier))
    }

    /** Legacy form: the live question sits after the end marker. */
    @Test fun the_live_message_after_the_end_marker_is_kept() {
        val carrier = "[CONTEXT SUMMARY]: lots of summary\n$END\n昨天公司数据如何？"
        assertEquals("昨天公司数据如何？", CompactionCarrier.project(carrier))
    }

    @Test fun an_end_marker_with_nothing_after_it_renders_nothing() {
        assertNull(CompactionCarrier.project("[CONTEXT SUMMARY]: summary\n$END\n   "))
    }

    /** Anchored on purpose: someone quoting the marker is saying something real. */
    @Test fun a_person_quoting_the_marker_is_left_alone() {
        val human = "上游那个 $HEADER 标记要不要过滤掉？"
        assertEquals(human, CompactionCarrier.project(human))
    }

    @Test fun ordinary_messages_pass_through_untouched() {
        assertEquals("昨天公司数据如何？", CompactionCarrier.project("昨天公司数据如何？"))
        assertEquals("", CompactionCarrier.project(""))
    }

    @Test fun a_turn_with_nothing_left_is_not_renderable() {
        val empty = ChatMessage(id = "x", role = Role.USER, text = "")
        assert(!empty.isRenderable())
        assert(empty.copy(text = "hi").isRenderable())
        assert(empty.copy(thinking = "…").isRenderable())
    }
}
