package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private val question = "最近三个月的经营数据如何？"
    private val modelNote = "[System: The active model for this chat has changed to glm-5.4 via provider zai. " +
        "From this point forward, use this runtime metadata when answering questions about what model/provider is active.]"

    private fun replay(id: Int, previous: String, projected: Boolean): MessageDto {
        val suffix = "${CompactionCarrier.REPLAY_HEADER}\n$modelNote\n\n$question"
        return MessageDto(
            id = id, role = "user", content = "[CONTEXT SUMMARY]: summary\n$END\n$suffix",
            displayContent = suffix.takeIf { projected }, messageUid = "replay-$id",
            absorbedMessageUids = if (projected) JsonPrimitive("[\"$previous\"]")
                else JsonArray(listOf(JsonPrimitive(previous))),
        )
    }

    private fun history(vararg rows: MessageDto): List<String> =
        CompactionCarrier.withoutReplayCopies(rows.map { it to it.toDomain() }).map { it.text }

    @Test fun merged_replays_leave_real_duplicate_submissions_and_the_answer() {
        val original = MessageDto(id = 1, role = "user", content = question, messageUid = "original")
        val second = original.copy(id = 4, messageUid = "second")
        val answer = MessageDto(id = 5, role = "assistant", content = "分析结果")
        assertEquals(listOf(question, question, "分析结果"),
            history(original, replay(2, "original", true), replay(3, "replay-2", false), second, answer))
    }

    @Test fun a_window_without_the_original_recovers_one_clean_question() {
        assertEquals(listOf(question), history(replay(2, "original", true), replay(3, "replay-2", false)))
    }

    @Test fun a_flagged_standalone_replay_can_have_an_unrelated_new_uid() {
        val original = MessageDto(id = 1, role = "user", content = question, messageUid = "original")
        val standalone = MessageDto(
            id = 2, role = "user", content = CompactionCarrier.REPLAY_HEADER + "\n" + question,
            messageUid = "new-uid",
            displayMetadata = buildJsonObject { put("compaction_inflight_task_restatement", true) },
        )
        assertEquals(listOf(question), history(original, standalone))
        assertEquals(listOf(question), history(standalone))
    }

    @Test fun a_user_pasting_the_header_with_a_message_identity_keeps_it() {
        val literal = "${CompactionCarrier.REPLAY_HEADER}\n$question"
        assertEquals(listOf(literal), history(MessageDto(role = "user", content = literal, messageUid = "human")))
    }

    @Test fun a_turn_with_nothing_left_is_not_renderable() {
        val empty = ChatMessage(id = "x", role = Role.USER, text = "")
        assert(!empty.isRenderable())
        assert(empty.copy(text = "hi").isRenderable())
        assert(empty.copy(thinking = "…").isRenderable())
    }
}
