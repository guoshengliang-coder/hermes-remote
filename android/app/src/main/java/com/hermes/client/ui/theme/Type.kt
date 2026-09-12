package com.hermes.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontStyle
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
 * Line heights come from [SessionRowLeading], the row container's own `leading-[…]`. Tracking is the
 * mock's `-0.01em` at the step's own size.
 *
 * **Two title tiers, split by UNREAD state** (decision 2026-09-11). The mock's 600 rows all happen
 * to sit in 需要你处理, but the designer's own system names the tiers `session-title-unread` /
 * `session-title-normal`, and that is what the product owner settled on. The unread dot stays;
 * weight is a second signal, not a replacement for it.
 */
/**
 * The row container's `leading-[…]`, applied to all three steps and overridden by none of them.
 *
 * It is a named constant rather than three hand-multiplied literals because the mock moves it as one
 * lever — 1.45 in the 4th pull, 1.35 in the 5th — and three literals drift apart the moment someone
 * updates two of them. Every step below is `size × this`, carried exactly and never rounded to a
 * whole sp, so the conformance fixture needs no "we rounded it" clause.
 */
const val SessionRowLeading = 1.35f

/**
 * `size × leading`, rounded to three decimals.
 *
 * The rounding is not cosmetic. `11.5f * 1.35f` is 15.525001 in float, and that tail reaches
 * `design-conformance.json`, which records the number as text and compares it exactly — the
 * fixture would have to say "15.525001" and nobody reading it could tell that from a typo.
 * Three decimals is past anything the mock expresses (its finest step is 0.005em) and past
 * anything a device can render.
 */
internal fun rowLeading(sizeSp: Float): androidx.compose.ui.unit.TextUnit =
    (kotlin.math.round(sizeSp * SessionRowLeading * 1000f) / 1000f).sp

/**
 * Makes a line box exactly `lineHeight` tall, the way CSS `line-height` does.
 *
 * Without this the row's arithmetic does not survive contact with Compose. The default
 * [LineHeightStyle] trims the leading above the first line and below the last, so a SINGLE-line
 * `Text` — which every step in a session row is — ends up as tall as the font's own ascent plus
 * descent and ignores `lineHeight` entirely. Measured: the two-line row came out 43.05dp against
 * the mock's 49.1dp, and the gap was not constant between row shapes, which is what gives it away
 * as a per-line effect rather than a missing padding.
 *
 * `Trim.None` keeps the leading; `Alignment.Center` splits it evenly above and below, which is
 * where CSS puts half-leading too. `includeFontPadding = false` is set alongside so the box is the
 * line box and nothing else — with it on, Android adds the font's own recommended padding on top.
 *
 * (An older note on [SessionRowSubline] says these were tried and changed nothing. That was true
 * then: the row was a Material `ListItem` and its 72dp floor was taller than the text either way,
 * so nothing could move. The row sets its own height now, and these are load-bearing.)
 */
internal val ExactLineBox = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

val SessionRowTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.5.sp,
    lineHeight = rowLeading(14.5f),
    letterSpacing = (-0.145).sp,
).merge(ExactLineBox)

/** Read rows: same step, one weight down. See [SessionRowTitle]. */
val SessionRowTitleRead = SessionRowTitle.copy(fontWeight = FontWeight.Medium)

/**
 * `project · model`. Monospaced in the mock, and this is where bundling [HermesMono] actually shows:
 * the content is repo and model names, so almost none of it falls back to the system face.
 *
 * This step is shared by all four consumers of `SessionSubline` (session list, search results,
 * project drill-down, archived) and must stay one size across them — see docs/DESIGN.md §5.2. So
 * the 5th pull's 12 → 11.5 reaches those three screens too, even though the rest of that pull's
 * density change is scoped to the session list.
 *
 * Historical note, now fixed: a session whose PROJECT NAME is Chinese used to render its row at
 * Material's 88dp instead of 72dp with the content top-aligned and a hole underneath
 * (docs/ANDROID_SMOKE.md A-05). It was never this font's doing — it was `ListItem` switching to
 * three-line geometry when the wrapped subline overflowed its two-line budget. The session row no
 * longer uses `ListItem`, so the line-count cliff is gone. `includeFontPadding` and a trimmed
 * `LineHeightStyle` were tried against the old bug and changed nothing; they stay absent.
 */
