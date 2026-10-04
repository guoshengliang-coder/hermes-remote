package com.hermes.client.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.chat.ChatMessageList
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.chat.ChatViewportController
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HG-195 device complement to the blocked-parser JVM regression. Uses platform instrumentation:
 * the pinned Espresso input strategy reflects InputManager.getInstance, absent on newer images.
 */
@RunWith(AndroidJUnit4::class)
class StreamingChatPresentationTest {
    @Test fun light_stream_follows_then_leaves_manual_reader_in_place() = exercise(false)
    @Test fun dark_stream_follows_then_leaves_manual_reader_in_place() = exercise(true)

    private fun exercise(dark: Boolean) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val viewport = ChatViewportController()
        val table = "| 项目 | 状态 |\n| --- | --- |\n" +
            (1..14).joinToString("\n") { "| 项目 $it | 进行中，等待验收 |" }
        val prefix = "$table\n\n## 验收建议\n\n1. 统一验收口径，核对结果。\n2. 复核新增用户与成本。\n3. "
        val tail = "确认资料完整，依次补充上线日期、验收负责人和最终结论。"
        val state = mutableStateOf(ChatUiState(
            messages = listOf(
                ChatMessage(id = "u", role = Role.USER, text = "查看工作室目标达成情况"),
                ChatMessage(id = "a", role = Role.ASSISTANT, text = prefix, isStreaming = true),
            ), isGenerating = true,
        ))
        lateinit var list: LazyListState
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    HermesTheme(darkTheme = dark) {
                        Surface {
                            list = rememberLazyListState()
                            ChatMessageList(
                                state = state.value, sessionId = "hg195", listState = list,
                                isGenerating = state.value.isGenerating, viewportController = viewport,
                            )
                        }
                    }
                }
            }
            fun awaitOnUi(description: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var satisfied = false
                while (SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { satisfied = condition() }
                    if (satisfied) return
                    SystemClock.sleep(16)
                }
                assertTrue(description, satisfied)
            }
            awaitOnUi("initial prose parsed") { viewport.parsedOutputContent("a:markdown:2")?.contains("复核新增用户与成本") == true }
            for (end in 1..tail.length) {
                scenario.onActivity {
                    state.value = state.value.copy(messages = state.value.messages.map {
                        if (it.id == "a") it.copy(text = prefix + tail.take(end)) else it
                    })
                }
                SystemClock.sleep(80)
            }
            awaitOnUi("final streamed prose parsed") { viewport.parsedOutputContent("a:markdown:2")?.contains(tail) == true }
            scenario.onActivity {
                assertEquals(0, list.firstVisibleItemIndex)
                assertEquals(0, list.firstVisibleItemScrollOffset)
            }
            // Inject a real downward drag through Android, without Espresso's removed hidden API.
            var width = 0
            var height = 0
            scenario.onActivity { width = it.window.decorView.width; height = it.window.decorView.height }
            val start = SystemClock.uptimeMillis()
            for (step in 0..20) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, width * 0.5f, height * (0.25f + 0.5f * step / 20), 0)
                try { instrument.sendPointerSync(event) } finally { event.recycle() }
                SystemClock.sleep(20)
            }
            awaitOnUi("drag moved away from bottom") {
                !list.isScrollInProgress && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)
            }
            var index = 0
            scenario.onActivity {
                index = list.firstVisibleItemIndex
                state.value = state.value.copy(messages = state.value.messages.map {
                    if (it.id == "a") it.copy(text = prefix + tail + "\n\n来源：本月工作室周报。", isStreaming = false) else it
                }, isGenerating = false)
            }
            SystemClock.sleep(800)
            scenario.onActivity {
                assertEquals("completion must not pull a manual reader back to the bottom", index, list.firstVisibleItemIndex)
                assertTrue(list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)
            }
        }
    }
}
