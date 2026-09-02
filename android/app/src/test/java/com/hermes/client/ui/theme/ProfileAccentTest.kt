package com.hermes.client.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAccentTest {

    private val profiles = listOf("default", "personal", "odos", "semiotic", "dito")

    @Test fun hue_is_deterministic() {
        assertEquals(profileHue("personal"), profileHue("personal"), 0f)
        assertEquals(profileHue("odos"), profileHue("odos"), 0f)
    }

    @Test fun hue_is_in_range() {
        for (p in profiles) {
            val h = profileHue(p)
            assertTrue("$p hue in [0,360): $h", h >= 0f && h < 360f)
        }
    }

    // The whole point of the avatar colour is that tenants look different. Require the real
    // profiles to land on visibly distinct hues (>= 20° apart pairwise).
    @Test fun real_profiles_get_distinct_hues() {
        val hues = profiles.map { it to profileHue(it) }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val (na, a) = hues[i]
            val (nb, b) = hues[j]
            val delta = minOf(Math.abs(a - b), 360f - Math.abs(a - b))
            assertTrue("$na($a) vs $nb($b) too close: ${delta}°", delta >= 20f)
        }
    }

    @Test fun avatar_color_is_deterministic_and_null_safe() {
        assertEquals(avatarColorArgb("odos"), avatarColorArgb("odos"))
        // Null/blank falls back to the brand hue rather than crashing.
        assertEquals(avatarColorArgb(null), avatarColorArgb(""))
    }

    // The avatar always renders WHITE text on the solid hashed colour. This must hold for EVERY
    // possible hue, not just today's tenant names — the fixed lightness (0.32) is the guarantee,
    // and this test is what pins it: white must clear WCAG AA-large (3.0:1) on all 360 hues.
    @Test fun white_text_clears_aa_large_on_every_hue() {
        val white = 0xFFFFFFFF.toInt()
        for (hue in 0 until 360) {
            val argb = hslToArgb(hue.toFloat(), 0.62f, 0.32f)
            val ratio = contrastRatioForTest(argb, white)
            assertTrue("hue $hue: white contrast $ratio < 3.0", ratio >= 3.0)
        }
    }

    // The OUTLINE style draws the identity colour as a ring + initial ON the surface. Light keeps
    // l=0.32 (≥ 3.9:1 on white, above); dark must lift it — the raw 0.32 colour is ~1.6–3.2:1
    // against #121921. Pin the lifted lightness at ≥ 4.5:1 (AA normal text) for EVERY hue and for
    // the neutral swatches.
    @Test fun outline_colour_on_dark_surface_clears_aa_on_every_hue() {
        val darkSurface = 0xFF121921.toInt()
        for (hue in 0 until 360) {
            val lifted = avatarOutlineColorArgb(hslToArgb(hue.toFloat(), 0.62f, 0.32f), dark = true)
            val ratio = contrastRatioForTest(lifted, darkSurface)
            assertTrue("hue $hue: outline contrast $ratio < 4.5", ratio >= 4.5)
        }
        for (swatch in AVATAR_SWATCHES) {
            val ratio = contrastRatioForTest(avatarOutlineColorArgb(swatch, dark = true), darkSurface)
            assertTrue("swatch ${Integer.toHexString(swatch)}: outline contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test fun outline_colour_is_unchanged_on_light_surfaces() {
        val argb = hslToArgb(214f, 0.62f, 0.32f)
        assertEquals(argb, avatarOutlineColorArgb(argb, dark = false))
    }

    // The hue slider round-trips: colour → hue → colour lands on the same 8-bit colour, and the
    // recovered hue sits within a degree of the one picked.
    @Test fun hue_slider_round_trips_through_the_avatar_colour() {
        for (hue in 0 until 360 step 5) {
            val argb = avatarColorForHue(hue.toFloat())
            val back = avatarHueOf(argb)
            val delta = minOf(Math.abs(back - hue), 360f - Math.abs(back - hue))
            assertTrue("hue $hue came back as $back", delta <= 1f)
            assertEquals(argb, avatarColorForHue(back))
        }
    }

    // Notifications read the same colour the avatar shows: a custom pick when present, else the
    // hash. The snapshot is process-global, so restore it.
    @Test fun notification_accent_honours_a_custom_colour_then_falls_back_to_the_hash() {
        try {
            ProfileIdentitySnapshot.identities = mapOf("work" to com.hermes.client.data.repository.ProfileIdentity(colorArgb = 0xFF1F4B84.toInt()))
            assertEquals(0xFF1F4B84.toInt(), avatarAccentArgb("work"))
            assertEquals(avatarColorArgb("odos"), avatarAccentArgb("odos"))
        } finally {
            ProfileIdentitySnapshot.identities = emptyMap()
        }
    }

    // --- Minimal WCAG math kept test-side only (the production adaptive-on-colour machinery
    // was deleted with the chrome-tinting feature; the invariant it guaranteed lives here). ---
    private fun contrastRatioForTest(a: Int, b: Int): Double {
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
