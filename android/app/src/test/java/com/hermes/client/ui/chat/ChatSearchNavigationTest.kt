package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.createHermesComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatSearchNavigationTest {
    @get:Rule val compose = createHermesComposeRule()

    @Test fun switching_occurrences_in_one_oversized_reply_reveals_the_actual_mark() {
        exercise(Role.ASSISTANT, dark = false)
    }

    @Test fun oversized_user_bubble_reveals_each_occurrence_in_dark_mode() {
        exercise(Role.USER, dark = true)
    }

    @Test fun a_match_inside_a_long_table_cell_is_revealed() {
        exercise(Role.ASSISTANT, dark = false, table = true)
    }

    @Test fun reference_link_marks_keep_the_same_visible_ordinals_as_the_counter() {
        exercise(Role.ASSISTANT, dark = false, reference = true)
    }

    private fun exercise(role: Role, dark: Boolean, table: Boolean = false, reference: Boolean = false) {
        // One Markdown paragraph / one user bubble, far taller than the viewport. Scrolling to
        // its lazy index cannot make both marks visible; the second arrow must move within it.
        val prose = (0..70).joinToString(if (table) " " else "\n") { i ->
            when {
                reference && i == 10 -> "[学习][学习]"
                reference && i == 60 -> "[学习]"
                i == 10 || i == 60 -> "学习目标 $i"
                else -> "第 $i 行，保留足够阅读距离。"
            }
        }
        val text = when {
            table -> "| 内容 |\n| --- |\n| $prose |"
            reference -> "$prose\n\n[学习]: https://example.com/hidden"
            else -> prose
        }
        val context = mutableStateOf(ChatSearchContext("学习", "answer", 0))
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                Surface(Modifier.width(360.dp).height(420.dp)) {
                    ChatMessageList(
                        state = ChatUiState(messages = listOf(ChatMessage(id = "answer", role = role, text = text)), historyLoaded = true),
                        sessionId = "search-regression", listState = rememberLazyListState(),
                        searchOpen = true, highlightIndex = 0, searchContext = context.value,
                    )
                }
            }
        }
        fun focusedRectangle(): Rect? {
            val nodes = compose.onAllNodes(hasText("学习", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                for (layout in layouts) {
                    val annotated = layout.layoutInput.text
                    val mark = annotated.getStringAnnotations(SEARCH_FOCUS_TAG, 0, annotated.length)
                        .firstOrNull { it.item == context.value.currentOccurrence.toString() } ?: continue
                    return layout.getPathForRange(mark.start, mark.end).getBounds().translate(node.positionInRoot)
                }
            }
            return null
        }
        fun assertVisible() {
            compose.waitUntil(10_000) {
                focusedRectangle()?.let { it.top >= 0 && it.bottom <= 420 * compose.density.density + 2 } == true
            }
            assertTrue(focusedRectangle() != null)
        }
        assertVisible()
        compose.onRoot().captureRoboImage("build/outputs/hg221/search-${role.name}-$dark-$table-$reference-first.png")
        compose.runOnIdle { context.value = context.value.copy(currentOccurrence = 1) }
        assertVisible()
        compose.onRoot().captureRoboImage("build/outputs/hg221/search-${role.name}-$dark-$table-$reference-second.png")
        compose.runOnIdle { context.value = context.value.copy(currentOccurrence = 0) }
        assertVisible()
    }
}
