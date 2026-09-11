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
internal val FabContainerDark = Color(0xFF181C24)

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

/**
 * The run spinner (docs/DESIGN.md §5.2). Blue — NOT the cyan that `StatusTone.RUNNING` paints the
 * status text with. The design source deliberately splits them: the words say what is happening,
 * the spinner just says something is, and keeping the spinner on the brand blue stops a small
 * moving object from competing with the sentence next to it.
 *
 * Paired with [SpinnerTrackAlpha]: the design draws a full circle at 25% under the arc, so the
 * indicator reads as a ring with a lit segment rather than a lone arc chasing its tail. Material's
 * default indeterminate indicator has no track at all, which is what shipped by mistake.
 */
internal val SpinnerLight = Color(0xFF2563EB)
internal val SpinnerDark = Color(0xFF3B82F6)

/** The design's `opacity-25` track under the moving arc. */
const val SpinnerTrackAlpha = 0.25f

@Composable
fun spinnerColor(): Color = if (isDarkSurface()) SpinnerDark else SpinnerLight

/**
 * The incident banner's fill and ink (docs/DESIGN.md §5.2). Deliberately NOT `errorContainer`:
 * that one belongs to genuine errors — a failed delete, a broken dialog — and painting a standing
 * "Slack is disconnected" notice in the same red both over-states the incident and wears out the
 * colour that destructive confirmations depend on. The design source gives this its own softer
 * rose, and these are its values.
 *
 * Worth watching: the light fill sits only 1.04:1 from the paper, so the card reads by HUE rather
 * than by lightness. That is the design's intent, but it is quieter than the errorContainer it
 * replaces (1.23:1) — if it disappears on a real screen, deepen the fill rather than reaching back
 * for errorContainer.
 */
internal val IncidentContainerLight = Color(0xFFFFF1F2)
internal val IncidentContainerDark = Color(0xFF2A1B1D)
internal val OnIncidentLight = Color(0xFF9F1239)
internal val OnIncidentDark = Color(0xFFFCA5A5)

@Composable
fun incidentContainerColor(): Color =
    if (isDarkSurface()) IncidentContainerDark else IncidentContainerLight

/** Ink on [incidentContainerColor]: 7.30:1 light, 8.69:1 dark. */
@Composable
fun onIncidentColor(): Color =
    if (isDarkSurface()) OnIncidentDark else OnIncidentLight

/** Dark shadows are invisible, so the dark tier carries the card on fill alone. */
/**
 * The session subline's faintest tier — the folder glyph and the 「仅此设备」note
 * (docs/DESIGN.md §5.2). The design source's `#A8A29E`, which is 2.39:1 on warm paper: below both
 * the 4.5:1 text floor and the 3:1 graphic one. Adopted anyway on 2026-09-11, when following the
 * mock exactly replaced the floors (§7 item 8). If it disappears on a real screen, that is the
 * trade being paid, not a bug.
 */
internal val SublineFaintLight = Color(0xFFA8A29E)
internal val SublineFaintDark = Color(0xFF64615B)

@Composable
fun sublineFaintColor(): Color = if (isDarkSurface()) SublineFaintDark else SublineFaintLight

@Composable
fun tileShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

/** Divider inside a faint card — one step stronger than the fill, never `outlineVariant`. */
@Composable
fun hairlineColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.14f)
    else Color(0xFFEFEEEA) // surfaceContainer — one warm step past the card fill
