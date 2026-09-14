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

    private val lightSurface = 0xFFFAF9F5.toInt()
    private val darkSurface = 0xFF0F1217.toInt()

    // The brand primary in each tier — GOOD must stay clear of these.
    private val primaryLight = 0xFF004AC6.toInt()
    private val primaryDark = 0xFFA9C7FF.toInt()

    @Test fun values_are_pinned() {
        assertEquals(0xFF2E7D32.toInt(), statusArgb(StatusTone.GOOD, dark = false))
        assertEquals(0xFFB45309.toInt(), statusArgb(StatusTone.WARN, dark = false))
        assertEquals(0xFFB91C1C.toInt(), statusArgb(StatusTone.BAD, dark = false))
        assertEquals(0xFF34D399.toInt(), statusArgb(StatusTone.GOOD, dark = true))
        assertEquals(0xFFFBBF24.toInt(), statusArgb(StatusTone.WARN, dark = true))
        assertEquals(0xFFF87171.toInt(), statusArgb(StatusTone.BAD, dark = true))
        assertEquals(0xFF0369A1.toInt(), statusArgb(StatusTone.RUNNING, dark = false))
        assertEquals(0xFF67E8F9.toInt(), statusArgb(StatusTone.RUNNING, dark = true))
    }

    /** WARN alone has a second, brighter value for marks — see [warnGraphicArgb]. */
    @Test fun warn_graphic_tier_is_pinned_and_distinct_from_its_text_tier() {
        assertEquals(0xFFD97706.toInt(), warnGraphicArgb(dark = false))
        assertEquals(0xFFF59E0B.toInt(), warnGraphicArgb(dark = true))
        assertNotEquals(statusArgb(StatusTone.WARN, dark = false), warnGraphicArgb(dark = false))
        assertNotEquals(statusArgb(StatusTone.WARN, dark = true), warnGraphicArgb(dark = true))
        // Same amber family, one step apart — not two unrelated colours.
        assertTrue(kotlin.math.abs(hueOf(warnGraphicArgb(false)) - hueOf(statusArgb(StatusTone.WARN, false))) < 15f)
    }

    @Test fun good_is_never_the_brand_colour() {
        assertNotEquals(primaryLight, statusArgb(StatusTone.GOOD, dark = false))
        assertNotEquals(primaryDark, statusArgb(StatusTone.GOOD, dark = true))
        // A different hue family, so the two never read as one. The band reaches 170 because the
        // dark tier took the design's mint #34D399 (hue 163) on 2026-09-11; it stays far from the
        // brand blue at ~217, which is all this guard is for.
        assertTrue(hueOf(statusArgb(StatusTone.GOOD, dark = false)) in 90f..170f)
        assertTrue(hueOf(statusArgb(StatusTone.GOOD, dark = true)) in 90f..170f)
    }

    /**
     * EVERY tone is rendered as a 12sp label in the session list, so every tone owes AA text
     * contrast — not just the two that happened to be checked before. WARN used to sit at 3.29:1
     * with a "dot only" comment while the row drew it as text anyway; including it here is what
     * stops that from coming back.
     *
     * This one survived the 2026-09-11 decision to follow the mock over the floors, because the
     * mock's own text tiers all clear 4.5 anyway — the values below are the design's. It is kept
     * so that a future mock which does not clear it has to be argued for here rather than landing
     * unnoticed.
     */
    @Test fun every_tone_clears_aa_text_on_its_own_surface() {
        for (tone in StatusTone.entries) {
            val light = contrast(statusArgb(tone, dark = false), lightSurface)
            val dark = contrast(statusArgb(tone, dark = true), darkSurface)
            assertTrue("$tone light $light < 4.5", light >= 4.5)
            assertTrue("$tone dark $dark < 4.5", dark >= 4.5)
        }
    }

    /**
     * The graphic amber is the one value in this file that does NOT clear a contrast floor: 3.02:1
     * on warm paper, which the design source picked and which the repo adopted on 2026-09-11 when
     * following the mock replaced the floors (docs/DESIGN.md §7 item 8).
     *
     * Asserted as a range rather than left unchecked, so that the trade stays visible: if a later
     * change drags it under 2.5 the pillar has effectively vanished, and that should be a decision
     * rather than a slip.
     */
    @Test fun warn_graphic_tier_is_below_the_old_floor_on_purpose() {
        val light = contrast(warnGraphicArgb(dark = false), lightSurface)
        assertTrue("graphic amber $light is no longer the design's 3.02:1", light in 2.5..3.5)
        assertTrue(contrast(warnGraphicArgb(dark = true), darkSurface) >= 3.0)
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
