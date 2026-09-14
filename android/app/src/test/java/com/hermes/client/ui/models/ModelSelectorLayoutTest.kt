package com.hermes.client.ui.models

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HG-32. Every provider card's 「N 项」 count and chevron form one right-aligned column, whatever
 * the card happens to be called.
 *
 * A screenshot golden did not catch this and could not have: the fixture's two collapsed groups
 * were 「Anthropic」 and 「DeepSeek」, within a character of each other, so the drift was a pixel or
 * two. The reporter's device had 「GitHub Copilot」 above 「DeepSeek」 above an ellipsised
 * 「devops-ai-proxy.yi…」, and the steps were unmistakable. So this asserts the geometry directly,
 * with titles as unequal as a real catalogue's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ModelSelectorLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val providers = listOf(
        ModelProviderDto(slug = "copilot", name = "GitHub Copilot", isCurrent = false, models = List(17) { "c$it" }),
        ModelProviderDto(slug = "deepseek", name = "DeepSeek", isCurrent = false, models = List(3) { "d$it" }),
        // Long enough to ellipsise — the card that used to sit furthest right.
        ModelProviderDto(
            slug = "proxy", name = "devops-ai-proxy.yiyuan.internal.example",
            isCurrent = false, models = List(48) { "p$it" },
        ),
        ModelProviderDto(slug = "opencode", name = "OpenCode Free", isCurrent = false, models = List(7) { "o$it" }),
    )

    private fun showAllCollapsed() {
        compose.setContent {
            HermesTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                    Surface {
                        ModelSelectorContent(
                            groups = modelSelectorGroups(
                                providers = providers, favorites = emptySet(),
                                currentProvider = null, currentModel = null,
                                expandedGroups = emptySet(),
                            ),
                            onToggleFavorite = { _, _ -> },
                            onSelect = { _, _ -> },
                            onToggleGroup = {},
                            pendingKey = null,
                            error = null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * `useUnmergedTree` is load-bearing: the card header Row is clickable, so the chevron's
     * description merges up into it and the merged node is the FULL-WIDTH row. Querying the merged
     * tree gets four identical row bounds and the assertion passes no matter what the icon does.
     */
    private fun chevrons() = compose.onAllNodesWithContentDescription("展开分组", useUnmergedTree = true)

    private fun chevronRights(): List<Float> {
        val count = chevrons().fetchSemanticsNodes().size
        assertEquals("all four provider cards must be collapsed and visible", 4, count)
        return (0 until count).map { chevrons()[it].getUnclippedBoundsInRoot().right.value }
    }

    @Test fun every_collapsed_card_puts_its_chevron_on_the_same_right_edge() {
        showAllCollapsed()
        val rights = chevronRights()
        rights.forEach { right ->
            assertEquals(
                "a card's chevron must share the right edge of every other card's — HG-32: a short " +
                    "title used to drag the count and chevron left with it. Got $rights",
                rights.first(), right, 0.5f,
            )
        }
    }

    @Test fun the_count_column_is_right_aligned_too() {
        showAllCollapsed()
        // 17 / 3 / 48 / 7 — different digit counts, so only a right-aligned column lines up.
        val rights = listOf("17 项", "3 项", "48 项", "7 项").map { text ->
            compose.onNode(hasText(text), useUnmergedTree = true)
                .getUnclippedBoundsInRoot().right.value
        }
        rights.forEach { right ->
            assertEquals(
                "counts must share one right edge whatever their digit count. Got $rights",
                rights.first(), right, 0.5f,
            )
        }
    }

    /** The over-long title is what gives way, never the pair to its right. */
    @Test fun a_title_too_long_for_its_card_ellipsises_instead_of_pushing_the_count_off() {
        showAllCollapsed()
        val chevron = chevrons()[2].getUnclippedBoundsInRoot()
        assertTrue(
            "the longest-titled card's chevron must stay inside the 411dp screen, got $chevron",
            chevron.right.value <= 411f && chevron.left.value > 0f,
        )
        assertEquals(
            "and must keep its full 20dp box",
            20f, (chevron.right - chevron.left).value, 0.5f,
        )
    }
}
