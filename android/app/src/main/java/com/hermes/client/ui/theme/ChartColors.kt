package com.hermes.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Chart palette for the usage page (DESIGN.md §2.6).
//
// The three bands are one quantity split three ways, not three categories, so they are three
// LIGHTNESS steps of one blue hue rather than three hues. Three hues would read as three separate
// things and would collide with the status palette's meaning.
//
// The ordering rule is CONTRAST, not lightness: INPUT is always the highest-contrast step, which
// means darkest in the light tier and BRIGHTEST in the dark tier. Copying "darkest = input" into
// dark mode would make the dominant band nearly vanish.
//
// A near-white card cannot carry three steps that are each >= 3:1 against it AND clearly apart
// from each other unless the deepest step goes all the way down to Blue20 — hence #00306A rather
// than something near Blue40. Every value is pinned by ChartColorsTest.
//
// The math is pure (ARGB Int in/out) so it unit-tests without Compose — same reason as
// StatusColors.kt and ProfileAccent.kt.

/** One band of the stacked usage chart. */
enum class ChartBand { INPUT, OUTPUT, CACHE }

private val INPUT_LIGHT = 0xFF00306A.toInt() // 12.5:1 on the light tile (#FAFBFD) — Blue20
private val OUTPUT_LIGHT = 0xFF0B5FD0.toInt() //  5.7:1 — Blue40, the brand primary
private val CACHE_LIGHT = 0xFF5C8FDE.toInt() //  3.2:1 — the lightest step that still clears 3:1

private val INPUT_DARK = 0xFFA9C7FF.toInt() //  8.9:1 on the dark tile (#20272E) — Blue80
private val OUTPUT_DARK = 0xFF7793C9.toInt() //  4.9:1
private val CACHE_DARK = 0xFF5B7398.toInt() //  3.1:1

fun chartBandArgb(band: ChartBand, dark: Boolean): Int = when (band) {
    ChartBand.INPUT -> if (dark) INPUT_DARK else INPUT_LIGHT
    ChartBand.OUTPUT -> if (dark) OUTPUT_DARK else OUTPUT_LIGHT
    ChartBand.CACHE -> if (dark) CACHE_DARK else CACHE_LIGHT
}

/**
 * Compose-facing wrapper. Dark is decided by the EFFECTIVE theme's surface luminance, never by
 * `isSystemInDarkTheme()` — DESIGN.md §2.2, and the reason the 0.1.56 "dark app, light cards" bug
 * happened. There is deliberately no default parameter here that could reintroduce it.
 */
@Composable
fun chartBandColor(band: ChartBand): Color =
    Color(chartBandArgb(band, MaterialTheme.colorScheme.surface.luminance() < 0.5f))
