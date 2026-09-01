package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ImageTransferState
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatChromeTest {
    @Test fun blankOrUntitledSession_usesNewChatLabel() {
        assertEquals("新会话", displaySessionTitle(null))
        assertEquals("新会话", displaySessionTitle("  "))
        assertEquals("新会话", displaySessionTitle("Untitled"))
        assertEquals("新会话", displaySessionTitle("New chat"))
    }

    @Test fun realSessionTitle_isPreserved() {
        assertEquals("修复登录问题", displaySessionTitle("  修复登录问题  "))
    }

    @Test fun existingSession_neverUsesNewChatFallback() {
        assertEquals("会话", displaySessionTitle(null, fallback = "会话"))
        assertEquals("会话", displaySessionTitle("Untitled", fallback = "会话"))
    }

    @Test fun streamRevision_tracksReasoningTextAndTools() {
        val base = ChatMessage("a1", Role.ASSISTANT, text = "答", thinking = "想")
        val withReasoning = base.copy(thinking = "想更多")
        val withTool = withReasoning.copy(
            tools = listOf(ToolCall("t1", "浏览器", ToolStatus.RUNNING, output = "结果")),
        )

        assertEquals(true, withReasoning.streamContentRevision() > base.streamContentRevision())
        assertEquals(true, withTool.streamContentRevision() > withReasoning.streamContentRevision())
    }

    @Test fun historyLayoutRevision_tracksEarlierTurnReplacement() {
        val original = listOf(
            ChatMessage("u1", Role.USER, "旧问题"),
            ChatMessage("a1", Role.ASSISTANT, "相同结尾"),
        )
        val refreshed = original.toMutableList().apply {
            this[0] = this[0].copy(text = "新问题")
        }

        assertEquals(false, original.conversationLayoutRevision() == refreshed.conversationLayoutRevision())
    }

    @Test fun composerModelLabel_isCompact() {
        assertEquals("Auto", compactModelLabel(null))
        assertEquals("claude-sonnet-4", compactModelLabel("anthropic/claude-sonnet-4"))
        assertEquals(24, compactModelLabel("provider/a-very-long-model-name-for-mobile").length)
    }

    @Test fun longSessionTitles_useSmallerHeaderType() {
        assertEquals(24, adaptiveSessionTitleSize("短会话"))
        assertEquals(20, adaptiveSessionTitleSize("GPT对应DeepSeek"))
        assertEquals(18, adaptiveSessionTitleSize("这是一个长度明显更长的会话标题"))
        assertEquals(16, adaptiveSessionTitleSize("这是一个非常非常长并且需要进一步缩小字号的完整会话标题"))
    }

    @Test fun adjacentAssistantRecords_areOneConversationTurn() {
        val messages = listOf(
            ChatMessage("u1", Role.USER, "查一下"),
            ChatMessage("a1", Role.ASSISTANT, "我先检查服务。"),
            ChatMessage(
                "a2",
                Role.ASSISTANT,
                "服务正常，再检查客户端。",
                tools = listOf(ToolCall("t1", "服务检查", ToolStatus.DONE)),
            ),
            ChatMessage("a3", Role.ASSISTANT, "结论：需要更新客户端。"),
            ChatMessage("u2", Role.USER, "继续"),
            ChatMessage("a4", Role.ASSISTANT, "已经更新", isStreaming = true),
        )

        val turns = messages.organizedConversationTurns()

        assertEquals(4, turns.size)
        // Merged turns keep the EARLIEST record's id: later records fold into the turn, so its
        // identity (and every id-derived list key) must not move as they arrive.
        assertEquals("a1", turns[1].id)
        assertEquals(
            "我先检查服务。\n\n服务正常，再检查客户端。\n\n结论：需要更新客户端。",
            turns[1].text,
        )
        assertEquals(listOf("t1"), turns[1].tools.map { it.id })
        assertEquals("u2", turns[2].id)
        assertEquals("a4", turns[3].id)
        assertEquals(true, turns[3].isStreaming)
    }

    @Test fun renderKeys_surviveLiveToHistoryReconciliation() {
        val live = listOf(
            ChatMessage("local-user-id", Role.USER, "同一个问题"),
            ChatMessage("local-assistant-id", Role.ASSISTANT, "第一段\n\n第二段"),
        ).organizedConversationTurns()
        val persisted = listOf(
            ChatMessage("server-user-id", Role.USER, "同一个问题"),
            ChatMessage("duplicated-model-id", Role.ASSISTANT, "第一段"),
            ChatMessage("duplicated-model-id", Role.ASSISTANT, "第二段"),
        ).organizedConversationTurns()

        // Key stability across the REST swap is now provided at the DATA layer: alignMessageIds
        // reuses the live ids, so id-derived render keys match exactly.
        val aligned = alignMessageIds(persisted, live)
        assertEquals(live.conversationRenderKeys(), aligned.conversationRenderKeys())
    }

    @Test fun renderKeys_surviveImageUploadAndHydration() {
        val uploading = listOf(
            ChatMessage(
                "local-user",
                Role.USER,
                "",
                images = listOf(ChatImage("att-local", "image/png", state = ImageTransferState.UPLOADING)),
            ),
        )
        val hydrated = listOf(
            ChatMessage(
                "server-user",
                Role.USER,
                "",
                images = listOf(
                    ChatImage(
                        "remote-different",
                        "image/png",
                        localPath = "/cache/image.png",
                        remotePath = "/server/image.png",
                    ),
                ),
            ),
        )

        val aligned = alignMessageIds(hydrated, uploading)
        assertEquals(uploading.conversationRenderKeys(), aligned.conversationRenderKeys())
    }

    // ---- identity alignment --------------------------------------------------

    @Test fun alignMessageIds_reusesLiveIdsPerRoleOrdinal() {
        val current = listOf(
            ChatMessage("u-0", Role.USER, "one"),
            ChatMessage("a-1-x", Role.ASSISTANT, "answer one"),
            ChatMessage("u-2", Role.USER, "two"),
            ChatMessage("a-3-y", Role.ASSISTANT, "answer two"),
        )
        val history = listOf(
            ChatMessage("h-0-10", Role.USER, "one"),
            ChatMessage("h-1-11", Role.ASSISTANT, "answer one (normalized)"),
            ChatMessage("h-2-12", Role.USER, "two"),
            ChatMessage("h-3-13", Role.ASSISTANT, "answer two"),
            // genuinely new tail beyond local knowledge keeps its own id
            ChatMessage("h-4-14", Role.USER, "three"),
        )
        val aligned = alignMessageIds(history, current)
        assertEquals(listOf("u-0", "a-1-x", "u-2", "a-3-y", "h-4-14"), aligned.map { it.id })
        // ids must stay unique for LazyColumn keys
        assertEquals(aligned.size, aligned.map { it.id }.toSet().size)
    }

    @Test fun alignMessageIds_toleratesRoleCountMismatch() {
        val current = listOf(ChatMessage("u-0", Role.USER, "hi"))
        val history = listOf(
            ChatMessage("h-0-1", Role.USER, "hi"),
            ChatMessage("h-1-2", Role.ASSISTANT, "hello"),
            ChatMessage("h-2-3", Role.SYSTEM, "note"),
        )
        val aligned = alignMessageIds(history, current)
        assertEquals(listOf("u-0", "h-1-2", "h-2-3"), aligned.map { it.id })
    }

    @Test fun mergedTurnKeepsEarliestIdentity() {
        val first = ChatMessage("a-1-x", Role.ASSISTANT, "part one")
        val second = ChatMessage("a-2-y", Role.ASSISTANT, "part two")
        assertEquals("a-1-x", mergeAssistantTurns(first, second).id)
    }
}
