package com.hermes.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ink palette for the image editor (DESIGN.md §2.8).
//
// This palette is THEME-INDEPENDENT, and that is the point rather than an oversight. The ink is
// drawn on the user's photograph, not on a Hermes surface, so there is no `surface` underneath and
// `surface.luminance()` has nothing to answer about whether a red circle reads on someone else's
// screenshot. §2.2's dark-mode branching has no subject here, so `inkArgb` deliberately takes no
// `dark` parameter. §2.1 names this as the second documented exemption from "no colour defined in
// only one theme", alongside the Glance widget.
//
// WHITE and INK are both members and both have to stay. A red circle on a dark-red screenshot is
// invisible, and no choice of hue fixes that — only a light escape and a dark escape do.
//
// The math is pure (ARGB Int in/out) so it unit-tests without Compose, same as ChartColors.kt and
// StatusColors.kt. Every value is pinned by AnnotationColorsTest.

/** One ink colour in the editor's palette. Ops store this id, never a resolved colour. */
enum class InkColor { RED, AMBER, GREEN, BLUE, WHITE, INK }

private val RED = 0xFFEF4444.toInt() // default — the universal "look here"
private val AMBER = 0xFFF59E0B.toInt() // second emphasis; survives on both light and dark photos
private val GREEN = 0xFF22C55E.toInt() // "this part is right"
private val BLUE = 0xFF2563EB.toInt() // the app's primaryContainer, not light primary #004AC6,
// which disappears against a dark screenshot
private val WHITE = 0xFFFFFFFF.toInt() // escape for dark photos
private val INK = 0xFF111111.toInt() // escape for light photos

fun inkArgb(color: InkColor): Int = when (color) {
    InkColor.RED -> RED
    InkColor.AMBER -> AMBER
    InkColor.GREEN -> GREEN
    InkColor.BLUE -> BLUE
    InkColor.WHITE -> WHITE
    InkColor.INK -> INK
}

/** Compose-facing wrapper. No theme parameter — see the note above. */
@Composable
fun inkColor(color: InkColor): Color = Color(inkArgb(color))

/** The palette, in the order the swatch row shows it. */
val InkPalette: List<InkColor> = InkColor.entries
