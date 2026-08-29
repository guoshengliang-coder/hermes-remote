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
}
