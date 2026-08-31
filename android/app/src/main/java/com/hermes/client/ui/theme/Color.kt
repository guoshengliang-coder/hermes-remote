package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Curated brand palette. Per-profile accent (see ProfileAccent.kt) tints the chrome on top
// of these neutral base schemes; the base stays calm so chat content reads cleanly and so
// Material You (opt-in) can slot in as an alternative neutral base without a redesign.

// Hermes Remote brand — calm mint for connectivity, with a small coral highlight.
private val Mint10 = Color(0xFF002117)
private val Mint20 = Color(0xFF003829)
private val Mint30 = Color(0xFF00513C)
private val Mint40 = Color(0xFF087A5C)
private val Mint80 = Color(0xFF65DBB2)
private val Mint90 = Color(0xFFB6F2DB)

private val Coral40 = Color(0xFFB9482C)
private val Coral80 = Color(0xFFFFB59F)
private val Coral90 = Color(0xFFFFDBD0)

// Neutrals tuned for comfortable long-form reading.
private val LightBackground = Color(0xFFF6FAF8)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEAF2EF)
private val LightOutline = Color(0xFF6F7975)

// Lifted one notch from 0E1513: pure-dark reads harsh on OLED for long night reading.
private val DarkBackground = Color(0xFF131B17)
private val DarkSurface = Color(0xFF121B18)
private val DarkSurfaceVariant = Color(0xFF263630)
private val DarkOutline = Color(0xFF89938F)

val HermesLightColors = lightColorScheme(
    primary = Mint40,
    onPrimary = Color.White,
    primaryContainer = Mint90,
    onPrimaryContainer = Mint10,
    secondary = Color(0xFF52665F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E8E2),
    onSecondaryContainer = Color(0xFF10201B),
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Color(0xFF3B0A00),
    background = LightBackground,
    onBackground = Color(0xFF17201D),
    surface = LightSurface,
    onSurface = Color(0xFF17201D),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF414A47),
    outline = LightOutline,
    outlineVariant = Color(0xFFC2CCC8),
)

val HermesDarkColors = darkColorScheme(
    primary = Mint80,
    onPrimary = Mint20,
    primaryContainer = Mint30,
    onPrimaryContainer = Mint90,
    secondary = Color(0xFFB7CCC4),
    onSecondary = Color(0xFF22332D),
    secondaryContainer = Color(0xFF394B44),
    onSecondaryContainer = Color(0xFFD3E7DF),
    tertiary = Coral80,
    onTertiary = Color(0xFF5D1907),
    tertiaryContainer = Color(0xFF7D2E17),
    onTertiaryContainer = Coral90,
    background = DarkBackground,
    onBackground = Color(0xFFDDE5E1),
    surface = DarkSurface,
    onSurface = Color(0xFFDDE5E1),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = DarkOutline,
    outlineVariant = Color(0xFF3E4C47),
)
