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
 *   two-line   = 10 + 15.5×1.4 + 2 + 12×1.4 + 10 = 60.50dp  (measured 60.57)
 *   three-line = two-line + 4 + 12×1.4              = 81.30dp
 *
 * **These are the SHIPPED numbers, not the mock's.** The product owner tuned on a device on
 * 2026-09-14 and settled a step looser everywhere: 10dp row padding against the mock's 6, leading
 * 1.4 against 1.35, title back to 15.5sp, subline and status back to 12sp. The mock's own values
 * stay in docs/design/stitch/; what this file pins is what users get.
 *
 * Chinese still costs extra leading, but far less than before: **0.76dp per line**, down from 1.52
 * at leading 1.35 — a looser leading covers more of what the CJK fallback face asks for.
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

    @Test fun a_latin_row_is_the_shipped_arithmetic() {
        // 10 + 15.5×1.4 + 2 + 12×1.4 + 10 = 60.50dp.
        val s = session("Refactor the gateway router", "hermes-remote")
        show { Row(s) }
        assertEquals(60.50f, heightOf(s.title), 0.6f)
    }

    @Test fun a_status_line_adds_one_step_and_no_cliff() {
        // The old row jumped 72 → 88dp here, because a third line put it in another Material tier.
        // Now it grows by exactly the step it gained: 4 + 12×1.4 = 20.80dp — plus the 0.76dp every
        // Chinese line costs, and 「运行失败」 is Chinese under InChinese, which is the language this
        // list is read in.
        val two = session("Refactor the gateway router", "hermes-remote")
        val three = session("Tidy the deployment docs", "hermes-remote")
        show {
            Row(two)
            Row(three, runtime = failed(three.id))
        }
        assertEquals(heightOf(two.title) + 20.80f + 0.76f, heightOf(three.title), 0.4f)
    }

    @Test fun chinese_costs_one_line_of_leading_and_nothing_more() {
        // ANDROID_SMOKE A-05 was 16dp and came from the line-count floor. What is left is the CJK
        // face needing more than `size × leading`, now 0.76dp per Chinese line at leading 1.4.
        val latin = session("Refactor the gateway router", "hermes-remote")
        val cjkTitle = session("重构网关路由中间件", "hermes-remote")
        val cjkBoth = session("整理部署文档", "赫尔墨斯远程")
        show {
            Row(latin)
            Row(cjkTitle)
            Row(cjkBoth)
        }
        val base = heightOf(latin.title)
        // Not quite linear — each line box rounds to a whole pixel on its own, so two Chinese
        // lines cost 1.14dp rather than twice 0.76. Measured, not derived.
        assertEquals(base + 0.76f, heightOf(cjkTitle.title), 0.3f)
        assertEquals(base + 1.14f, heightOf(cjkBoth.title), 0.3f)
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
                LocalSessionListTuning provides SessionListTuning(rowPaddingVDp = 16f),
            ) { Row(loose) }
        }
        assertEquals(heightOf(base.title) + 12f, heightOf(loose.title), 0.3f)
    }
}
