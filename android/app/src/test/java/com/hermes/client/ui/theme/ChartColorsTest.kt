package com.hermes.client.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage chart's three bands are one quantity split three ways. Two invariants (DESIGN.md §2.6):
 *
 *  1. Every band must be visible on the card it is drawn on. The first attempt used a lightest
 *     step of #B4CDF0, which measured 1.57:1 against the light tile — that band carried 37% of
 *     every bar and was effectively invisible.
 *  2. INPUT is the highest-contrast step in BOTH tiers. Lightness order flips between themes;
 *     contrast order must not.
 */
class ChartColorsTest {

    // The tile the chart is drawn on, not the page background (CardPage.kt).
    private val lightTile = 0xFFFAFBFD.toInt()
    private val darkTile = 0xFF20272E.toInt()

    @Test fun values_are_pinned() {
        assertEquals(0xFF00306A.toInt(), chartBandArgb(ChartBand.INPUT, dark = false))
        assertEquals(0xFF0B5FD0.toInt(), chartBandArgb(ChartBand.OUTPUT, dark = false))
        assertEquals(0xFF5C8FDE.toInt(), chartBandArgb(ChartBand.CACHE, dark = false))
        assertEquals(0xFFA9C7FF.toInt(), chartBandArgb(ChartBand.INPUT, dark = true))
        assertEquals(0xFF7793C9.toInt(), chartBandArgb(ChartBand.OUTPUT, dark = true))
        assertEquals(0xFF5B7398.toInt(), chartBandArgb(ChartBand.CACHE, dark = true))
    }

    @Test fun every_band_clears_the_graphic_contrast_floor_on_its_tile() {
        for (band in ChartBand.entries) {
            val light = contrast(chartBandArgb(band, dark = false), lightTile)
            val dark = contrast(chartBandArgb(band, dark = true), darkTile)
            assertTrue("$band light $light < 3.0", light >= 3.0)
            assertTrue("$band dark $dark < 3.0", dark >= 3.0)
        }
    }

    @Test fun input_is_the_highest_contrast_step_in_both_tiers() {
        for (dark in listOf(false, true)) {
            val tile = if (dark) darkTile else lightTile
            val ratios = ChartBand.entries.associateWith { contrast(chartBandArgb(it, dark), tile) }
            val strongest = ratios.maxByOrNull { it.value }!!.key
            assertEquals("dark=$dark ordered $ratios", ChartBand.INPUT, strongest)
        }
    }

    /** Contrast must decrease monotonically INPUT -> OUTPUT -> CACHE, so the stack reads as steps. */
    @Test fun the_three_steps_are_ordered_and_separated() {
        for (dark in listOf(false, true)) {
            val tile = if (dark) darkTile else lightTile
            val input = contrast(chartBandArgb(ChartBand.INPUT, dark), tile)
            val output = contrast(chartBandArgb(ChartBand.OUTPUT, dark), tile)
            val cache = contrast(chartBandArgb(ChartBand.CACHE, dark), tile)
            assertTrue("dark=$dark not ordered: $input $output $cache", input > output && output > cache)
            // Adjacent bands must be distinguishable from each other, not just from the card.
            assertTrue("dark=$dark input/output too close", contrast(
                chartBandArgb(ChartBand.INPUT, dark), chartBandArgb(ChartBand.OUTPUT, dark),
            ) >= 1.5)
            assertTrue("dark=$dark output/cache too close", contrast(
                chartBandArgb(ChartBand.OUTPUT, dark), chartBandArgb(ChartBand.CACHE, dark),
            ) >= 1.5)
        }
    }

    /** §2.6: the chart must not reuse tertiary. The old chart drew "output" in coral. */
    @Test fun no_band_is_the_coral_tertiary() {
        val coralLight = 0xFFB9482C.toInt()
        val coralDark = 0xFFFFB59F.toInt()
        for (band in ChartBand.entries) {
            assertTrue(chartBandArgb(band, dark = false) != coralLight)
            assertTrue(chartBandArgb(band, dark = true) != coralDark)
        }
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
