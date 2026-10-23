package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the one-row search bar shows when (docs/DESIGN.md §5.4, Stitch 基线-聊天页/搜索).
 *
 * The counter and the two arrows now live INSIDE the field and appear only once something is
 * typed — the un-typed mock draws neither. That is a rule about which controls exist, not about
 * pixels, so it is asserted here rather than left to the goldens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatSearchBarTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(
        query: String,
        matchCount: Int = 0,
        currentIndex: Int = 0,
        historyLoaded: Boolean = true,
        onSearchAll: ((String) -> Unit)? = {},
    ) {
        compose.setContent {
            HermesTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                    ChatSearchBar(
                        query = query,
                        onQueryChange = {},
                        matchCount = matchCount,
                        currentIndex = currentIndex,
                        historyLoaded = historyLoaded,
                        onPrevious = {},
                        onNext = {},
                        onClose = {},
                        onSearchAll = onSearchAll,
                        requestFocus = false,
                    )
                }
            }
        }
    }

    @Test
    fun nothing_typed_shows_neither_counter_nor_arrows() {
        bar(query = "")
        compose.onNodeWithContentDescription("上一个匹配项").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一个匹配项").assertDoesNotExist()
        compose.onNodeWithText("0/0").assertDoesNotExist()
        // Closing is still reachable, and it is the leading back arrow now, not a trailing ✕.
        compose.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
    }

    @Test
    fun hits_are_counted_by_occurrence_and_both_arrows_are_live() {
        bar(query = "图", matchCount = 12, currentIndex = 3)
        compose.onNodeWithText("4/12").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一个匹配项").assertIsEnabled()
        compose.onNodeWithContentDescription("下一个匹配项").assertIsEnabled()
    }

    @Test
    fun a_shrunk_match_set_never_shows_a_counter_past_its_own_total() {
        bar(query = "图", matchCount = 2, currentIndex = 4)
        compose.onNodeWithText("2/2").assertIsDisplayed()
    }

    @Test
    fun nothing_found_disables_the_arrows_and_offers_the_global_search() {
        bar(query = "未命中的词", matchCount = 0)
        compose.onNodeWithText("0/0").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一个匹配项").assertIsNotEnabled()
        compose.onNodeWithText("此会话中没有匹配").assertIsDisplayed()
        compose.onNodeWithText("在全部会话中搜索").assertIsDisplayed()
    }

    @Test
    fun a_still_loading_history_says_so_instead_of_claiming_nothing_matched() {
        bar(query = "连接失败", matchCount = 0, historyLoaded = false)
        compose.onNodeWithText("正在加载对话…").assertIsDisplayed()
        compose.onNodeWithText("此会话中没有匹配").assertDoesNotExist()
    }

    @Test
    fun one_character_says_nothing_at_all() {
        bar(query = "图", matchCount = 0)
        compose.onNodeWithText("此会话中没有匹配").assertDoesNotExist()
        compose.onNodeWithText("正在加载对话…").assertDoesNotExist()
    }
}
