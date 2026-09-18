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

    @Test fun thePlaceholderCarriesNoOutput() {
        val placeholder = sessionRunPlaceholder("s1")
        assertTrue(placeholder.isStreaming)
        assertEquals("", placeholder.text)
        assertEquals("", placeholder.thinking)
        assertTrue(placeholder.tools.isEmpty())
        // No run start supplied: nothing to count from, so the line stays the bare mark. This used
        // to be the only shape the placeholder had (HG-58/56 changed that, below).
        assertEquals(null, placeholder.timestamp)
    }

    // HG-56: the placeholder now carries the run's start, because a wait with no output and no
    // number is indistinguishable from a message that never sent — which is what the reporter
    // concluded after four and a half minutes, before stopping the run and sending it again.
    @Test fun thePlaceholderCountsFromTheRunsStartWhenItHasOne() {
        assertEquals(1_700_000_000_000L, sessionRunPlaceholder("s1", 1_700_000_000_000L).timestamp)
    }

    @Test fun aShortWaitSaysNothingAndALongOneSaysHowLong() {
        val start = 1_700_000_000_000L
        // Under the threshold: an ordinary turn answers inside this window, and a label that is
        // replaced a beat later is read once and then only flickers.
        assertEquals(null, runWaitElapsedLabel(start, start, zh = true))
        assertEquals(null, runWaitElapsedLabel(start, start + 4_999L, zh = true))
        // Past it, the number is the whole content.
        assertEquals("已运行 5秒", runWaitElapsedLabel(start, start + 5_000L, zh = true))
        assertEquals("Running for 5s", runWaitElapsedLabel(start, start + 5_000L, zh = false))
        // The wait HG-56 actually sat through: 4m28s between prompt.submit and the first delta.
        assertEquals("已运行 4分28秒", runWaitElapsedLabel(start, start + 268_000L, zh = true))
        assertEquals("Running for 4m28s", runWaitElapsedLabel(start, start + 268_000L, zh = false))
    }

    @Test fun noStartTimeMeansNoClaimAboutHowLong() {
        // A run restored from disk or adopted from upstream may have no start we can vouch for.
        // Inventing one would put a wrong number on screen, which is worse than no number.
        assertEquals(null, runWaitElapsedLabel(null, 1_700_000_000_000L, zh = true))
    }
}