val SessionRowSubline = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.5.sp,
    lineHeight = rowLeading(11.5f),
    letterSpacing = 0.sp,
).merge(ExactLineBox)

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
    fontSize = 11.5.sp,
    lineHeight = rowLeading(11.5f),
    // `tracking-tight` is −0.025em, which happened to be exactly −0.3 at the old 12px. At 11.5 it
    // is −0.2875; carrying the rounded −0.3 would silently make this the one step that is not the
    // mock's own arithmetic.
    letterSpacing = (-0.2875).sp,
).merge(ExactLineBox)

/**
 * 「正在使用 <top-monitor> 工具…」 — the one status line the mock sets in mono, because the thing
 * worth reading in it is a tool name. The Chinese around it falls back to the system face.
 */
val SessionRowStatusRunning = SessionRowStatus.copy(fontFamily = HermesMono)

/**
 * The session list's centred top-bar title: the mock's `text-[16px] font-semibold
 * tracking-[-0.01em]` (17px before the 5th pull).
 *
 * Its own style rather than a smaller `titleLarge`, because `titleLarge` is the 20sp step every
 * other screen's bar uses and the mock only speaks for this one. The 1.45 multiplier is the page
 * default — `leading-[1.35]` belongs to the row's text column, not to chrome.
 */
val SessionsTopBarTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 23.2.sp,
    letterSpacing = (-0.16).sp,
)

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
 * `text-[13px] font-medium tracking-tight`. Sans — the segment labels are prose, not data.
 *
 * Tracking was recorded as −0.135 while the mock has always said `tracking-tight`. That is −0.025em,
 * not the −0.01em the rows use, so the transcription was wrong from the start — at the old 13.5px it
 * should have been −0.3375. Corrected here along with the 5th pull's 13.5 → 13.
 *
 * The 1.45 multiplier is kept: `leading-[1.35]` in the mock sits on the row's text column, and this
 * label is outside it.
 */
val SegmentLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.85.sp,
    letterSpacing = (-0.325).sp,
)

/** 「仅此设备」: `font-mono text-[10px] tracking-tight font-normal`. */
val SessionGroupNote = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    lineHeight = 13.sp,
    letterSpacing = (-0.1).sp,
)

// ── Card page steps (docs/DESIGN.md §3.2, Stitch 基线-卡片页, second pull 2026-09-11) ──────────
//
// Every step below is read straight off the mock's classes: `text-[Npx]` plus the weight and
// tracking it carries. Line height is the browser's inherited 1.5 unless the element sets
// `leading-tight` (1.25) or `leading-none` (1); `tracking-tight` is −0.025em at the step's size
// and `tracking-wide` is +0.025em. Sans throughout, with one exception: the footer tagline, which
// the second pull sets in an italic serif.

/** 「Hermes GO」: `text-[20px] font-bold tracking-tight`. */
val CardWordmark = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.5).sp,
)

/**
 * The build-type chip beside the wordmark: `text-[11px] font-medium tracking-wide`. The first pull
 * drew it 10.5/600 in caps with tight padding; the second lightened it and rounded it more.
 */
val CardChip = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.5.sp,
    letterSpacing = 0.275.sp,
)

/** The identity card's big line: `text-[15.5px] font-semibold tracking-tight`. */
val CardIdentityName = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.5.sp,
    lineHeight = 23.25.sp,
    letterSpacing = (-0.3875).sp,
)

/** Every subline on the two cards: `text-[12px]`. */
val CardIdentitySub = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

/** 「远程节点」: `text-[14.5px] font-semibold tracking-tight` (was 14px before the second pull). */
val CardNodeTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.5.sp,
    lineHeight = 21.75.sp,
    letterSpacing = (-0.3625).sp,
)

