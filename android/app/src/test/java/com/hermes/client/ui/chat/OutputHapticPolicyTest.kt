package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.*
import org.junit.Test

class OutputHapticPolicyTest {
    @Test fun pacedOutputIsBoundedAndNeverQueuesPulses() {
        val policy = OutputHapticPolicy()
        assertFalse(policy.observe("run", "", true, 0))
        assertTrue(policy.observe("run", "a", true, 64))
        assertFalse(policy.observe("run", "ab", true, 128))
        // A network-sized burst is just one display event, not 2000 pulses.
        val burst = "ab" + "c".repeat(2000)
        assertTrue(policy.observe("run", burst, true, 192))
        assertFalse(policy.observe("run", burst, true, 3000))
    }

    @Test fun pauseOffscreenBackgroundToolWaitAndDisableNeverReplay() {
        // Each gate uses the same consume-without-feedback rule.
        run {
            val policy = OutputHapticPolicy()
            policy.observe("run", "a", true, 0)
            assertTrue(policy.observe("run", "ab", true, 100))
            assertFalse(policy.observe("run", "abc", false, 200))
            assertFalse(policy.observe("run", "abcdef", false, 300))
            assertFalse(policy.observe("run", "abcdef", true, 400))
            assertFalse(policy.observe("run", "abcdef", true, 500))
            assertTrue(policy.observe("run", "abcdefg", true, 600))
        }
    }

    @Test fun entryNewTurnsCorrectionsAndWhitespaceDoNotVibrate() {
        val policy = OutputHapticPolicy()
        assertFalse(policy.observe("existing", "already displayed answer", true, 0))
        assertFalse(policy.observe("existing", "already displayed answer\n", true, 100))
        assertFalse(policy.observe("existing", "corrected", true, 200))
        assertFalse(policy.observe("new", "new answer", true, 300))
        assertTrue(policy.observe("new", "new answer continues", true, 400))
        assertFalse(policy.observe(null, "new answer continues at completion", false, 500))
        assertFalse(policy.observe(null, "new answer continues at completion", false, 900))
    }

    @Test fun returningWithNewTextAlreadyPresentEstablishesBaseline() {
        val policy = OutputHapticPolicy()
        policy.observe("run", "a", false, 0)
        assertFalse(policy.observe("run", "abc", true, 200))
        assertTrue(policy.observe("run", "abcd", true, 300))
    }

    @Test fun lifecycleReturnDoesNotReplayWhenNoBackgroundFramesWereObserved() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
        registry = LifecycleRegistry.createUnsafe(owner)
        val policy = OutputHapticPolicy()
        registry.addObserver(policy)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        policy.observe("run", "a", true, 0)
        assertTrue(policy.observe("run", "ab", true, 100))
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // No calls to observe while the frame clock is stopped in the background.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse(policy.observe("run", "abcdef", true, 10000))
        assertTrue(policy.observe("run", "abcdefg", true, 10100))
        // Focus / tool / toggle transitions also reset even between sampling frames.
        policy.resetEligibility()
        assertFalse(policy.observe("run", "abcdefgh", true, 10200))
        assertTrue(policy.observe("run", "abcdefghi", true, 10300))
    }

    @Test fun onlyVisibleProseIsIncludedNotReasoningToolsOrPlaceholder() {
        val message = ChatMessage("a", Role.ASSISTANT, text = "正文\n\n*接收中*", thinking = "思考")
        val presentation = outputHapticPresentation(message, "接收中")
        assertEquals("正文", presentation.text)
        assertFalse(presentation.text.contains("思考"))
        assertEquals("a:markdown:0", presentation.tailKey)
        assertEquals("", outputHapticPresentation(message.copy(text = "*接收中*"), "接收中").text)
        assertEquals("", outputHapticPresentation(message.copy(isError = true), "接收中").text)
        assertEquals("print(1)", outputHapticPresentation(message.copy(text = "```py\nprint(1)\n```"), "接收中").text)
    }

    @Test fun partiallyVisibleAnswerMustNotCountAsVisibleNewOutput() {
        val viewport = ChatViewportController()
        viewport.updateViewport(Rect(0f, 100f, 400f, 600f))
        viewport.updateBlock("tail", Rect(0f, 0f, 400f, 600f))
        viewport.updateOutputTail("tail", 900f)
        assertFalse(viewport.isOutputTailVisible("tail"))
        viewport.updateOutputTail("tail", 550f)
        assertTrue(viewport.isOutputTailVisible("tail"))
        viewport.lockForOverlay()
        assertFalse(viewport.isOutputTailVisible("tail"))
        viewport.removeBlock("tail")
        assertFalse(viewport.isOutputTailVisible("tail"))
    }

    @Test fun lightConstantsMatchSupportedPlatformLevels() {
        assertEquals(4, outputHapticConstant(26))
        assertEquals(9, outputHapticConstant(27))
        assertEquals(9, outputHapticConstant(33))
        assertEquals(27, outputHapticConstant(34))
    }
}
