package com.hermes.client.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.workspace.SessionUiStateCache
import com.hermes.client.ui.workspace.SessionState
import io.mockk.*
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Exercise the actual reveal executor with an empty pre-open frame and saved navigation state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
class StreamReentryTest {
    @get:Rule val compose = createComposeRule()
    private val question = ChatMessage("u", Role.USER, "提问")
    private val fullText = "已有正文。".repeat(600) + "回答末尾"

    @Test fun delayedRuntimeSnapshotDoesNotReplayExistingStream() = exercise(null)
    @Test fun returnToStreamSkipsTextGeneratedWhileAway() = exercise(true)
    @Test fun returnAfterCompletionDoesNotRestoreAnOldRevealCursor() = exercise(false)

    private fun exercise(stillRunningOnReturn: Boolean?) {
        val parsed = AtomicReference("")
        mockkConstructor(MarkdownParser::class)
        every { anyConstructed<MarkdownParser>().buildMarkdownTreeFromString(any()) } answers {
            val text = firstArg<String>()
            if (text.startsWith("已有正文")) parsed.set(text)
            callOriginal()
        }
        val state = mutableStateOf(ChatUiState.empty())
        val shown = mutableStateOf(true)
        val cache = SessionUiStateCache()
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                HermesTheme {
                    if (shown.value) cache.SessionState("reentry") {
                        ChatMessageList(state.value, sessionId = "reentry", isGenerating = state.value.isGenerating)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(128)
            compose.waitForIdle()
            if (stillRunningOnReturn != null) {
                // Establish a genuinely fresh run and leave while the visual cursor lags behind.
                compose.runOnIdle {
                    state.value = ChatUiState(messages = listOf(question,
                        ChatMessage("a", Role.ASSISTANT, "", isStreaming = true)), isGenerating = true)
                }
                compose.mainClock.advanceTimeBy(128)
                compose.runOnIdle {
                    state.value = state.value.copy(messages = listOf(question,
                        ChatMessage("a", Role.ASSISTANT, fullText, isStreaming = true)))
                }
                compose.mainClock.advanceTimeBy(256)
                // Publish the async reveal snapshot through a bounded number of Compose frames
                // before waiting on the parser's external signal with the clock frozen.
                repeat(4) {
                    compose.mainClock.advanceTimeBy(64)
                    Thread.sleep(30)
                    compose.waitForIdle()
                }
                compose.waitUntil(5_000) { parsed.get().isNotEmpty() }
                assertTrue("new output must still be paced", parsed.get().length < fullText.length)
                compose.runOnIdle { shown.value = false }
                compose.mainClock.advanceTimeByFrame() // actually dispose before mounting again
                compose.waitForIdle()
                parsed.set("")
                compose.runOnIdle { state.value = ChatUiState.empty(); shown.value = true }
                compose.mainClock.advanceTimeBy(128)
                compose.waitForIdle()
            }
            val running = stillRunningOnReturn != false
            compose.runOnIdle {
                state.value = ChatUiState(messages = listOf(question,
                    ChatMessage("a", Role.ASSISTANT, fullText, isStreaming = running)),
                    isGenerating = running, historyLoading = true, historyLoaded = true)
            }
            compose.mainClock.advanceTimeBy(256)
            compose.waitForIdle()
            // Give the Default dispatcher time to publish the remounted full snapshot, then
            // provide its next Compose frame. The bounded clock budget is much shorter than
            // replaying this answer (the reveal cap is 64 characters per 64ms tick).
            repeat(8) {
                compose.mainClock.advanceTimeBy(64)
                Thread.sleep(30)
                compose.waitForIdle()
            }
            // Freeze the display clock: waiting for real async parsing must not silently allow
            // dozens of reveal ticks to make a replay look like immediate presentation.
            try {
                compose.waitUntil(5_000) { parsed.get() == fullText }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                fail("entry failed to parse full text; parsed length=${parsed.get().length}; " +
                    compose.onRoot().printToString(maxDepth = 5).take(2000))
            }
            assertEquals(fullText, parsed.get())
            if (running) {
                val extended = fullText + "之后新增的文字。".repeat(600)
                compose.runOnIdle {
                    state.value = state.value.copy(messages = listOf(question,
                        ChatMessage("a", Role.ASSISTANT, extended, isStreaming = true)))
                }
                compose.mainClock.advanceTimeBy(256)
                repeat(4) {
                    compose.mainClock.advanceTimeBy(64)
                    Thread.sleep(30)
                    compose.waitForIdle()
                }
                compose.waitUntil(5_000) { parsed.get().length > fullText.length }
                assertTrue("only output after the entry baseline should be paced",
                    parsed.get().length < extended.length)
            }
        } finally {
            unmockkConstructor(MarkdownParser::class)
        }
    }
}
