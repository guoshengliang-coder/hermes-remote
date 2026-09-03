package com.hermes.client.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Structure and semantics of the redesigned prompt list (docs/DESIGN.md §5.4, decision 2026-09-03). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class PromptListTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        PromptRow(0, ordinal = null, label = "会话开始", time = null, isCurrent = false, isLeading = true),
        PromptRow(1, ordinal = 1, label = "现在这台机器的性能负荷如何", time = null, isCurrent = false, isLeading = false),
        PromptRow(2, ordinal = 2, label = "内存消耗做下拆解分析", time = null, isCurrent = true, isLeading = false),
        PromptRow(3, ordinal = 3, label = "把chrome关掉", time = "11:02", isCurrent = false, isLeading = false),
    )

    @Test fun rows_carry_an_ordinal_and_only_the_current_row_says_where_you_are() {
        var picked: PromptRow? = null
        compose.setContent { HermesTheme(darkTheme = false) { PromptListContent(rows, onPick = { picked = it }) } }
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        // The current row is announced as such; nobody else is, and there is no visible "当前位置" text.
        compose.onNodeWithContentDescription("第 2 条：内存消耗做下拆解分析")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前位置"))
        compose.onNodeWithContentDescription("第 1 条：现在这台机器的性能负荷如何")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText("当前位置").assertDoesNotExist()
        // Time shows only where the message has one.
        compose.onNodeWithText("11:02").assertIsDisplayed()
        compose.onNodeWithContentDescription("第 3 条：把chrome关掉").performClick()
        assertEquals(3, picked?.groupIndex)
    }

    @Test fun header_counts_prompts_and_offers_the_way_back_to_the_latest_turn() {
        var latest = 0
        compose.setContent { HermesTheme(darkTheme = false) { PromptListHeader(count = 3, onLatest = { latest++ }) } }
        compose.onNodeWithText("我的提问").assertIsDisplayed()
        compose.onNodeWithText("3 条").assertIsDisplayed()
        compose.onNodeWithTag("prompt-list-latest").performClick()
        assertEquals(1, latest)
    }
}
