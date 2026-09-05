package com.hermes.client.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/DESIGN.md §5.4, decision 2026-09-05: a completed turn's tool timeline folds behind a
 * one-line summary; a running one stays open because it is the progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ToolTimelineCollapseTest {
    @get:Rule
    val compose = createComposeRule()

    private val tools = listOf(
        ToolCall("a", "skill_view", ToolStatus.DONE, output = "---", durationMs = 1_200),
        ToolCall("b", "terminal", ToolStatus.DONE, command = "date", exitCode = 0, durationMs = 300),
        ToolCall("c", "mcp__bi_query__query_data", ToolStatus.DONE, output = "rows: 42", exitCode = 1),
        ToolCall("d", "write_file", ToolStatus.DONE),
    )

    private fun show(completed: Boolean) {
        compose.setContent { HermesTheme(darkTheme = false) { ToolTimelineCard(tools, completed = completed, stateKey = "t") } }
    }

    @Test fun a_completed_timeline_first_seen_complete_is_folded_behind_its_summary() {
        show(completed = true)
        compose.onNodeWithTag("tool-timeline-summary").assertIsDisplayed()
        compose.onNodeWithText("4 次工具调用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 次失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("terminal").assertDoesNotExist()
        compose.onNodeWithText("mcp__bi_query__query_data").assertDoesNotExist()
    }

    @Test fun tapping_the_summary_unfolds_the_rows() {
        show(completed = true)
        compose.onNodeWithTag("tool-timeline-summary").performClick()
        compose.onNodeWithText("terminal").assertIsDisplayed()
        compose.onNodeWithText("mcp__bi_query__query_data").assertIsDisplayed()
    }

    @Test fun a_running_timeline_has_no_summary_and_stays_open() {
        show(completed = false)
        compose.onNodeWithTag("tool-timeline-summary").assertDoesNotExist()
        compose.onNodeWithText("terminal").assertIsDisplayed()
    }
}
