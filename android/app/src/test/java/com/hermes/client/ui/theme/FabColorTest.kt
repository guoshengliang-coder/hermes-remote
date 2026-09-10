package com.hermes.client.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new-chat FAB is the one piece of chrome allowed off the brand colour (DESIGN.md §1 原则3
 * exception, reasoning in §2.7 item 1): every other accent on the session list means a runtime
 * state, so the creation action stays a neutral near-black instead of becoming a fifth blue thing.
 *
 * Pinned here because it is a scheme-independent literal pair — nothing in colorScheme would fail
 * if someone "tidied" it back to `primary`.
 */
class FabColorTest {

    @Test fun values_are_pinned() {
        assertEquals(0xFF181C24.toInt(), FabContainerLight.toArgb())
        assertEquals(0xFF1E232B.toInt(), FabContainerDark.toArgb())
    }

    @Test fun fab_is_never_the_brand_colour() {
        assertNotEquals(HermesLightColors.primary.toArgb(), FabContainerLight.toArgb())
        assertNotEquals(HermesDarkColors.primary.toArgb(), FabContainerDark.toArgb())
    }

    /** The icon and the in-place progress ring are both white, in both tiers. */
    @Test fun white_content_clears_aa_on_both_tiers() {
        for ((name, fill) in listOf("light" to FabContainerLight, "dark" to FabContainerDark)) {
            val c = contrast(0xFFFFFFFF.toInt(), fill.toArgb())
            assertTrue("$name FAB white contrast $c < 4.5", c >= 4.5)
        }
    }

    /** Light separates on fill alone: near-black on warm paper. */
    @Test fun light_fab_separates_from_its_page_on_fill_alone() {
        val c = contrast(FabContainerLight.toArgb(), HermesLightColors.surface.toArgb())
        assertTrue("light FAB vs page $c < 3.0", c >= 3.0)
    }

    /**
     * Dark does NOT, and that is the whole reason [FabOutlineDark] exists: a near-black fill on an
     * obsidian ground measures 1.19:1, so the shape has to be drawn by a ring instead. This test
     * states both halves, so removing the ring without revisiting the fill fails here.
     */
    @Test fun dark_fab_needs_its_hairline() {
        val fillOnPage = contrast(FabContainerDark.toArgb(), HermesDarkColors.surface.toArgb())
        assertTrue("dark FAB fill unexpectedly separates ($fillOnPage) — is the ring still needed?", fillOnPage < 1.5)
        assertTrue(contrast(FabOutlineDark.toArgb(), FabContainerDark.toArgb()) >= 1.5)
        assertTrue(contrast(FabOutlineDark.toArgb(), HermesDarkColors.surface.toArgb()) >= 1.5)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        fun lum(argb: Int) = 0.2126 * lin((argb shr 16) and 0xFF) +
            0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
        val (hi, lo) = listOf(lum(a), lum(b)).let { maxOf(it[0], it[1]) to minOf(it[0], it[1]) }
        return (hi + 0.05) / (lo + 0.05)
    }
}