/** Shortcut row labels: `text-[14.5px] font-medium`. */
val CardRowTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 14.5.sp,
    lineHeight = 21.75.sp,
    letterSpacing = 0.sp,
)

/** Right-hand values on the rows, and the node card's latency: `text-[13px]`. */
val CardRowValue = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.5.sp,
    letterSpacing = 0.sp,
)

/**
 * The bottom tagline: `text-[12px] tracking-wide italic font-serif`.
 *
 * The one serif in the app, and deliberately not bundled — [FontFamily.Serif] is whatever the
 * device ships (Noto Serif on stock Android), which is enough for six English words set as a
 * flourish. Bundling a face for this line would cost more than the line is worth.
 */
val CardFooter = TextStyle(
    fontFamily = FontFamily.Serif,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.3.sp,
)

// ── Card page · theme sheet (Stitch 基线-卡片页/主题设置 / 暗夜) ─────────────────────────────────
//
// Same reading rule as the card-page steps above: the class is the spec, line height is 1.5 unless
// the element says otherwise, `tracking-tight` is −0.025em at the step's size.

/** 「外观与主题」: `text-lg font-semibold tracking-tight`. */
val CardThemeTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 27.sp,
    letterSpacing = (-0.45).sp,
)

/** An option's name: `text-sm font-semibold`, no tracking class — so NOT [CardNodeTitle]. */
val CardThemeOptionTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.sp,
)

/** 「当前使用」: `text-[10px] font-semibold`. The build chip is 11/500 now, so this is its own step. */
val CardThemeBadge = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
)

/**
 * The save button's label: `text-sm font-medium`.
 *
 * Geometry from the light mock, as always. The dark mock sets the same button 600 with
 * `tracking-wide`; recorded in the lock file's `pairs`.
 */
val CardThemeCta = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.sp,
)

// ── 模型选择 (docs/DESIGN.md §5.17, Stitch 基线-模型选择, 2026-09-12) ────────────────────────────
//
// Same transcription rules as the card page above. One extra Tailwind subtlety this screen leans
// on: `text-xs` is a PAIRED step (12px/16px), while `text-[12px]` is an arbitrary one and inherits
// Preflight's 1.5 (12px/18px). The mock uses both, deliberately — the tight 16px pairing is for
// chips and buttons that must not grow their container, the loose 18px one for the card's subline.
// Sans throughout except [ModelGroupCount], the one thing the mock marks `font-mono`.

/** 「选择模型」: `text-[19px] font-bold tracking-tight`. */
val ModelSheetTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 19.sp,
    lineHeight = 28.5.sp,
    letterSpacing = (-0.475).sp,
)

/** The status card's model name: `text-[16px] font-bold tracking-tight`. */
val ModelCardName = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.4).sp,
)

/** Its provider line: `text-[12px]`, the loose pairing. */
val ModelCardProvider = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

/** 「恢复默认」 and the effort dropdown's value: `text-xs`, the tight pairing. */
val ModelCardAction = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp,
)

/** The dropdown's current value, one weight up from [ModelCardAction]: `text-xs font-semibold`. */
val ModelEffortValue = ModelCardAction.copy(fontWeight = FontWeight.SemiBold)

/** 「推理强度」: `text-[13px] font-medium`. */
val ModelEffortLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 19.5.sp,
    letterSpacing = 0.sp,
)

/** 「当前使用」, 「固定顶部」: `text-[11px] font-medium`. */
val ModelBadge = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.5.sp,
    letterSpacing = 0.sp,
)

/** The in-row badges 「前次生效」/「切换中…」: `text-[10px]`, 500 neutral and 600 on the tint. */
val ModelRowBadge = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
)

/** A provider card's title: `text-xs font-bold tracking-tight`. */
val ModelGroupTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = (-0.3).sp,
)

