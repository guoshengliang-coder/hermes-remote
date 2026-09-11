package com.hermes.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hermes.client.R

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
// Prose stays on the system family. One family IS bundled now — JetBrains Mono, below.

private val Default = FontFamily.Default

/**
 * JetBrains Mono, the design source's `font-mono` (decision 2026-09-11: follow the mock strictly,
 * which reversed the 2026-09-10 "bundle nothing" position).
 *
 * Where the mock actually sets it, and therefore where this is used: the group-header label, the
 * 「仅此设备」note, the count chip, the session subline's `project · model`, and the running status
 * line only — 「运行失败」and the other verdicts are NOT mono in the mock.
 *
 * Worth knowing before reading a screenshot: JetBrains Mono carries no CJK glyphs, so every
 * Chinese run falls back to the system face exactly as it does in the browser. 「需要你处理」looks
 * unchanged; what visibly becomes monospaced is Latin and digits — repo and model names, counts,
 * tool names. The English UI is where the group headers change.
 *
 * One variable file at three instances rather than three static files: minSdk is 26, which is
 * where variable fonts start, and it saves ~80 KB over shipping Regular/Medium/SemiBold
 * separately. `FontVariation.weight` is what actually moves the axis — declaring the same file
 * three times under different [FontWeight]s would render all three at the default instance.
 *
 * Licence: SIL OFL 1.1, shipped at assets/licenses/JetBrainsMono-OFL.txt.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun mono(weight: FontWeight) = Font(
    R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val HermesMono = FontFamily(
    mono(FontWeight.Normal),
    mono(FontWeight.Medium),
    mono(FontWeight.SemiBold),
)

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
 * The session list row's own steps (docs/DESIGN.md §5.2). Material's ListItem would otherwise hand
 * the first two bodyLarge (16sp) and bodyMedium (14sp), which is what made rows measure 73.5dp
 * against the design's ~65.
 *
 * These are explicit rather than global tokens because the sizes they replace are load-bearing
 * elsewhere: bodyLarge is the chat reading step, bodyMedium is used in 78 places, and labelMedium
 * — which the status line used to borrow — also carries the group headers, whose design role is the
 * opposite setting (wide-tracked uppercase). One token cannot serve both.
 *
 * Line heights are the design's own `leading-[1.45]`, which the row container applies to every step
 * and none of them override: 15.5 × 1.45 = 22.475, 12 × 1.45 = 17.4. Tracking is its `-0.01em` at
 * the step's own size. Both are carried exactly rather than rounded to a whole sp, so the fixture
 * needs no "we rounded it" clause.
 *
 * **Two title tiers, split by UNREAD state** (decision 2026-09-11). The mock's 600 rows all happen
 * to sit in 需要你处理, but the designer's own system names the tiers `session-title-unread` /
 * `session-title-normal`, and that is what the product owner settled on. The unread dot stays;
 * weight is a second signal, not a replacement for it.
 */
val SessionRowTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.5.sp,
    lineHeight = 22.475.sp,
    letterSpacing = (-0.155).sp,
)

/** Read rows: same step, one weight down. See [SessionRowTitle]. */
val SessionRowTitleRead = SessionRowTitle.copy(fontWeight = FontWeight.Medium)

/**
 * `project · model`. Monospaced in the mock, and this is where bundling [HermesMono] actually shows:
 * the content is repo and model names, so almost none of it falls back to the system face.
 */
val SessionRowSubline = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = 0.sp,
)

/**
 * The runtime status line ("正在运行" / "等待你的确认"). The design sets it apart from the subline
 * above it by weight and tracking, not by size — `text-[12px] font-medium tracking-tight`.
 *
 * Sans, not mono: the mock leaves 「运行失败」and the other verdicts on the prose face. Only the
 * running line is monospaced — see [SessionRowStatusRunning].
 */
val SessionRowStatus = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = (-0.3).sp,
)

/**
 * 「正在使用 <top-monitor> 工具…」 — the one status line the mock sets in mono, because the thing
 * worth reading in it is a tool name. The Chinese around it falls back to the system face.
 */
val SessionRowStatusRunning = SessionRowStatus.copy(fontFamily = HermesMono)

/**
 * The group header's label (docs/DESIGN.md §5.2): `font-mono text-[11px] uppercase tracking-wider
 * font-semibold`. Tailwind's `tracking-wider` is 0.05em, so 0.55sp at this size.
 *
 * 11sp is below the 12sp floor this file used to observe. The floor was dropped on 2026-09-11 in
 * favour of following the mock exactly; the Chinese headers fall back to the system face anyway,
 * so what this actually resizes is the English ones.
 */
val SessionGroupHeader = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.55.sp,
)

/** The group header's count chip: `font-mono text-[10.5px]`, 600 on the hot group, 500 elsewhere. */
val SessionGroupCount = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.sp,
)

/**
 * The segmented capsule's label (`ui/components/SegmentedCapsule.kt`): the mock's
 * `text-[13.5px] font-medium tracking-tight`. Sans — the segment labels are prose, not data.
 */
val SegmentLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.5.sp,
    lineHeight = 19.575.sp,
    letterSpacing = (-0.135).sp,
)

/** 「仅此设备」: `font-mono text-[10px] tracking-tight font-normal`. */
val SessionGroupNote = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    lineHeight = 13.sp,
    letterSpacing = (-0.1).sp,
)
