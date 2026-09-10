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
// The light tier INVERTS after the 2026-09-10 warm re-skin (DESIGN.md §2.3): the sheet is warm
// paper now, so the card is pure white — brighter than its ground — where it used to be a near-white
// grey one step darker than a white ground. The rule that survived is "card and ground must share a
// temperature", and that is exactly why the direction had to flip when the ground turned warm.

/** True when the EFFECTIVE theme is dark. Never `isSystemInDarkTheme()` — DESIGN.md §2.2. */
@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Faint card fill. */
@Composable
fun tileColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.06f)
    else Color(0xFFFFFFFF) // surfaceContainerLowest — the raised layer on warm paper

/**
 * The create-action FAB fill: a neutral near-black, deliberately NOT the brand colour.
 *
 * DESIGN.md §1 原则3 carves this one exception out of "chrome is always the brand colour", and
 * §2.7 item 1 records the reasoning: the creation entry point has to stay legible as an ACTION
 * next to a list whose every other accent (amber pillar, green dot, blue unread) means a runtime
 * STATE. A blue FAB on this page is one more blue thing among several.
 *
 * A scheme-independent pair, so it lives here rather than in colorScheme. White content clears AA
 * on both tiers (17.07:1 light, 15.24:1 dark) — pinned by FabColorTest.
 */
internal val FabContainerLight = Color(0xFF181C24)
internal val FabContainerDark = Color(0xFF1E232B)

/**
 * Dark needs a hairline; light does not. A near-black fill separates from warm paper on its own
 * (16.2:1) but sits at 1.19:1 against the obsidian ground — the fill alone would be a hole in the
 * page. The design source draws the same conclusion and rings its dark FAB in white at 10%; this
 * is that ring resolved to an opaque token so it can be pinned (1.51:1 on the fill, 1.80:1 on the
 * ground). [Color.Transparent] in light, where the ring would only muddy a shape that already reads.
 */
internal val FabOutlineDark = Color(0xFF3A4049)

@Composable
fun fabContainerColor(): Color = if (isDarkSurface()) FabContainerDark else FabContainerLight

/** The FAB's hairline: an opaque ring in dark, nothing in light. See [FabOutlineDark]. */
@Composable
fun fabOutlineColor(): Color = if (isDarkSurface()) FabOutlineDark else Color.Transparent

/** Dark shadows are invisible, so the dark tier carries the card on fill alone. */
@Composable
fun tileShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

/** Divider inside a faint card — one step stronger than the fill, never `outlineVariant`. */
@Composable
fun hairlineColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.14f)
    else Color(0xFFEFEEEA) // surfaceContainer — one warm step past the card fill
