package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Curated brand palette. Per-profile accent (see ProfileAccent.kt) tints the chrome on top
// of these neutral base schemes; the base stays calm so chat content reads cleanly and so
// Material You (opt-in) can slot in as an alternative neutral base without a redesign.

// Hermes Remote brand — the blue lifted straight off the launcher icon, with a small coral
// highlight. The icon's faceted H is red/yellow/green/blue; its two blue facets sit at hue
// 213-215 (#1F84FD bright, #005EE3 deep). Blue50 below is that deep facet pushed further down so
// white clears AA on warm paper (7.51:1); the old Blue40 #0B5FD0 lives on as the chart OUTPUT
// step in ChartColors.kt, which keeps its own literal.
//
// Why blue and not the old mint: the startup screen (StartupScreen.kt) was ALREADY blue —
// icon-derived progress colours over a blue wash — so the app used to change colour between
// the splash and the first frame. Mint also collided with status: primary was simultaneously
// the brand and the "completed" state, while StatusDot carried a second, different green.
// Blue chrome resolves both; the status palette now lives in StatusColors.kt.
private val Blue20 = Color(0xFF00306A)
private val Blue30 = Color(0xFF00458F)
private val Blue45 = Color(0xFF2563EB)   // primaryContainer — the design source's Royal Cobalt
private val Blue50 = Color(0xFF004AC6)   // primary on warm paper, 7.13:1
private val Blue80 = Color(0xFFA9C7FF)
private val Blue90 = Color(0xFFD6E3FF)

// Amber replaces the old coral tertiary (DESIGN.md §2.1). Coral was read as a warning tint in
// a blue UI; on warm paper it also collided with the ground itself.
private val Amber40 = Color(0xFF824500)
private val Amber50 = Color(0xFFA65900)
private val Amber80 = Color(0xFFFFB77D)

// Neutrals: WARM paper — ground AND greys. The design source shipped cool-cast greys
// (#434655 / #737686 / #C3C6D7) on its warm paper; that is the same temperature mismatch §2.3 was
// written about, just mirrored, so the whole neutral family is re-derived warm here (decision
// 2026-09-10). Every replacement holds its predecessor's contrast or beats it.
// Not the cool whites this file carried until the 2026-09-10 re-skin
// (DESIGN.md §2.1). The 0.1.61 note that warm whites "read yellow" applied to a warm CARD on a
// cool sheet; here the whole neutral family turns warm together and white is promoted to the
// raised layer, so the mismatch that argument was about cannot occur.
// Error family. Left unset until 0.1.89, so both schemes silently inherited the Material 3
// baseline — the same class of omission §2.5 fixed for the surface container family. The values
// below are now explicit and pinned by ErrorColorsTest. Contrast: #BA1A1A on white 6.46:1,
// #FFB4AB on the dark surface 10.42:1 — both clear the 4.5:1 body-text floor in DESIGN.md §5.7.
private val ErrorLight = Color(0xFFBA1A1A)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

private val LightBackground = Color(0xFFFAF9F5)
// Rows sit directly on paper now; pure white moved to surfaceContainerLowest (§2.1).
private val LightSurface = Color(0xFFFAF9F5)
private val LightSurfaceVariant = Color(0xFFE3E2DF)
// 4.54:1 on paper. Deep enough to carry TEXT, not just strokes: the session subline uses it as
// the design's "muted" tier, which sits one step lighter than onSurfaceVariant.
private val LightOutline = Color(0xFF777268)

// Lifted off pure dark: pure-dark reads harsh on OLED for long night reading.
private val DarkBackground = Color(0xFF0F1217)
private val DarkSurface = Color(0xFF0F1217)
private val DarkSurfaceVariant = Color(0xFF262C35)
private val DarkOutline = Color(0xFF8D897E)

// M3 surface-container family, cool-tinted at the SAME hue as the neutrals above. These MUST
// be defined explicitly: any token left out of lightColorScheme()/darkColorScheme() falls back
// to Material 3's purple-tinted baseline — which is exactly the off-brand reddish cast bottom
// sheets, menus, and dialogs used to show (ModalBottomSheet defaults to surfaceContainerLow).
// Registered in docs/DESIGN.md §2.
private val LightSurfaceDim = Color(0xFFDBDAD6)
private val LightSurfaceBright = Color(0xFFFAF9F5)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF4F4F0)
private val LightSurfaceContainer = Color(0xFFEFEEEA)
private val LightSurfaceContainerHigh = Color(0xFFE9E8E4)
private val LightSurfaceContainerHighest = Color(0xFFE3E2DF)

private val DarkSurfaceDim = Color(0xFF0F1217)
private val DarkSurfaceBright = Color(0xFF2E343D)
private val DarkSurfaceContainerLowest = Color(0xFF0A0D11)
private val DarkSurfaceContainerLow = Color(0xFF161A22)
private val DarkSurfaceContainer = Color(0xFF1A1F27)
private val DarkSurfaceContainerHigh = Color(0xFF1E232B)
private val DarkSurfaceContainerHighest = Color(0xFF262C35)

val HermesLightColors = lightColorScheme(
    primary = Blue50,
    onPrimary = Color.White,
    primaryContainer = Blue45,
    onPrimaryContainer = Color(0xFFEEEFFF),
    secondary = Color(0xFF605C54),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E1DA),
    onSecondaryContainer = Color(0xFF605C54),
    // Tertiary is amber now, not coral: it is what runtimeColor() paints "waiting on you" with,
    // and amber is what that state means in DESIGN.md §5.2 and in the design source.
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber50,
    onTertiaryContainer = Color(0xFFFFEDE1),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = LightBackground,
    onBackground = Color(0xFF1B1C1A),
    surface = LightSurface,
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF494641),
    outline = LightOutline,
    outlineVariant = Color(0xFFC9C7C2),
    // Tonal elevation tints toward THIS, and it defaults to primary when left out. On the old
    // cool scheme a blue tint over a white surface was invisible; on warm paper it turned every
    // tonally-raised surface into a cold blue-grey patch (the chat composer measured #EDF0F3
    // against a #FAF9F5 page). Raised means "closer to white" here — DESIGN.md §2.3.
    surfaceTint = Color(0xFFFFFFFF),
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

val HermesDarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = Color(0xFFC8C5BD),
    onSecondary = Color(0xFF32302A),
    secondaryContainer = Color(0xFF494640),
    onSecondaryContainer = Color(0xFFE4E1DA),
    tertiary = Amber80,
    onTertiary = Color(0xFF4A2400),
    tertiaryContainer = Color(0xFF6E3900),
    onTertiaryContainer = Color(0xFFFFDCC3),
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = DarkBackground,
    onBackground = Color(0xFFE2E0DB),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E0DB),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA8A49C),
    outline = DarkOutline,
    outlineVariant = Color(0xFF423F3A),
    // Dark has no usable shadows (§2.3), so tonal elevation is the only lift a raised surface
    // gets — tint toward onSurface so it reads lighter, never toward the brand blue.
    surfaceTint = Color(0xFFE2E0DB),
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)
