package com.hermes.client.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Loading motion (docs/DESIGN.md §5.6): the reveal gate, three dots, row cap, animations-off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class BrandLoaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun a_wait_shorter_than_the_gate_shows_no_loading_furniture() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HermesTheme(darkTheme = false) { LoadingState() } }
        compose.mainClock.advanceTimeBy(Motion.RevealDelay - 50L)
        compose.onNodeWithTag("loading-dots").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(Motion.RevealDelay + Motion.RevealFade.toLong())
        compose.onNodeWithTag("loading-dots").assertExists()
    }

    @Test fun the_list_skeleton_also_waits_for_the_gate() {
        compose.mainClock.autoAdvance = false
        compose.setContent { HermesTheme(darkTheme = false) { ListLoadingState() } }
        compose.mainClock.advanceTimeBy(Motion.RevealDelay - 50L)
        compose.onNodeWithTag("skeleton-rows").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(Motion.RevealDelay + Motion.RevealFade.toLong())
        compose.onNodeWithTag("skeleton-rows").assertExists()
    }

    @Test fun the_skeleton_never_draws_more_than_a_screenful() {
        assertEquals(skeletonHeight(SKELETON_MAX_ROWS), skeletonHeight(SKELETON_MAX_ROWS + 4))
        assertTrue(skeletonHeight(3) < skeletonHeight(SKELETON_MAX_ROWS))
        compose.mainClock.autoAdvance = false
        compose.setContent { HermesTheme(darkTheme = false) { SkeletonRows(rows = 12) } }
        compose.onNodeWithTag("skeleton-rows").assertHeightIsEqualTo(skeletonHeight(SKELETON_MAX_ROWS))
    }

    /** With animations off the dots must still be drawn — a blank box is not acceptable. */
    @Test fun the_dots_and_the_top_line_survive_animations_off() {
        compose.setContent {
            HermesTheme(darkTheme = false) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    androidx.compose.foundation.layout.Column {
                        LoadingDots()
                        TopProgressLine()
                    }
                }
            }
        }
        compose.onNodeWithTag("loading-dots").assertIsDisplayed()
        compose.onNodeWithTag("top-progress-line").assertIsDisplayed()
    }
}
