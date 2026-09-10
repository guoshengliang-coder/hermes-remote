package com.hermes.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale aligned to the Stitch design system "Hermes Mobile System" (docs/DESIGN.md §3.1,
// decision 2026-09-10).
//
// Aligned BY SIZE, not by token name. The design system's names do not map onto Material 3's
// slots at the same sizes — its `body-sm` is 13px where M3's bodySmall is 12sp, its `label-lg` is
// 13px where M3's labelLarge is 14sp — so aligning by name would have resized ~70 bodySmall call
// sites and ~22 labelLarge ones for no reason anybody asked for. Every slot below therefore KEEPS
// the size it already rendered at, and adopts the design's weight / line-height / tracking.
//
// What actually changed, and why it is visible:
//   * Tracking flips direction at 16sp and above. The design tightens (−0.01em … −0.02em); this
//     file used to loosen (+0.1sp, +0.15sp, +0.2sp). Same sentence, denser setting.
//   * Weight goes 500 → 600 on titleMedium, labelLarge and labelMedium. The design separates
//     hierarchy by weight where this file separated it by size alone.
//   * bodySmall and labelSmall are now DEFINED. They were left out and silently inherited the
//     Material 3 baseline — the same class of omission that let `surfaceTint` fall back to primary
//     and paint every raised surface blue (§2.5). Their values below match what the baseline was
//     already producing, so nothing moves; they are pinned so nothing can move later either.
//
// Deliberately NOT taken from the design system:
//   * bodyLarge stays 16sp. The design's reading step is 15px, but bodyLarge is the chat message
//     body — the app's primary reading surface — and shrinking it is a legibility decision, not a
//     re-skin detail. The session list gets its 15sp title from an explicit style instead (§5.2).
//   * The design's 18px step has no consumer here and no M3 slot between titleLarge (20) and
//     titleMedium (16); putting it in headlineMedium would leave that slot smaller than
//     headlineSmall. Recorded in §3.1, not materialised.
//
// Still the system family: no font is bundled (there is no res/font/). Bundling Inter was
// considered and deferred — Inter carries no CJK glyphs, so it would change Latin and digits only
// (§3.1). Call sites need not change if that decision is revisited.

private val Default = FontFamily.Default

val HermesTypography = Typography(
    // 24sp — design display-sm 24/32/600/−0.02em
    headlineSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.48).sp,
    ),
    // 20sp — design headline-md 20/28/600/−0.015em
    titleLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    // 16sp — design title-lg 16/22/600/−0.01em. Was Medium with +0.1sp: the weight AND the
    // direction of the tracking both change here, which is why this is the most visible slot.
    titleMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.16).sp,
    ),
    // 13sp — design label-lg 13/16/600/+0.02em
    titleSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.26.sp,
    ),
    // 16sp reading step — kept at 16 on purpose (see header). Tracking drops to the design's 0.
    bodyLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    // 14sp — design body-md 14/20/400/0
    bodyMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // 12sp — pinned at the value the M3 baseline was already giving it, so ~70 call sites do not
    // move. Only the fallback disappears.
    bodySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // 14sp — button text. Weight follows the design's label discipline (600), size does not move.
    labelLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.28.sp,
    ),
    // 12sp — design label-md 12/16/600/+0.04em. Group headers live here (§5.2).
    labelMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.48.sp,
    ),
    // 11sp — design label-sm 11/14/500/+0.02em. Also previously an undefined fallback.
    labelSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.22.sp,
    ),
)

/**
 * The session list row's own three steps (docs/DESIGN.md §5.2). The design puts the title at 15px,
 * the subline at 12px and the status line at 12px/500; Material's ListItem would otherwise hand the
 * first two bodyLarge (16sp) and bodyMedium (14sp), which is what made rows measure 73.5dp against
 * the design's ~65.
 *
 * These are explicit rather than global tokens because the sizes they replace are load-bearing
 * elsewhere: bodyLarge is the chat reading step, bodyMedium is used in 78 places, and labelMedium
 * — which the status line used to borrow — also carries the group headers, whose design role is the
 * opposite setting (wide-tracked uppercase). One token cannot serve both.
 *
 * Line heights are the design's own `leading-[1.45]`, which the row container applies to all three
 * and none of them override: 15 × 1.45 = 21.75, 12 × 1.45 = 17.4. They are carried exactly rather
 * than rounded to a whole sp, so the fixture row needs no "we rounded it" clause.
 *
 * Where the design *screen* and the design *system* disagree, the screen wins (decision
 * 2026-09-11): the system's `title-md` token says 15px/500/0em, the screen's row title renders
 * 15px/600/−0.01em. Both are pinned by DesignConformanceTest so the choice cannot silently flip.
 */
val SessionRowTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 21.75.sp,
    letterSpacing = (-0.15).sp,
)

val SessionRowSubline = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = 0.sp,
)

/**
 * The runtime status line ("正在运行" / "等待你的确认"). The design sets it apart from the subline
 * above it by weight and tracking, not by size — `text-[12px] font-medium tracking-tight`.
 */
val SessionRowStatus = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = (-0.3).sp,
)
