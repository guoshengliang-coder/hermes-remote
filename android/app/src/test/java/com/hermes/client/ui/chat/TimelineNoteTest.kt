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

    // Regression for HG-16. Hermes' compression scaffolding rides the wire as role=user, so a
    // transcript ended with a wall of "[Your active task list was preserved…] / [Skills pruned…]"
    // rendered as if the user had typed it.
    @Test fun `compression scaffolding alone becomes a quiet note`() {
        val scaffolding = COMPRESSION_SNAPSHOT_HEADER + "\n- [>] product. 下钻产品 (in_progress)\n\n" +
            "[Skills pruned during compression — reload before acting on these tasks]\n" +
            "…reload them first: skill_view(name='claude-code')."
        val note = timelineNoteFor(msg(scaffolding))
        assertEquals("上下文已压缩", note?.zh)
        assertTrue("the original must stay readable on tap", note?.expandable == true)
        assertFalse("a note is not a prompt turn", msg(scaffolding).isPromptTurn())
    }

    // The half that a naive prefix match gets catastrophically wrong: upstream appends the
    // snapshot to the trailing REAL user turn, so hiding the whole message deletes what the
    // person actually typed. Cut at the marker, keep the prompt, stay a bubble.
    @Test fun `scaffolding appended to a real prompt keeps the prompt`() {
        val text = "是不是广点通这个广告平台的收入没回来\n\n" + COMPRESSION_SNAPSHOT_HEADER + "\n- [>] product. 下钻 (in_progress)"
        assertNull("still a real user turn", timelineNoteFor(msg(text)))
        assertEquals("是不是广点通这个广告平台的收入没回来", withoutCompressionScaffolding(text))
        assertEquals("是不是广点通这个广告平台的收入没回来", msg(text).organizedForDisplay().text)
    }

    @Test fun `messages without the marker are untouched`() {
        val plain = "帮我看看昨天的数据"
        assertEquals(plain, withoutCompressionScaffolding(plain))
        assertEquals(plain, msg(plain).organizedForDisplay().text)
        assertNull(timelineNoteFor(msg(plain)))
    }

    // A scaffolding-only turn keeps its text: the note's expanded body is the only place the
    // original survives, so stripping it here would empty the card.
    @Test fun `a scaffolding-only turn keeps its text for the expanded body`() {
        val scaffolding = COMPRESSION_SNAPSHOT_HEADER + "\n- [>] 1. task (in_progress)"
        assertEquals(scaffolding, msg(scaffolding).organizedForDisplay().text)
    }

    // Regression for HG-24, taken from the production store (Hermes 0.21.0): the display name is
    // the platform's signed storage URL with every punctuation mark turned into an underscore.
    private val documentNote =
        "[The user sent a document: 'ddmedia_2FiwEcAqNqcGcDAQTRBCQF0QlIBrBuXXRkUqPkTQpk1Ewxhqo.jpg" +
            "_x-oss-access-key-id_LTAI5tHAVmgnLXFMYxv2BgEv_x-oss-expires_1787999232'. " +
            "It is saved at: http://wukong-file-im-zjk.oss-cn-zhangjiakou.aliyuncs.com/ddmedia.jpg. " +
            "Its text is not inlined here (it's a binary format such as PDF or DOCX). " +
            "To read it, extract the document's text yourself — for example with the terminal tool " +
            "or the ocr-and-documents skill — before answering, instead of asking the user to " +
            "paste the contents.]"

    // The reported turn had the caption FIRST and the note after it. Upstream only ever prepends,
    // but the production store holds both shapes, so matching the note as a prefix would have left
    // it on screen in exactly the case that was reported.
    @Test fun `an attachment note is cut whichever side of the caption it lands on`() {
        assertEquals("用一个连不上啊", withoutAttachmentScaffolding("用一个连不上啊\n\n$documentNote"))
        assertEquals("用一个连不上啊", withoutAttachmentScaffolding("$documentNote\n\n用一个连不上啊"))
        assertEquals("用一个连不上啊", msg("用一个连不上啊\n\n$documentNote").organizedForDisplay().text)
    }

    @Test fun `the whole attachment note family is cut`() {
        listOf(
            documentNote,
            "[The user sent a text document: 'notes.txt'. Its content has been included below. " +
                "The file is also saved at: /cache/doc_notes.txt]",
            "[The user sent an audio file attachment: 'memo.m4a'. It is saved at: /cache/memo.m4a. " +
                "Its content is not inlined here.]",
            "[The user sent a video attachment: 'clip.mp4'. It is saved at: /cache/clip.mp4. " +
                "Its content is not inlined here.]",
            "[The user sent a voice message: /cache/voice.ogg (duration: 0:12)]",
            "[User sent an image: https://example.com/a.png]",
            "[User sent a file: https://example.com/a.zip]",
        ).forEach { note ->
            assertEquals("看看这个", withoutAttachmentScaffolding("$note\n\n看看这个"))
            assertEquals("", withoutAttachmentScaffolding(note))
        }
    }

    // The vision notes describe what arrived and are the transcript's only account of the image,
    // so they are deliberately outside the family and must survive.
    @Test fun `an image description is not attachment scaffolding`() {
        val described = "[The user sent an image~ Here's what I can see:\n一张风景照]"
        assertEquals(described, withoutAttachmentScaffolding(described))

        val plain = "普通的一句话，没有附件。"
        assertEquals(plain, withoutAttachmentScaffolding(plain))
    }
}
