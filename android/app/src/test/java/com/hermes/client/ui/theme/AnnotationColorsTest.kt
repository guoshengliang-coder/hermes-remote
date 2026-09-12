package com.hermes.client.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationColorsTest {

    @Test
    fun everyInkValueIsPinned() {
        assertEquals(0xFFEF4444.toInt(), inkArgb(InkColor.RED))
        assertEquals(0xFFF59E0B.toInt(), inkArgb(InkColor.AMBER))
        assertEquals(0xFF22C55E.toInt(), inkArgb(InkColor.GREEN))
        assertEquals(0xFF2563EB.toInt(), inkArgb(InkColor.BLUE))
        assertEquals(0xFFFFFFFF.toInt(), inkArgb(InkColor.WHITE))
        assertEquals(0xFF111111.toInt(), inkArgb(InkColor.INK))
    }

    @Test
    fun thePaletteIsTheWholeEnumInDeclarationOrderWithRedFirst() {
        assertEquals(InkColor.entries.toList(), InkPalette)
        assertEquals(InkColor.RED, InkPalette.first())
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * The escapes are the point of the palette. A red circle on a dark-red screenshot is invisible
     * and no choice of hue fixes it — only one near-white and one near-black member do. Removing
     * either is what this guards against.
     */
    @Test
    fun thePaletteKeepsBothALightAndADarkEscape() {
        val luminances = InkPalette.map { relativeLuminance(inkArgb(it)) }

        assertTrue("needs a near-white escape", luminances.any { it > 0.8 })
        assertTrue("needs a near-black escape", luminances.any { it < 0.05 })
    }

    /**
     * Blue is primaryContainer, not light primary #004AC6, which all but vanishes on a dark
     * screenshot. Pinned separately because "use the brand blue" is the obvious wrong fix.
     */
    @Test
    fun blueIsTheLegibleBrandStepNotLightPrimary() {
        assertTrue(inkArgb(InkColor.BLUE) != 0xFF004AC6.toInt())
        assertTrue(relativeLuminance(inkArgb(InkColor.BLUE)) > relativeLuminance(0xFF004AC6.toInt()))
    }
}
