package com.hermes.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The "faint card" language (DESIGN.md §2.3), extracted from CardPage.kt now that the usage page
// needs the same containers. It was flagged there as a pending item precisely to avoid the second
// copy of these literals that this file replaces.
//
// The card outline is carried by a WHISPER of fill difference plus a whisper of shadow, in both
// themes — dark lifts the surface one small step rather than jumping to the heavy surfaceVariant.
//
// The light literals are cool whites on purpose: the original #FAFAF8 / #ECECEA were warm and read
// yellow once the sheet around them turned cool with the icon-blue swap.

/** True when the EFFECTIVE theme is dark. Never `isSystemInDarkTheme()` — DESIGN.md §2.2. */
@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Faint card fill. */
@Composable
fun tileColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.06f)
    else Color(0xFFFAFBFD)

/** Dark shadows are invisible, so the dark tier carries the card on fill alone. */
@Composable
fun tileShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

/** Divider inside a faint card — one step stronger than the fill, never `outlineVariant`. */
@Composable
fun hairlineColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.14f)
    else Color(0xFFEBEDF2)
