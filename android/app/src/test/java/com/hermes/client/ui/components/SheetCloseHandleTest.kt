package com.hermes.client.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The grab bar is the sheet's only touch route to dismissal (docs/DESIGN.md §5.8 global rule). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SheetCloseHandleTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(onDismiss: () -> Unit) {
        compose.setContent { HermesTheme(darkTheme = false) { SheetCloseHandle(onDismiss) } }
    }

    @Test fun tapping_the_bar_dismisses() {
        var dismissed = 0
        show { dismissed++ }
        compose.onNodeWithTag("sheet-close-handle").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun a_short_pull_does_nothing_but_a_pull_past_the_threshold_dismisses_once() {
        var dismissed = 0
        show { dismissed++ }
        val node = compose.onNodeWithTag("sheet-close-handle")
        node.performTouchInput { swipeDown(startY = top + 2f, endY = top + 2f + SHEET_CLOSE_DRAG_DP * density / 2f, durationMillis = 120) }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        node.performTouchInput { swipeDown(startY = top + 2f, endY = top + 2f + SHEET_CLOSE_DRAG_DP * density * 3f, durationMillis = 120) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }
}
