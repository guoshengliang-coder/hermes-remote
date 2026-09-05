package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/DESIGN.md §5.6, decision 2026-09-05: the running indicator belongs to the session's run.
 * A list row saying "思考中" over an empty chat (HG-8) is what happens without this rule.
 */
class SessionRunIndicatorTest {
    private val user = ChatMessage("u", Role.USER, "html我看不到，我远程访问你的")
    private val streaming = ChatMessage("a", Role.ASSISTANT, "", isStreaming = true)
    private val settled = ChatMessage("a", Role.ASSISTANT, "完成内容")

    @Test fun anActiveRunWithNoStreamingBubbleShowsTheSessionIndicator() {
        assertTrue(showsSessionRunIndicator(isGenerating = true, messages = listOf(user)))
        assertTrue(showsSessionRunIndicator(isGenerating = true, messages = listOf(user, settled)))
    }

    @Test fun aStreamingBubbleAlreadyCarriesTheIndicator() {
        assertFalse(showsSessionRunIndicator(isGenerating = true, messages = listOf(user, streaming)))
    }

    @Test fun anIdleSessionShowsNothing() {
        assertFalse(showsSessionRunIndicator(isGenerating = false, messages = listOf(user, settled)))
        assertFalse(showsSessionRunIndicator(isGenerating = false, messages = emptyList()))
    }

    @Test fun thePlaceholderHasNoOutputSoOnlyTheMarkRenders() {
        val placeholder = sessionRunPlaceholder("s1")
        assertTrue(placeholder.isStreaming)
        assertEquals("", placeholder.text)
        assertEquals("", placeholder.thinking)
        assertTrue(placeholder.tools.isEmpty())
        assertEquals(null, placeholder.timestamp)
    }
}
