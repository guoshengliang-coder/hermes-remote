package com.hermes.client.ui.chat

import com.hermes.client.data.network.MessageDto
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineNoteTest {
    private fun msg(
        text: String = "",
        role: Role = Role.USER,
        kind: String? = null,
        tasks: Int? = null,
        failed: Int? = null,
    ) = ChatMessage(
        id = "m", role = role, text = text,
        displayKind = kind, displayTaskCount = tasks, displayFailedCount = failed,
    )

    @Test fun delegationMarkerCountsTasks() {
        val note = timelineNoteFor(msg(kind = "async_delegation_complete", tasks = 2))!!
        assertEquals("2 个后台子任务已完成", note.zh)
        assertEquals("2 background tasks finished", note.en)
        assertTrue(note.expandable)
    }

    @Test fun delegationMarkerReportsFailures() {
        val note = timelineNoteFor(msg(kind = "async_delegation_complete", tasks = 3, failed = 1))!!
        assertEquals("3 个后台子任务已完成，1 个失败", note.zh)
    }

    @Test fun modelSwitchExtractsModelName() {
        val note = timelineNoteFor(
            msg(
                text = "[System: The active model for this chat has changed to gpt-5.6-sol via provider openai-codex.]",
                kind = "model_switch",
            ),
        )!!
        assertEquals("已切换模型 · gpt-5.6-sol", note.zh)
        assertFalse(note.expandable)
    }

    @Test fun hiddenKindSuppressesRendering() {
        assertTrue(isHiddenTimelineMessage(msg(kind = "hidden")))
        assertFalse(isHiddenTimelineMessage(msg(text = "普通消息")))
    }

    @Test fun unknownKindFallsBackToGenericNote() {
        val note = timelineNoteFor(msg(kind = "future_marker_v9"))!!
        assertEquals("系统备注", note.zh)
        assertTrue(note.expandable)
    }

    @Test fun prefixFallbackMatchesWholeTextStartOnly() {
        assertEquals(
            "后台子任务已完成",
            timelineNoteFor(msg(text = "[ASYNC DELEGATION BATCH COMPLETE — d1]\n…"))!!.zh,
        )
        assertEquals(
            "后台进程通报",
            timelineNoteFor(msg(text = "[IMPORTANT: Background process p1 exited]"))!!.zh,
        )
        // Quoting the marker mid-message must not reclassify a real user turn.
        assertNull(timelineNoteFor(msg(text = "日志里出现了 [ASYNC DELEGATION BATCH COMPLETE 字样")))
        // Assistant turns never fall back on text prefixes.
        assertNull(timelineNoteFor(msg(text = "[ASYNC DELEGATION BATCH COMPLETE]", role = Role.ASSISTANT)))
    }

    @Test fun ordinaryTurnsAreNotNotes() {
        assertNull(timelineNoteFor(msg(text = "帮我部署一下")))
    }

    @Test fun dtoMapsDisplayFields() {
        val dto = Json.decodeFromString<MessageDto>(
            """{"id":7,"role":"user","content":"[ASYNC…]","display_kind":"async_delegation_complete",
                "display_metadata":{"task_count":2,"failed_count":0,"delegation_id":"d"}}""",
        )
        val domain = dto.toDomain()
        assertEquals("async_delegation_complete", domain.displayKind)
        assertEquals(2, domain.displayTaskCount)
        assertEquals(0, domain.displayFailedCount)
    }

    @Test fun dtoWithoutDisplayFieldsStaysPlain() {
        val dto = Json.decodeFromString<MessageDto>("""{"id":8,"role":"user","content":"你好"}""")
        val domain = dto.toDomain()
        assertNull(domain.displayKind)
        assertNull(timelineNoteFor(domain))
    }
}
