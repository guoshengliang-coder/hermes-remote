package com.hermes.client.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The alert strip's two shapes (HG-50, 2026-09-14).
 *
 * It had no test at all before this, which is how it stayed click-only long after one of its two
 * callers had nowhere to send the tap: the scheduled-jobs list scrolled to a group that was already
 * the next thing on screen.
 *
 * The arrow is the part worth pinning rather than just the click. §5.16's rule is that anything
 * tappable ends in a thin arrow, and the promise has to hold in reverse too — an arrow on a strip
 * that does not move is a worse lie than no arrow, because the user has no way to find out except
 * by tapping it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class IncidentStripTest {
    @get:Rule val compose = createComposeRule()

    private fun show(onClick: (() -> Unit)?) {
        compose.setContent {
            InChinese {
                HermesTheme {
                    IncidentStrip(
                        label = "1 个任务需要处理",
                        icon = Icons.Rounded.Schedule,
                        onClick = onClick,
                    )
                }
            }
        }
    }

    private fun clickableNodeCount(): Int =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size

    @Test fun a_tappable_strip_reports_the_tap() {
        var taps = 0
        show { taps++ }
        compose.onNodeWithText("1 个任务需要处理").performClick()
        assertEquals(1, taps)
    }

    /** No click action anywhere in the strip — not merely a no-op handler. */
    @Test fun a_strip_without_a_destination_is_not_clickable() {
        show(onClick = null)
        assertEquals(0, clickableNodeCount())
    }

    // Unmerged: on a tappable strip the clickable Row merges its children's semantics, so the
    // chevron is not a node of its own in the merged tree. Both arrow assertions use the unmerged
    // tree so that they are asking the same question in both shapes.
    private fun arrowCount(): Int =
        compose.onAllNodes(hasTestTag(INCIDENT_STRIP_ARROW_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test fun a_tappable_strip_ends_in_an_arrow() {
        show { }
        assertEquals(1, arrowCount())
    }

    /** The arrow goes with the click: §5.16's promise has to hold in reverse as well. */
    @Test fun an_announcing_strip_has_no_arrow() {
        show(onClick = null)
        assertEquals(0, arrowCount())
    }
}
