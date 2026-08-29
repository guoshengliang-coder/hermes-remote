package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
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
        assertEquals("a3", turns[1].id)
        assertEquals(
            "我先检查服务。\n\n服务正常，再检查客户端。\n\n结论：需要更新客户端。",
            turns[1].text,
        )
        assertEquals(listOf("t1"), turns[1].tools.map { it.id })
        assertEquals("u2", turns[2].id)
        assertEquals("a4", turns[3].id)
        assertEquals(true, turns[3].isStreaming)
    }
}
