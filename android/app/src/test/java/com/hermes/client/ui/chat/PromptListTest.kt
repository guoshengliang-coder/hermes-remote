package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.ui.sessions.BotOrigin
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.InChinese
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structure and semantics of the prompt list (docs/DESIGN.md §5.4; header restyled 2026-09-12 off
 * Stitch 基线-聊天页/我的提问).
 */
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
        compose.setContent { InChinese { HermesTheme(darkTheme = false) { PromptListContent(rows, onPick = { picked = it }) } } }
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

    // The ≤2-line rule (this app's deliberate departure from the mock's one-line truncation) is NOT
    // asserted here: plain Robolectric measures text with a stub font that never wraps, so a long
    // and a short summary come out the same height. Roborazzi does lay text out for real, so the
    // rule lives in the `prompt-list-rows` golden, which carries a wrapping long prompt on purpose.

    @Test fun header_counts_prompts_and_offers_the_way_back_to_the_latest_turn() {
        var latest = 0
        compose.setContent { InChinese { HermesTheme(darkTheme = false) { PromptListHeader(count = 3, onLatest = { latest++ }) } } }
        compose.onNodeWithText("我的提问").assertIsDisplayed()
        compose.onNodeWithText("3 条").assertIsDisplayed()
        compose.onNodeWithTag("prompt-list-latest").performClick()
        assertEquals(1, latest)
    }

    /**
     * The ✕ added 2026-09-12. It is a FOURTH way out (§5.8), so what matters is that it dismisses —
     * the grab bar, the scrim and back are unchanged and tested by the sheet's own defaults.
     */
    @Test fun the_header_close_button_dismisses_the_sheet() {
        var dismissed = 0
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    PromptListHeader(count = 3, onLatest = {}, onDismiss = { dismissed++ })
                }
            }
        }
        compose.onNodeWithTag("prompt-list-close").performClick()
        assertEquals(1, dismissed)
    }

    /**
     * Both header buttons lost their words when they became icons, so the description IS the label
     * now — an unlabelled ⇊ is unreadable to TalkBack and nearly as unreadable to everyone else.
     */
    @Test fun the_icon_only_header_buttons_still_say_what_they_do() {
        compose.setContent { InChinese { HermesTheme(darkTheme = false) { PromptListHeader(count = 3, onLatest = {}) } } }
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    /**
     * Regression for a real bug: the menu entry that opens this sheet said 「对方的提问」 in a bot
     * conversation while the sheet's own title said 「我的提问」 — the app attributing someone
     * else's words to the user, which docs/CHAT_PROMPT_NAVIGATION_REQUIREMENTS.md §6 forbids.
     */
    @Test fun a_bot_conversations_prompts_are_not_called_mine() {
        val origin = BotOrigin(displayName = "老王", chatType = "private", source = "wecom")
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalBotOrigin provides origin) {
                        PromptListHeader(count = 3, onLatest = {})
                    }
                }
            }
        }
        compose.onNodeWithText("对方的提问").assertIsDisplayed()
        compose.onNodeWithText("我的提问").assertDoesNotExist()
    }
}
