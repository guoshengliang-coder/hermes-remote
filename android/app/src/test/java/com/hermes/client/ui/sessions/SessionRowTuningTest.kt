package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.tuning.LocalSessionListTuning
import com.hermes.client.ui.tuning.SessionListTuning
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The session row's height is DERIVED — padding plus the type steps — and this pins the arithmetic.
 *
 * It replaces a test that pinned the opposite thing. The row used to be a Material `ListItem`, and
 * the temporary tuning panel drove an exact `Modifier.height` because `ListItem` will not go below
 * its 56 / 72 / 88dp line-count floor. The 5th Stitch pull asks for a 49.1dp two-line row — under
 * even the one-line floor — so the row is drawn by hand and the height is no longer set at all.
 *
 * **`@GraphicsMode(NATIVE)` is load-bearing, not decoration.** Robolectric's default graphics stub
 * out text measurement, and under the stub every case below measures 130px whatever script it is
 * written in — including the Chinese ones, which is a comfortable lie. `ScreenshotTest` already
 * runs NATIVE, which is why its goldens disagreed with an earlier version of this file by 4px and
 * exposed the difference.
 *
 * The numbers, at 411dp / 420dpi (2.625×), measured rather than assumed:
 *
 *   Latin title + Latin project     130px = 49.52dp   ← the mock's 49.10, plus pixel rounding
 *   CJK title   + Latin project     134px = 51.05dp
 *   Latin title + CJK project       134px = 51.05dp
 *   CJK title   + CJK project       138px = 52.57dp
 *
 * So **every line of Chinese costs exactly 4px**. `leading-[1.35]` is derived from the mock's Latin
 * setting, and the CJK fallback face needs more ascent + descent than `size × 1.35` at these sizes,
 * so that line box grows to what the font requires. Nothing can be done about it short of clipping
 * the glyphs, and it is recorded as an intentional deviation in docs/DESIGN.md §5.2.
 *
 * That is also the shape of the old bug, and the contrast is the point: a CJK project name used to
 * add **16dp** by tipping the row into `ListItem`'s three-line tier, top-aligned with a hole under
 * the subline (ANDROID_SMOKE A-05). It now adds 1.5dp of evenly distributed leading.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SessionRowTuningTest {
    @get:Rule val compose = createComposeRule()

    private fun session(title: String, project: String?) = Session(
        id = title, title = title, model = "claude-opus-5", provider = null,
        messageCount = 1, profile = "personal", cwd = project, gitRepoRoot = project,
        gitBranch = null,
    )

    private fun failed(id: String) =
        SessionRuntime(key = SessionRuntimeKey("personal", id), phase = SessionRunPhase.FAILED)

    /**
     * One `setContent` per test is all Compose allows, so every row a test needs is rendered in the
     * same column and measured by its title.
     */
    private fun show(tuning: SessionListTuning = SessionListTuning(), rows: @Composable () -> Unit) {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalSessionListTuning provides tuning) {
                        Column { rows() }
                    }
                }
            }
        }
    }

    @Composable
    private fun Row(session: Session, runtime: SessionRuntime? = null) {
        SessionRow(
            session = session, isPinned = false, defaultProjectPath = null,
            onMoveToProject = {}, runtime = runtime, onOpen = {}, onTogglePin = {},
            onRename = {}, onArchive = {}, onDelete = {},
        )
    }

    /**
     * `combinedClickable` merges the row into one semantics node, so the node carrying the title
     * IS the row. (Taking `.onParent()` from here returns the enclosing column — which happens to
     * measure the same as the row when a test renders only one, and silently sums them when it
     * renders two.)
     */
    private fun heightOf(title: String): Float =
        compose.onNodeWithText(title).fetchSemanticsNode().size.height / compose.density.density

    @Test fun a_latin_row_is_the_mock_arithmetic() {
        // 6 + 14.5×1.35 + 2 + 11.5×1.35 + 6 = 49.10dp.
        val s = session("Refactor the gateway router", "hermes-remote")
        show { Row(s) }
        assertEquals(49.10f, heightOf(s.title), 0.6f)
    }

    @Test fun a_status_line_adds_one_step_and_no_cliff() {
        // The old row jumped 72 → 88dp here, because a third line put it in another Material tier.
        // Now it grows by exactly the step it gained: 2 + 11.5×1.35 = 17.53dp — plus the 1.52dp
        // every Chinese line costs, and 「运行失败」 is Chinese under InChinese, which is the
        // language this list is read in.
        val two = session("Refactor the gateway router", "hermes-remote")
        val three = session("Tidy the deployment docs", "hermes-remote")
        show {
            Row(two)
            Row(three, runtime = failed(three.id))
        }
        assertEquals(heightOf(two.title) + 17.53f + 1.52f, heightOf(three.title), 0.3f)
    }

    @Test fun chinese_costs_one_line_of_leading_and_nothing_more() {
        // ANDROID_SMOKE A-05 was 16dp and came from the line-count floor. What is left is the CJK
        // face needing more than `size × 1.35`, which is 1.52dp per Chinese line and unavoidable.
        val latin = session("Refactor the gateway router", "hermes-remote")
        val cjkTitle = session("重构网关路由中间件", "hermes-remote")
        val cjkBoth = session("整理部署文档", "赫尔墨斯远程")
        show {
            Row(latin)
            Row(cjkTitle)
            Row(cjkBoth)
        }
        val base = heightOf(latin.title)
        assertEquals(base + 1.52f, heightOf(cjkTitle.title), 0.3f)
        assertEquals(base + 3.05f, heightOf(cjkBoth.title), 0.3f)
    }

    @Test fun a_long_title_does_not_make_the_row_taller() {
        // The mock's `truncate`. Wrapping was the other reason row height varied between rows.
        val short = session("Short", "hermes-remote")
        val long = session(
            "A title far past the width of any phone, which used to wrap onto a second line",
            "hermes-remote",
        )
        show {
            Row(short)
            Row(long)
        }
        assertEquals(heightOf(short.title), heightOf(long.title), 0.3f)
    }

    @Test fun the_padding_knob_still_moves_the_row() {
        // The panel stays until the product owner settles the numbers on a device, so the one knob
        // that controls density has to keep working. Both rows are measured in the same render and
        // compared to each other, so the assertion is the +12dp itself and carries no dependence
        // on the absolute height or on how the fonts happen to round.
        val base = session("Refactor the gateway router", "hermes-remote")
        val loose = session("Refactor the gateway proxy", "hermes-remote")
        show {
            Row(base)
            CompositionLocalProvider(
                LocalSessionListTuning provides SessionListTuning(rowPaddingVDp = 12f),
            ) { Row(loose) }
        }
        assertEquals(heightOf(base.title) + 12f, heightOf(loose.title), 0.3f)
    }
}