/** 「N 项」: `text-[11px] font-mono font-medium` — the one mono step on this screen. */
val ModelGroupCount = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.5.sp,
    letterSpacing = 0.sp,
)

/** A model row's name: `text-[13.5px] font-medium`, 600 on the selected row. */
val ModelRowName = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.5.sp,
    lineHeight = 20.25.sp,
    letterSpacing = 0.sp,
)

/** A model row's provider line: `text-[11px]`. */
val ModelRowProvider = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.5.sp,
    letterSpacing = 0.sp,
)

/** 「快捷切换」: `text-[11px] font-medium`. */
val ModelQuickLabel = ModelBadge

/** 「正在切换至 X」: `text-[10px] font-medium`. */
val ModelQuickStatus = ModelRowBadge

/** A quick-switch chip's model name: `text-xs font-medium`, 600 when it is the one in flight. */
val ModelQuickChip = ModelCardAction

/** The provider suffix inside a chip: `text-[10px] font-normal`. */
val ModelQuickChipProvider = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
)

// ── 聊天页浮层 (docs/DESIGN.md §5.4, Stitch 基线-聊天页/滑动引导胶囊 与 /我的提问, 2026-09-12) ──────
//
// Line heights follow the same reading as the card page's: a `text-[Npx]` with no `leading-*`
// inherits the browser's 1.5; `leading-tight` = 1.25, `leading-snug` = 1.375, `leading-none` = 1.
// `tracking-tight` = −0.025em, converted at the step's own size.
//
// No mono here. Neither mock marks anything `font-mono` — not the 「N 条」 count, which is the one
// place the session list would have used it. Per §7 item 8 the mock decides, screen by screen.

/** The pill's summary line: `text-[13px] font-medium tracking-tight`, always one line. */
val ChatPillLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 19.5.sp,
    letterSpacing = (-0.325).sp,
)

/** 「我的提问」: `text-[18px] font-semibold tracking-tight leading-tight`, now left-aligned. */
val ChatSheetTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 22.5.sp,
    letterSpacing = (-0.45).sp,
)

/** 「7 条」 in the header chip: `text-[11px] font-medium leading-none`. */
val ChatSheetCount = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 11.sp,
    letterSpacing = 0.sp,
)

/**
 * A prompt row's summary: `text-[15px] font-medium leading-snug`.
 *
 * Down from `bodyLarge` (16sp/400) — the sheet is an index, not a reading surface, and the mock
 * buys a whole extra row per screen with the change.
 */
val ChatPromptLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 20.625.sp,
    letterSpacing = 0.sp,
)

/** The same step at 600 on the row you are reading — the mock's `font-semibold`. */
val ChatPromptLabelCurrent = ChatPromptLabel.copy(fontWeight = FontWeight.SemiBold)

/** A row's timestamp: `text-[12px] font-normal`. */
val ChatPromptTime = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

// ── 会话行长按操作单 (docs/DESIGN.md §5.5, Stitch 基线-会话列表页/长按下拉菜单, 2026-09-12) ────────
//
// Every step below is an ARBITRARY Tailwind size (`text-[16px]`, not `text-base`), so none of them
// carries its own line-height: all four inherit the body's `text-body-md` pairing, 20px. That is
// why 20sp appears on an 11sp chip as well as on the 16sp title — it is the mock's own number, and
// it is what makes the chip 24dp tall (20 + py-0.5) and the action row 44dp (20 + py-3).
//
// [ExactLineBox] is merged in for the same reason the session row needs it: these are single-line
// texts, and Compose's default `LineHeightStyle` trims the leading above the first line and below
// the last, which collapses the line box to the font's own ascent+descent and makes `lineHeight`
// a no-op. Without it a "44dp" row measures 43.
//
// The light and dark mocks disagree on two of these steps — action 15px/16px and hint 12px/13px.
// Both take the DARK value, which is the one that also lands on an M3 standard step; recorded as a
// pairs decision in docs/design/stitch/stitch.lock.json.

