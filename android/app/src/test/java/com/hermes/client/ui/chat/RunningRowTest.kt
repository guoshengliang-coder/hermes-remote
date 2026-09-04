package com.hermes.client.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One indicator for the whole run (docs/DESIGN.md §5.6). The three bouncing dots that used to
 * stand in before the first token are gone: the mark is already there, and only the text changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class RunningRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun streaming(text: String = "", thinking: String = "", tools: List<ToolCall> = emptyList()) =
        ChatMessage(id = "s", role = Role.ASSISTANT, text = text, thinking = thinking, tools = tools, isStreaming = true)

    private fun show(msg: ChatMessage) {
        compose.mainClock.autoAdvance = false
        compose.setContent { HermesTheme(darkTheme = false) { RunningStatusLine(msg) } }
    }

    @Test fun before_the_first_token_the_mark_stands_alone() {
        show(streaming())
        compose.onNodeWithTag("hermes-mark").assertExists()
        // No "Generating…" yet: it would be read once and replaced a beat later by the real status.
        compose.onNodeWithText("生成中…", substring = true).assertDoesNotExist()
    }

    @Test fun the_same_mark_gains_a_label_once_output_arrives() {
        show(streaming(text = "已经有一段输出"))
        compose.onNodeWithTag("hermes-mark").assertExists()
        compose.onNodeWithText("生成中…", substring = true).assertExists()
    }

    @Test fun a_running_tool_keeps_one_indicator_and_swaps_only_the_text() {
        show(streaming(text = "x", tools = listOf(ToolCall("t", "Bash", ToolStatus.RUNNING, command = "npm test"))))
        compose.onNodeWithTag("hermes-mark").assertExists()
        compose.onNodeWithText("npm test", substring = true).assertExists()
        compose.onNodeWithText("生成中…", substring = true).assertDoesNotExist()
    }
}
