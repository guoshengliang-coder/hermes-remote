package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import com.hermes.client.ui.createHermesComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
class MessageProgressTest {
    @get:Rule val compose = createHermesComposeRule()
    private val thought = (1..8).joinToString("\n") { "第 $it 步：核实文件和运行进度，保留完整的思考内容。" }

    @Test fun reasoning_starts_folded_and_manual_expansion_survives_deltas() {
        val text = mutableStateOf("初始思考")
        compose.setContent { InChinese { HermesTheme { ThinkingCard("live", text.value) } } }
        compose.onNodeWithText("初始思考").assertDoesNotExist()
        compose.onNodeWithText("查看思考过程").performClick()
        compose.runOnIdle { text.value = "新的思考" }
        compose.onNodeWithText("新的思考").assertIsDisplayed()
        compose.onNodeWithText("收起思考过程").performClick()
        compose.runOnIdle { text.value = "后续思考" }
        compose.onNodeWithText("后续思考").assertDoesNotExist()
    }

    @Test fun manually_hiding_reasoning_is_not_overridden_by_more_output() {
        val text = mutableStateOf("初始思考")
        compose.setContent { InChinese { HermesTheme { ThinkingCard("live", text.value) } } }
        compose.onNodeWithText("查看思考过程").performClick()
        compose.onNodeWithText("收起思考过程").performClick()
        compose.runOnIdle { text.value = "后续思考" }
        compose.onNodeWithText("后续思考").assertDoesNotExist()
    }

    @Test fun historical_reasoning_starts_folded() {
        compose.setContent { InChinese { HermesTheme { ThinkingCard("history", "历史思考") } } }
        compose.onNodeWithText("历史思考").assertDoesNotExist()
        compose.onNodeWithText("查看思考过程").performClick()
        compose.onNodeWithText("历史思考").assertIsDisplayed()
    }

    @Test fun status_shows_all_lines_and_full_view_keeps_the_clicked_snapshot() {
        val text = mutableStateOf(thought.replace("\n", ""))
        val snapshot = text.value
        compose.mainClock.autoAdvance = false
        compose.setContent { InChinese { HermesTheme { RunningStatusLine(ChatMessage("live", Role.ASSISTANT, "", thinking = text.value, isStreaming = true)) } } }
        val node = compose.onNodeWithTag("running-status-text", useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 3)
        assertTrue(!layouts.single().hasVisualOverflow)
        compose.onNodeWithText(snapshot).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("运行状态").assertIsDisplayed()
        compose.runOnIdle { text.value = "新的状态" }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("running-status-details").assertTextEquals(snapshot)
        compose.onNodeWithText("关闭").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("新的状态").assertIsDisplayed()
    }

    @Test fun prose_hides_long_status_and_releases_its_height_while_still_streaming() {
        val message = mutableStateOf(ChatMessage("footer", Role.ASSISTANT, "", thinking = "独立思考内容", isStreaming = true,
            tools = listOf(com.hermes.client.domain.ToolCall("tool", "terminal", com.hermes.client.domain.ToolStatus.RUNNING,
                command = "检查所有来源文件和运行结果，再逐一核对详细信息。".repeat(8)))))
        compose.mainClock.autoAdvance = false
        compose.setContent { InChinese { HermesTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Column(Modifier.width(320.dp)) {
                    AssistantTurn(msg = message.value, canRegenerate = false, showActions = true,
                        onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                        isSpeaking = false, onReadAloud = {}, onStopReading = {},
                        onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {})
                }
            }
        } } }
        compose.onNodeWithText("独立思考内容").assertDoesNotExist()
        compose.onNodeWithText("查看思考过程").performClick()
        compose.mainClock.advanceTimeBy(32)
        val footer = compose.onNodeWithTag("turn-footer-footer")
        footer.assertHeightIsAtLeast(64.dp)
        val bounds = footer.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        compose.runOnIdle { message.value = message.value.copy(text = "最终正文开始显示") }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("running-status-text", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("正在生成").assertExists()
        val proseBounds = footer.getUnclippedBoundsInRoot()
        assertTrue(proseBounds.bottom - proseBounds.top < height)
        compose.runOnIdle { message.value = message.value.copy(isStreaming = false) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("独立思考内容").assertExists()
        val completedBounds = footer.getUnclippedBoundsInRoot()
        assertEquals(proseBounds.bottom - proseBounds.top, completedBounds.bottom - completedBounds.top)
    }

    @Test fun progress_light() = screenshot("progress-light", false, 1f)
    @Test fun progress_app_dark_on_light_system() = screenshot("progress-dark", true, 1f)
    @Test fun progress_large_font() = screenshot("progress-fs13", false, 1.3f)

    private fun screenshot(name: String, dark: Boolean, scale: Float) {
        compose.mainClock.autoAdvance = false
        compose.setContent { InChinese { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Surface { Column(Modifier.width(360.dp)) {
                    ThinkingCard("live", thought)
                    RunningStatusLine(ChatMessage("live", Role.ASSISTANT, "", thinking = thought.replace("\n", ""), isStreaming = true))
                } }
            }
        } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
}
