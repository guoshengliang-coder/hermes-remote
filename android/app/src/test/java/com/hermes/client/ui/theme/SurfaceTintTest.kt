package com.hermes.client.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `surfaceTint` is what Material 3 blends in for `tonalElevation`, and it falls back to `primary`
 * when a scheme leaves it out — the same class of omission DESIGN.md §2.5 already recorded for the
 * surface-container family.
 *
 * It went unnoticed for as long as the app was cool-toned: a blue tint over a white `surface` is
 * invisible. The 2026-09-10 warm re-skin made it visible in one step — the chat composer
 * (`Surface(color = surface, tonalElevation = 1.dp)`) rendered a cold blue-grey `#EDF0F3` patch on
 * a `#FAF9F5` page. These assertions fail on the old behaviour.
 */
class SurfaceTintTest {

    @Test fun values_are_pinned() {
        assertEquals(0xFFFFFFFF.toInt(), HermesLightColors.surfaceTint.toArgb())
        assertEquals(0xFFE2E0DB.toInt(), HermesDarkColors.surfaceTint.toArgb())
    }

    /** The regression itself: an undefined surfaceTint resolves to primary. */
    @Test fun surface_tint_is_never_the_brand_colour() {
        assertNotEquals(HermesLightColors.primary.toArgb(), HermesLightColors.surfaceTint.toArgb())
        assertNotEquals(HermesDarkColors.primary.toArgb(), HermesDarkColors.surfaceTint.toArgb())
    }

    /**
     * Raised must read as raised. A tonally-lifted surface has to move AWAY from the ground, and in
     * both themes this app's ground is the darker end in dark and the near-white end in light — so
     * in both cases the tint must be lighter than the surface it lifts.
     */
    @Test fun surface_tint_lifts_towards_light_in_both_themes() {
        for ((name, tint, surface) in listOf(
            Triple("light", HermesLightColors.surfaceTint.toArgb(), HermesLightColors.surface.toArgb()),
            Triple("dark", HermesDarkColors.surfaceTint.toArgb(), HermesDarkColors.surface.toArgb()),
        )) {
            assertTrue("$name tint is not lighter than its surface", luminance(tint) > luminance(surface))
        }
    }

    /**
     * The tint is neutral, not chromatic: a raised container must not pick up a hue the page does
     * not have. Anything past a few points of channel spread would put colour back in.
     */
    @Test fun surface_tint_is_neutral() {
        for ((name, argb) in listOf(
            "light" to HermesLightColors.surfaceTint.toArgb(),
            "dark" to HermesDarkColors.surfaceTint.toArgb(),
        )) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            assertTrue("$name tint spread $spread is chromatic", spread <= 12)
        }
    }

    private fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin((argb shr 16) and 0xFF) +
            0.7152 * lin((argb shr 8) and 0xFF) +
            0.0722 * lin(argb and 0xFF)
    }
}
