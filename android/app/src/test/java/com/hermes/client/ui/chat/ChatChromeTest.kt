package com.hermes.client.ui.chat

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
}
