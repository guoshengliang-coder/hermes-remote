package com.hermes.client.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Chats/Bots segment is a submerged capsule: a `surfaceContainer` track with the selected
 * segment raised out of it (docs/DESIGN.md §5.2). "Raised" has to be measured against the TRACK,
 * which is the mistake this test exists to catch — the first cut reused `tileColor()`, whose dark
 * tier lifts one step off `surface` and therefore landed 1.01:1 from the track. The selected
 * segment was effectively invisible in dark while looking fine in light.
 */
class SegmentPillTest {

    /** Light raises toward white, dark raises toward the top of the container ramp. */
    @Test fun selected_pill_separates_from_its_track_in_both_themes() {
        val light = contrast(
            HermesLightColors.surfaceContainerLowest.toArgb(),
            HermesLightColors.surfaceContainer.toArgb(),
        )
        val dark = contrast(
            HermesDarkColors.surfaceContainerHighest.toArgb(),
            HermesDarkColors.surfaceContainer.toArgb(),
        )
        assertTrue("light pill vs track $light < 1.12", light >= 1.12)
        assertTrue("dark pill vs track $dark < 1.12", dark >= 1.12)
    }

    /** The value that was actually wrong, stated so nobody reintroduces it. */
    @Test fun the_faint_card_fill_would_not_have_worked_in_dark() {
        val naive = lerpToWhite(HermesDarkColors.surface.toArgb(), 0.06f)
        val c = contrast(naive, HermesDarkColors.surfaceContainer.toArgb())
        assertTrue("tileColor()'s dark tier unexpectedly separates ($c)", c < 1.05)
    }

    private fun lerpToWhite(argb: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val v = (argb shr shift) and 0xFF
            return (v + t * (255 - v)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        fun lum(argb: Int) = 0.2126 * lin((argb shr 16) and 0xFF) +
            0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
