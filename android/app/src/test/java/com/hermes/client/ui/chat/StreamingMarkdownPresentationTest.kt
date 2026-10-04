package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HG-195: a real async parse must not erase the previous frame or collapse the pinned turn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
class StreamingMarkdownPresentationTest {
    @get:Rule val compose = createComposeRule()

    private class ParseGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    @Test fun light_stream_keeps_prose_and_height_while_next_list_snapshot_parses() = exercise(false, 1f)

    @Test fun app_dark_on_light_system_keeps_prose_at_large_font() = exercise(true, 1.3f)

    private fun exercise(dark: Boolean, fontScale: Float) {
        val pending = AtomicReference<ParseGate?>(null)
        mockkConstructor(MarkdownParser::class)
        // Hold the actual renderer's next parse, rather than hoping a slow machine catches a
        // one-frame Loading state. The production composable and renderer remain under test.
        every { anyConstructed<MarkdownParser>().buildMarkdownTreeFromString(any()) } answers {
            if (firstArg<String>().startsWith("1. 已显示第一项")) {
                pending.get()?.let { gate ->
                    gate.entered.countDown()
                    check(gate.release.await(10, TimeUnit.SECONDS)) { "test did not release parser" }
                }
            }
            callOriginal()
        }
        val prefix = "| 项目 | 进度 |\n| --- | --- |\n| 示例 | 进行中 |\n\n## 验收建议\n\n"
        val first = "1. 已显示第一项\n2. 第二项正在输出"
        val message = mutableStateOf(ChatMessage(id = "live", role = Role.ASSISTANT, text = prefix + first, isStreaming = true))
        var height = 0
        lateinit var list: LazyListState
        try {
            compose.setContent {
                HermesTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        Surface {
                            list = rememberLazyListState()
                            LazyColumn(state = list, reverseLayout = true, modifier = Modifier.height(700.dp)) {
                                item(key = "live") {
                                    Column(Modifier.onSizeChanged { height = it.height }) {
                                        AssistantTurn(
                                            msg = message.value, canRegenerate = false, showActions = false,
                                            onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                                            isSpeaking = false, onReadAloud = {}, onStopReading = {},
                                            onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                                            smoothLiveResize = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("已显示第一项", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val before = height
            assertTrue(before > 0)
            val gate = ParseGate()
            pending.set(gate)
            compose.runOnIdle { message.value = message.value.copy(text = prefix + first + "\n3. 新增第三项") }
            compose.waitUntil(5_000) { gate.entered.count == 0L }
            compose.waitForIdle()
            compose.onNodeWithText("已显示第一项", substring = true).assertExists()
            assertEquals("pending parse collapsed the turn", before, height)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            gate.release.countDown()
            pending.set(null)
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("新增第三项", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { message.value = message.value.copy(isStreaming = false) }
            compose.waitForIdle()
            compose.onNodeWithText("已显示第一项", substring = true).assertExists()
            compose.onNodeWithText("新增第三项", substring = true).assertExists()
        } finally {
            pending.getAndSet(null)?.release?.countDown()
            unmockkConstructor(MarkdownParser::class)
        }
    }
}
