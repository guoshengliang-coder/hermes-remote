package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import com.hermes.client.ui.createHermesComposeRule
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.workspace.*
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.launch
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Checks the intermediate frame on re-entry, not only the eventual restored offset. */
@RunWith(RobolectricTestRunner::class)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
class WarmChatEntryPresentationTest {
    @get:Rule val compose = createHermesComposeRule()

    @Test fun completedAnswerDoesNotExposeItsReparseAndRestore() = exercise(false)
    @Test fun runningAnswerDoesNotExposeItsReparseAndRestore() = exercise(true)
    @Test fun completedAnswerInAppDarkWithLargeFontDoesNotExposeItsRestore() = exercise(false, true, 1.3f)
    @Test fun runningAnswerInAppDarkWithLargeFontDoesNotExposeItsRestore() = exercise(true, true, 1.3f)

    private fun exercise(running: Boolean, dark: Boolean = false, fontScale: Float = 1f) {
        val pending = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockkConstructor(MarkdownParser::class)
        every { anyConstructed<MarkdownParser>().buildMarkdownTreeFromString(any()) } answers {
            if (pending.get() && firstArg<String>().contains("已有")) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
            callOriginal()
        }
        val visible = mutableStateOf(true)
        val entry = mutableStateOf(0)
        val cache = SessionUiStateCache()
        val messages = listOf(
            ChatMessage("u", Role.USER, "原来的提问"),
            ChatMessage("a", Role.ASSISTANT, "## 已有回答\n\n" +
                (1..25).joinToString("\n\n") { "第 $it 段。已有的文字应该在原来的阅读位置显示，不能先缩短再撑开。" },
                isStreaming = running),
        )
        lateinit var list: LazyListState
        lateinit var viewport: ChatViewportController
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        var background = 0
        fun ink(): Int {
            val file = java.io.File.createTempFile("warm-chat-frame", ".png")
            try {
                compose.onRoot().captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                try {
                    var count = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        if (bitmap.getPixel(x, y) != background) count++
                    }
                    return count
                } finally { bitmap.recycle() }
            } finally {
                file.delete()
            }
        }
        try {
            compose.setContent {
                HermesTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        background = MaterialTheme.colorScheme.surface.toArgb()
                        Surface(Modifier.fillMaxSize()) {
                            if (visible.value) key(entry.value) { cache.SessionState("warm") {
                                scope = rememberCoroutineScope()
                                list = rememberConversationValue("list", LazyListState.Saver) { LazyListState() }
                                viewport = rememberConversationValue("viewport", ChatViewportController.Saver) { ChatViewportController() }
                                ChatMessageList(
                                    state = ChatUiState(messages = messages, historyLoaded = true, isGenerating = running),
                                    sessionId = "warm", listState = list, viewportController = viewport,
                                    isGenerating = running,
                                )
                            } }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已有回答").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { viewport.pendingParses == 0 }
            compose.waitForIdle()
            compose.runOnIdle { scope.launch { list.scrollToItem(1, 130) } }
            compose.waitForIdle()
            val index = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            assertTrue("original transcript must be visible", ink() > 0)
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            pending.set(true)
            compose.runOnIdle { entry.value++; visible.value = true }
            compose.waitForIdle()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.onAllNodesWithTag("chat-history-skeleton").assertCountEquals(0)
            assertEquals("warm entry exposed placeholder text or an unstable reading position", 0, ink())
            pending.set(false)
            release.countDown()
            compose.waitUntil(5_000) { viewport.pendingParses == 0 && !viewport.isRestoring() }
            compose.waitForIdle()
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            assertTrue("settled transcript must be visible", ink() > 0)
        } finally {
            pending.set(false)
            release.countDown()
            unmockkConstructor(MarkdownParser::class)
        }
    }
}
