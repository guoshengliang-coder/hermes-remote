package com.hermes.client.ui.chat

import android.content.Context
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.data.diagnostics.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real list, paced reveal, async Markdown parsing and measured output-tail geometry.
 * Only the physical window/lifecycle and vibrator boundary are supplied by this test.
 * Accepted requests prove integration, never actual motor strength or comfort.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class OutputHapticRenderingTest {
    @get:Rule val compose = createComposeRule()
    @After fun resetDiagnostics() { DebugLog.setEnabled(false) }

    private class TickOnlyView : View(ApplicationProvider.getApplicationContext<Context>()) {
        val requests = mutableListOf<Int>()
        var accepted = 0
        override fun isShown() = true
        override fun hasWindowFocus() = true
        override fun performHapticFeedback(effect: Int): Boolean {
            requests += effect
            return (effect == 6 || effect == 26).also { if (it) accepted++ }
        }
    }

    private fun settle() {
        // The renderer works on Dispatchers.Default; give parsing real CPU time as well as frames.
        repeat(8) {
            compose.mainClock.advanceTimeBy(80)
            Thread.sleep(30)
            compose.waitForIdle()
        }
    }

    private fun advanceUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeBy(80)
            Thread.sleep(30)
            compose.waitForIdle()
        }
        assertTrue(message, condition())
    }

    @Test fun newlyRenderedProseRequestsAnOrdinaryTickAndDisabledOutputStaysSilent() {
        val view = TickOnlyView()
        val viewport = ChatViewportController()
        DebugLog.setEnabled(true)
        val window = object : WindowInfo { override val isWindowFocused = true }
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
        registry = LifecycleRegistry.createUnsafe(owner).apply { currentState = Lifecycle.State.RESUMED }
        val enabled = mutableStateOf(true)
        val answer = mutableStateOf(ChatMessage("answer", Role.ASSISTANT, text = "", isStreaming = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window, LocalLifecycleOwner provides owner) {
                    ChatMessageList(
                        state = ChatUiState(messages = listOf(ChatMessage("question", Role.USER, text = "测试"), answer.value), isGenerating = true),
                        sessionId = "haptic-integration", isGenerating = true, outputHapticsEnabled = enabled.value,
                        viewportController = viewport,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        settle()
        repeat(5) {
            compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "新增正文。") }
            settle()
        }
        val key = "answer:markdown:0"
        assertTrue("rendered prose must request a tick; requests=${view.requests}, mode=${viewport.mode}, " +
            "visible=${viewport.isOutputTailVisible(key)}, " +
            "painted=${viewport.parsedOutputContent(key)}; ${DebugLog.export()}", view.accepted > 0)
        val beforeDisable = view.requests.size
        compose.runOnIdle { enabled.value = false }
        settle()
        compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "关闭后的正文。") }
        settle()
        assertEquals("disabled output must not request any platform feedback", beforeDisable, view.requests.size)
    }

    @Test fun naturalCompletionKeepsFeedbackUntilTheVisibleTailFinishesThenStaysSilent() {
        DebugLog.setEnabled(true)
        val view = TickOnlyView()
        val viewport = ChatViewportController()
        val window = object : WindowInfo { override val isWindowFocused = true }
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
        registry = LifecycleRegistry.createUnsafe(owner).apply { currentState = Lifecycle.State.RESUMED }
        val generating = mutableStateOf(true)
        val answer = mutableStateOf(ChatMessage("answer", Role.ASSISTANT, text = "", isStreaming = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window, LocalLifecycleOwner provides owner) {
                    ChatMessageList(
                        state = ChatUiState(messages = listOf(ChatMessage("question", Role.USER, text = "测试"), answer.value), isGenerating = generating.value),
                        sessionId = "haptic-completion", isGenerating = generating.value, outputHapticsEnabled = true,
                        viewportController = viewport, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        settle()
        repeat(3) {
            compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "已有正文。") }
            advanceUntil("the live baseline must actually paint") {
                viewport.parsedOutputContent("answer:markdown:0") == answer.value.text
            }
            settle()
        }
        assertTrue("live output must establish feedback; ${DebugLog.export()}", view.accepted > 0)
        // A final network burst must drain through the real typewriter AND asynchronous parser.
        compose.runOnIdle {
            answer.value = answer.value.copy(text = answer.value.text + "最后一段正文。".repeat(200), isStreaming = false)
            generating.value = false
        }
        val beforeCompletion = view.accepted
        advanceUntil("network completion must not stop visible-tail feedback") { view.accepted > beforeCompletion }
        assertTrue("visible prose must still be draining", viewport.parsedOutputContent("answer:markdown:0").orEmpty().length < answer.value.text.length)
        advanceUntil("all final prose must paint, including parser-only updates") {
            viewport.parsedOutputContent("answer:markdown:0") == answer.value.text
        }
        settle()
        assertEquals(answer.value.text, viewport.parsedOutputContent("answer:markdown:0"))
        compose.onAllNodesWithText(answer.value.text, useUnmergedTree = true).assertCountEquals(1)
        val afterPaint = view.accepted
        settle()
        assertEquals("no queued pulses after the final text paints", afterPaint, view.accepted)
        compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "历史校正") }
        settle()
        assertEquals("completed history changes must stay silent", afterPaint, view.accepted)
    }


    @Test fun interruptedTailIsSilentAndANewRunCanProduceFeedback() {
        val view = TickOnlyView()
        val viewport = ChatViewportController()
        val window = object : WindowInfo { override val isWindowFocused = true }
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
        registry = LifecycleRegistry.createUnsafe(owner).apply { currentState = Lifecycle.State.RESUMED }
        val generating = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val answer = mutableStateOf(ChatMessage("answer", Role.ASSISTANT, text = "", isStreaming = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window, LocalLifecycleOwner provides owner) {
                    ChatMessageList(
                        state = ChatUiState(messages = listOf(ChatMessage("question", Role.USER, text = "测试"), answer.value), isGenerating = generating.value),
                        sessionId = "haptic-interrupted", isGenerating = generating.value, outputHapticsEnabled = enabled.value,
                        viewportController = viewport, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        settle()
        repeat(3) {
            compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "已有正文。") }
            advanceUntil("the live baseline must actually paint") {
                viewport.parsedOutputContent("answer:markdown:0") == answer.value.text
            }
            settle()
        }
        assertTrue(view.accepted > 0)
        val beforeStop = view.accepted
        // The UI's local Stop gate suppresses output before even a slow interrupt RPC returns.
        compose.runOnIdle {
            enabled.value = false
            answer.value = answer.value.copy(text = answer.value.text + "停止后的尾段。".repeat(50))
        }
        settle()
        assertEquals(beforeStop, view.accepted)
        // Remote interruption must also suppress the visual tail independently of that UI gate.
        compose.runOnIdle {
            enabled.value = true
            generating.value = false
            answer.value = answer.value.copy(interrupted = true, isStreaming = false)
        }
        repeat(4) { settle() }
        assertEquals(beforeStop, view.accepted)
        compose.runOnIdle {
            generating.value = true
            answer.value = ChatMessage("next-answer", Role.ASSISTANT, "", isStreaming = true)
        }
        settle()
        repeat(3) {
            compose.runOnIdle { answer.value = answer.value.copy(text = answer.value.text + "新一轮正文。") }
            advanceUntil("each new-run update must paint before the next delta") {
                viewport.parsedOutputContent("next-answer:markdown:0") == answer.value.text
            }
            settle()
        }
        assertTrue("a new run must recover after interruption", view.accepted > beforeStop)
    }

}
