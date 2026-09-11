package com.hermes.client.ui.sessions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.tuning.LocalSessionListTuning
import com.hermes.client.ui.tuning.SessionListTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TUNING-TEMP — delete together with the tuning panel (grep TUNING-TEMP).
 *
 * The panel's spacing knob shipped inert: `rowHeightDp` was declared, persisted and drawn as a
 * stepper, but nothing ever read it, so the one parameter the product owner most wanted to move
 * did nothing at all on the device. A knob that stores a number without changing a pixel is worse
 * than no knob, because it makes the design look like it was already tried and rejected.
 *
 * Both directions matter and neither is redundant. The non-default case fails on the original bug
 * — no modifier reached the row. The default case guards the fix itself: forcing a height is only
 * safe to ship because at 72 nothing is forced, and Material sizes the row exactly as it does in
 * production (a two-line row is 72dp, but a CJK subline that wraps reaches the 88dp three-line
 * floor). Asserting "default == 72dp" would have pinned the wrong behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SessionRowTuningTest {
    @get:Rule val compose = createComposeRule()

    private val session = Session(
        id = "s1", title = "重构 gateway 路由中间件", model = "claude-opus-5", provider = null,
        messageCount = 1, profile = "personal", cwd = "/u/me/hermes", gitRepoRoot = "/u/me/hermes",
        gitBranch = null,
    )

    private fun heightDpOf(tuning: SessionListTuning): Float {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalSessionListTuning provides tuning) {
                        SessionRow(
                            session = session, isPinned = false, defaultProjectPath = null,
                            onMoveToProject = {}, onOpen = {}, onTogglePin = {}, onRename = {},
                            onArchive = {}, onDelete = {},
                        )
                    }
                }
            }
        }
        // The row is the clickable ancestor of the title, so its bounds are the row's bounds.
        val node = compose.onNodeWithText(session.title).onParent().fetchSemanticsNode()
        return node.size.height / compose.density.density
    }

    @Test fun the_panel_height_reaches_the_row() {
        assertEquals(56f, heightDpOf(SessionListTuning(rowHeightDp = 56f)), 0.5f)
    }

    @Test fun the_default_forces_nothing_and_leaves_material_in_charge() {
        val shipped = heightDpOf(SessionListTuning())
        // Not an equality check on 72: a wrapping CJK subline legitimately reaches 88dp. What must
        // hold is that the default is Material's own answer, which is never the forced 56 above.
        assertNotEquals(56f, shipped)
        assert(shipped >= 72f) { "default row collapsed to ${shipped}dp; Material's floor is 72dp" }
    }
}
