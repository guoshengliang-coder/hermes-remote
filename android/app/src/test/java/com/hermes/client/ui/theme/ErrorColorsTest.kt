package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error family was the last colour group left unset in both schemes, so it silently inherited
 * the Material 3 baseline — the same omission §2.5 of DESIGN.md called out for the surface
 * container family. Nothing was visibly broken, which is exactly why it needs pinning: an
 * inherited default is not a decision, and the next Material update can move it underneath us.
 */
class ErrorColorsTest {

    private val lightSurface = 0xFFFFFFFF.toInt()
    private val darkSurface = 0xFF121921.toInt()

    @Test fun values_are_pinned() {
        assertEquals(0xFFBA1A1A.toInt(), HermesLightColors.error.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), HermesLightColors.onError.toArgb())
        assertEquals(0xFFFFDAD6.toInt(), HermesLightColors.errorContainer.toArgb())
        assertEquals(0xFF410002.toInt(), HermesLightColors.onErrorContainer.toArgb())
        assertEquals(0xFFFFB4AB.toInt(), HermesDarkColors.error.toArgb())
        assertEquals(0xFF690005.toInt(), HermesDarkColors.onError.toArgb())
        assertEquals(0xFF93000A.toInt(), HermesDarkColors.errorContainer.toArgb())
        assertEquals(0xFFFFDAD6.toInt(), HermesDarkColors.onErrorContainer.toArgb())
    }

    /**
     * The point of the change: neither scheme may resolve to whatever Material happens to ship.
     * Compare against a bare baseline scheme rather than a hard-coded literal, so this keeps
     * holding if the baseline moves.
     */
    @Test fun neither_scheme_inherits_the_material_baseline() {
        val baselineLight = lightColorScheme()
        val baselineDark = darkColorScheme()
        assertNotEquals(baselineLight.error.toArgb(), HermesLightColors.error.toArgb())
        assertNotEquals(baselineLight.errorContainer.toArgb(), HermesLightColors.errorContainer.toArgb())
        assertNotEquals(baselineDark.error.toArgb(), HermesDarkColors.error.toArgb())
        assertNotEquals(baselineDark.errorContainer.toArgb(), HermesDarkColors.errorContainer.toArgb())
    }

    /** Error copy renders as body text (`ErrorState`, the startup failure summary). */
    @Test fun error_clears_aa_text_contrast_on_its_own_surface() {
        val light = contrast(HermesLightColors.error.toArgb(), lightSurface)
        val dark = contrast(HermesDarkColors.error.toArgb(), darkSurface)
        assertTrue("light $light < 4.5", light >= 4.5)
        assertTrue("dark $dark < 4.5", dark >= 4.5)
    }

    /** Containers carry text in their own on-colour, so that pair owes AA too. */
    @Test fun container_pairs_clear_aa_text_contrast() {
        val light = contrast(
            HermesLightColors.onErrorContainer.toArgb(),
            HermesLightColors.errorContainer.toArgb(),
        )
        val dark = contrast(
            HermesDarkColors.onErrorContainer.toArgb(),
            HermesDarkColors.errorContainer.toArgb(),
        )
        assertTrue("light container $light < 4.5", light >= 4.5)
        assertTrue("dark container $dark < 4.5", dark >= 4.5)
    }

    /**
     * Error is chrome, the status palette is meaning (DESIGN.md §2.1). They may look alike in the
     * dark tier — both are the same Material tone — but the light tier must not drift into
     * StatusColors' BAD, or a failure banner and a "failed" status label become indistinguishable
     * decisions made in two places.
     */
    @Test fun light_error_is_not_the_status_bad_colour() {
        assertNotEquals(statusArgb(StatusTone.BAD, dark = false), HermesLightColors.error.toArgb())
    }

    @Test fun on_error_is_legible_on_error() {
        assertTrue(contrast(Color.White.toArgb(), HermesLightColors.error.toArgb()) >= 4.5)
        assertTrue(contrast(HermesDarkColors.onError.toArgb(), HermesDarkColors.error.toArgb()) >= 4.5)
    }

    // --- WCAG math, test-side only (same convention as StatusColorsTest) ---
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
