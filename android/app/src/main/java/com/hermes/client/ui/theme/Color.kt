package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Curated brand palette. Per-profile accent (see ProfileAccent.kt) tints the chrome on top
// of these neutral base schemes; the base stays calm so chat content reads cleanly and so
// Material You (opt-in) can slot in as an alternative neutral base without a redesign.

// Hermes Remote brand — the blue lifted straight off the launcher icon, with a small coral
// highlight. The icon's faceted H is red/yellow/green/blue; its two blue facets sit at hue
// 213-215 (#1F84FD bright, #005EE3 deep), and Blue40 below is that deep facet held at the same
// hue but pushed one step darker so white clears AA on it (5.90:1 vs the facet's 5.66:1).
//
// Why blue and not the old mint: the startup screen (StartupScreen.kt) was ALREADY blue —
// icon-derived progress colours over a blue wash — so the app used to change colour between
// the splash and the first frame. Mint also collided with status: primary was simultaneously
// the brand and the "completed" state, while StatusDot carried a second, different green.
// Blue chrome resolves both; the status palette now lives in StatusColors.kt.
private val Blue10 = Color(0xFF001B3D)
private val Blue20 = Color(0xFF00306A)
private val Blue30 = Color(0xFF00458F)
private val Blue40 = Color(0xFF0B5FD0)
private val Blue80 = Color(0xFFA9C7FF)
private val Blue90 = Color(0xFFD6E3FF)

private val Coral40 = Color(0xFFB9482C)
private val Coral80 = Color(0xFFFFB59F)
private val Coral90 = Color(0xFFFFDBD0)

// Neutrals tuned for comfortable long-form reading. Cool-tinted to sit under the blue —
// the old mint-tinted neutrals turn muddy the moment a blue primary lands on them.
private val LightBackground = Color(0xFFF7F9FD)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE7ECF6)
private val LightOutline = Color(0xFF74777F)

// Lifted off pure dark: pure-dark reads harsh on OLED for long night reading.
private val DarkBackground = Color(0xFF131A22)
private val DarkSurface = Color(0xFF121921)
private val DarkSurfaceVariant = Color(0xFF273039)
private val DarkOutline = Color(0xFF8E9099)

val HermesLightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Color(0xFF3B0A00),
    background = LightBackground,
    onBackground = Color(0xFF1A1C20),
    surface = LightSurface,
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF43474E),
    outline = LightOutline,
    outlineVariant = Color(0xFFC4C6D0),
)

val HermesDarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Coral80,
    onTertiary = Color(0xFF5D1907),
    tertiaryContainer = Color(0xFF7D2E17),
    onTertiaryContainer = Coral90,
    background = DarkBackground,
    onBackground = Color(0xFFE1E3E9),
    surface = DarkSurface,
    onSurface = Color(0xFFE1E3E9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = DarkOutline,
    outlineVariant = Color(0xFF43474E),
)
