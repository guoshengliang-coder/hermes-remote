package com.hermes.client.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The action row's height is DERIVED — 12 + content + 12 — and this pins the arithmetic, including
 * the part of it that is not 44dp.
 *
 * 44dp is what the mock computes from `py-3` around a 20px line box, and it is what a Latin label
 * measures. A Chinese label measures more, for the reason `SessionRowTuningTest` documents at
 * length: `lineHeight` is derived from the mock's Latin setting, and the CJK fallback face needs
 * more ascent + descent than that at these sizes, so the line box grows to what the font requires.
 * Nothing can be done about it short of clipping glyphs.
 *
 * `@GraphicsMode(NATIVE)` is load-bearing: under Robolectric's default stub every label measures
 * the same whatever script it is written in, which would make the CJK case below a comfortable lie.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class RowActionItemHeightTest {
    @get:Rule val compose = createComposeRule()

    private fun show(vararg labels: String) {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    Column {
                        labels.forEach { RowActionItem(Icons.Rounded.PushPin, it, onClick = {}) }
                    }
                }
            }
        }
    }

    private fun heightOf(label: String): Float =
        compose.onNodeWithText(label).fetchSemanticsNode().size.height / compose.density.density

    @Test fun a_latin_row_is_the_mock_arithmetic() {
        // 12 + 20 + 12 = 44dp, straight off `py-3` around the inherited 20px line box.
        show("Move to project")
        assertEquals(44f, heightOf("Move to project"), 0.6f)
    }

    @Test fun a_chinese_row_costs_the_cjk_line_box() {
        // Measured, not assumed. Recorded as an intentional deviation in docs/DESIGN.md §5.5 —
        // the same one the session row carries.
        show("移动到项目")
        assertEquals(48.4f, heightOf("移动到项目"), 0.8f)
    }

    /** The trailing hint rides in the same line box and must not add a step of its own. */
    @Test fun a_trailing_hint_does_not_grow_the_row() {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    Column {
                        RowActionItem(Icons.Rounded.PushPin, "归档会话", onClick = {})
                        RowActionItem(Icons.Rounded.PushPin, "删除会话", hint = "不可撤销", onClick = {})
                    }
                }
            }
        }
        assertEquals(heightOf("归档会话"), heightOf("删除会话"), 0.1f)
    }
}