/** The sheet's title: `text-[16px] font-semibold leading-tight tracking-tight`, one line. */
val RowMenuTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.4).sp,
).merge(ExactLineBox)

/** An action's label: `text-[16px] font-medium tracking-tight` (dark mock; light draws 15px). */
val RowMenuAction = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.4).sp,
).merge(ExactLineBox)

/**
 * The trailing hint 「可在归档箱恢复」/「不可撤销」 and the trailing project value: `text-[13px]`
 * (dark mock; light draws 12px). The delete row's hint is this step at 500 — `copy()` at the call
 * site, the way the mock switches only that one to `font-medium`.
 */
val RowMenuHint = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
).merge(ExactLineBox)

/** The type chip 「会话」/「已归档」: `text-[11px] font-medium tracking-wide`. */
val RowMenuChipLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.275.sp,
).merge(ExactLineBox)
// ── Cron steps (docs/DESIGN.md §5.18, Stitch 基线-定时任务列表 / 任务详情, 2026-09-12) ──────────
//
// Read straight off the mocks' classes the same way the card-page family was. Where a step exists
// already it is reused instead of duplicated: the list ROW uses [SessionRowTitle] /
// [SessionRowSubline] / [SessionRowStatus], and the group header uses [SessionGroupHeader] /
// [SessionGroupCount]. Only what the cron mocks say something new about is below.
//
// The 1.45 page multiplier applies to all of these: `leading-[1.35]` belongs to a list row's text
// column, and none of these steps sits in one.

/** The list's bar title: `text-[19px] font-semibold tracking-tight`. */
val CronTopBarTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp,
    lineHeight = 27.55.sp,
    letterSpacing = (-0.19).sp,
)

/** 「当前身份 · default」 under it: `text-[11px] font-medium`. */
val CronTopBarSubtitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.95.sp,
    letterSpacing = 0.sp,
)

/**
 * The detail bar's centred title: `text-[15px] font-semibold tracking-tight`.
 *
 * Smaller than the list's 19px on purpose — the mock gives the pushed screen a quieter bar, and
 * the job's name is already the thing the reader tapped to get here.
 */
val CronDetailTopBarTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 21.75.sp,
    letterSpacing = (-0.15).sp,
)

/** A status-card field label (「下次运行」「投递落点」): `text-xs`, the card's faintest ink. */
val CronFieldLabel = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = 0.sp,
)

/** Its value: `text-[13px] font-medium tracking-tight`. */
val CronFieldValue = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.85.sp,
    letterSpacing = (-0.13).sp,
)

/** 「每天 18:15」, the card's headline value: `text-sm font-medium`. */
val CronScheduleValue = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.3.sp,
    letterSpacing = 0.sp,
)

/** A pill's label (「已启用」「成功」): `text-xs font-medium`. */
val CronPill = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.4.sp,
    letterSpacing = 0.sp,
)

/** 「运行历史」 and the prompt card's header: `text-sm font-semibold`. */
val CronSectionTitle = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 20.3.sp,
    letterSpacing = 0.sp,
)

/**
 * The prompt preview: `text-[13px] leading-relaxed font-mono`. Tailwind's `leading-relaxed` is
 * 1.625, not the page's 1.45 — the mock loosens this one block because it is a wall of text.
 *
 * Mono is doing real work here: a cron prompt is mostly paths, flags and shell, and the Chinese
 * around them falls back to the system face anyway (docs/DESIGN.md §3.1).
 */
val CronPromptBody = TextStyle(
    fontFamily = HermesMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 21.125.sp,
    letterSpacing = 0.sp,
)

/** A run's start time: `text-xs font-medium`. */
val CronRunTime = CronPill

/** A run's outcome line: `text-[11px]`. */
val CronRunMeta = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.95.sp,
    letterSpacing = 0.sp,
)

/** 「立即运行」: `text-sm font-medium`. */
val CronActionLabel = CronScheduleValue

/** 「暂停任务」「编辑配置」「删除此定时任务」: `text-xs font-medium`. */
val CronActionLabelSmall = CronPill
