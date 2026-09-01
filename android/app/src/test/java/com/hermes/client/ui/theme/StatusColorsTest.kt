package com.hermes.client.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status palette is the one place colour carries MEANING rather than brand, so its values are
 * pinned here rather than left to be nudged. Two invariants matter:
 *
 *  1. GOOD must not be the brand colour. That collision is exactly what this palette exists to
 *     undo — before the blue swap, "completed" resolved to primary.
 *  2. Both tiers must be legible on their own surface. The old traffic light was theme-blind and
 *     its green/red sat at ~3.2:1 on the dark surface.
 */
class StatusColorsTest {

    private val lightSurface = 0xFFFFFFFF.toInt()
    private val darkSurface = 0xFF121921.toInt()

    // The brand primary in each tier — GOOD must stay clear of these.
    private val primaryLight = 0xFF0B5FD0.toInt()
    private val primaryDark = 0xFFA9C7FF.toInt()

    @Test fun values_are_pinned() {
        assertEquals(0xFF2E7D32.toInt(), statusArgb(StatusTone.GOOD, dark = false))
        assertEquals(0xFFC77700.toInt(), statusArgb(StatusTone.WARN, dark = false))
        assertEquals(0xFFC62828.toInt(), statusArgb(StatusTone.BAD, dark = false))
        assertEquals(0xFF7CDC80.toInt(), statusArgb(StatusTone.GOOD, dark = true))
        assertEquals(0xFFFFB945.toInt(), statusArgb(StatusTone.WARN, dark = true))
        assertEquals(0xFFFFB4AB.toInt(), statusArgb(StatusTone.BAD, dark = true))
    }

    @Test fun good_is_never_the_brand_colour() {
        assertNotEquals(primaryLight, statusArgb(StatusTone.GOOD, dark = false))
        assertNotEquals(primaryDark, statusArgb(StatusTone.GOOD, dark = true))
        // Not just a different value — a different hue family, so the two never read as one.
        assertTrue(hueOf(statusArgb(StatusTone.GOOD, dark = false)) in 90f..150f)
        assertTrue(hueOf(statusArgb(StatusTone.GOOD, dark = true)) in 90f..150f)
    }

    // GOOD and BAD are rendered as 12sp labels in the session list, so they owe AA text contrast.
    @Test fun good_and_bad_clear_aa_text_on_their_own_surface() {
        for (tone in listOf(StatusTone.GOOD, StatusTone.BAD)) {
            val light = contrast(statusArgb(tone, dark = false), lightSurface)
            val dark = contrast(statusArgb(tone, dark = true), darkSurface)
            assertTrue("$tone light $light < 4.5", light >= 4.5)
            assertTrue("$tone dark $dark < 4.5", dark >= 4.5)
        }
    }

    // WARN only ever renders as the connection dot, so it owes the 3:1 non-text floor, not 4.5.
    @Test fun warn_clears_non_text_contrast() {
        assertTrue(contrast(statusArgb(StatusTone.WARN, dark = false), lightSurface) >= 3.0)
        assertTrue(contrast(statusArgb(StatusTone.WARN, dark = true), darkSurface) >= 3.0)
    }

    // The dark tier is deliberately level with the dark primary: status separates from chrome by
    // hue, never by shouting louder. Guard the spread so a later tweak cannot break the balance.
    @Test fun dark_tier_is_level_with_the_dark_primary() {
        val ratios = listOf(StatusTone.GOOD, StatusTone.WARN, StatusTone.BAD)
            .map { contrast(statusArgb(it, dark = true), darkSurface) } +
            contrast(primaryDark, darkSurface)
        val spread = ratios.max() - ratios.min()
        assertTrue("dark tier spread $spread too wide: $ratios", spread <= 1.0)
    }

    // --- WCAG + hue math, test-side only (same convention as ProfileAccentTest) ---

    private fun hueOf(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return (h + 360f) % 360f
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lum(argb: Int): Double {
            fun lin(channel: Int): Double {
                val c = channel / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin((argb shr 16) and 0xFF) +
                0.7152 * lin((argb shr 8) and 0xFF) +
                0.0722 * lin(argb and 0xFF)
        }
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
