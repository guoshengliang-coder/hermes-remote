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

/**
 * The group-header pillars (docs/DESIGN.md §5.2). Four colours, one per group, straight from the
 * design source — the 2026-09-10 "only 需要你处理 carries a hue" rule was reversed on 2026-09-11
 * because an all-neutral list of headers reads flat.
 *
 * Only the PILLAR is coloured. Header labels and count chips stay neutral for every group except
 * 需要你处理, which is what the mock does and what keeps "this one needs action" the loudest thing
 * on the screen rather than one of four competing hues.
 *
 * 需要你处理's pillar is the graphic amber and lives in StatusColors, next to the dot it matches.
 *
 * **Known collision, accepted**: the dark 今天 pillar is `#34D399`, which is exactly the dark
 * `StatusTone.GOOD` a 「已完成」 dot draws. The design source has this collision too — it paints
 * both with its mint. Recorded here rather than quietly resolved, because the rule since
 * 2026-09-11 is that the mock wins.
 */
internal val PillarPinnedLight = Color(0xFF2563EB)
internal val PillarPinnedDark = Color(0xFF3B82F6)
internal val PillarTodayLight = Color(0xFF059669)
internal val PillarTodayDark = Color(0xFF34D399)
internal val PillarOlderLight = Color(0xFF94A3B8)
internal val PillarOlderDark = Color(0xFF64748B)

@Composable
fun pillarPinnedColor(): Color = if (isDarkSurface()) PillarPinnedDark else PillarPinnedLight

@Composable
fun pillarTodayColor(): Color = if (isDarkSurface()) PillarTodayDark else PillarTodayLight

@Composable
fun pillarOlderColor(): Color = if (isDarkSurface()) PillarOlderDark else PillarOlderLight

@Composable
fun tileShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

/** Divider inside a faint card — one step stronger than the fill, never `outlineVariant`. */
@Composable
fun hairlineColor(): Color =
    if (isDarkSurface()) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.14f)
    else Color(0xFFEFEEEA) // surfaceContainer — one warm step past the card fill

// ── Card page (docs/DESIGN.md §5.1, Stitch 基线-卡片页 / 暗夜, second pull 2026-09-11) ─────────
//
// The drawer's own tile language, kept apart from [tileColor] on purpose: the card-page mock
// fills its cards with warm paper-subtle and rings them in a hairline, the usage page's mock does
// not exist yet, and the product owner ruled that the usage page keeps the old faint card until
// it has a mock of its own. Two truths for now, one per page.
//
// The second pull lightened everything: the cards went from pure white to #F4F3EE, and the
// shortcut rows left their card entirely — they are bare rows on the drawer now, separated by
// hairlines. The status capsule is gone with the latency bands it carried.

/** The drawer sheet itself: paper in light (= surface), one container step UP from the ground
 *  in dark (= surfaceContainerLow). A literal pair so the fixture can pin it as one role. */
internal val CardDrawerLight = Color(0xFFFAF9F5)
internal val CardDrawerDark = Color(0xFF161A22)

@Composable
fun cardDrawerColor(): Color = if (isDarkSurface()) CardDrawerDark else CardDrawerLight

/** Card fill: paper-subtle in light (NOT white — changed by the second pull), one step up in dark. */
internal val CardTileLight = Color(0xFFF4F3EE)
internal val CardTileDark = Color(0xFF1E232B)

/**
 * The card's hairline. The mock draws `#E3E2DF` at 60% over the paper ground; this is that blend
 * resolved to an opaque value so the fixture can pin it, the same way the dark capsule's rgba was
 * resolved in the first pull.
 */
internal val CardTileBorderLight = Color(0xFFECEBE8)
internal val CardTileBorderDark = Color(0xFF262C35)

/** Between shortcut rows. Dark is the mock's `white/5` over the drawer, resolved to opaque. */
internal val CardDividerLight = Color(0xFFEFEEEA)
internal val CardDividerDark = Color(0xFF21252D)

/** The build-type chip's fill. The gear button carries no fill at all since the second pull. */
internal val CardChipLight = Color(0xFFEFEEEA)
internal val CardChipDark = Color(0xFF1E232B)

/** Row icons, right-hand values and the gear: the mock's `ink-muted`, one step lighter than body ink. */
internal val CardInkMutedLight = Color(0xFF605C54)
internal val CardInkMutedDark = Color(0xFFA8A49C)

/** The 40dp rounded tile behind the remote-node icon: paper in light, one step up in dark. */
internal val CardIconTileLight = Color(0xFFFAF9F5)
internal val CardIconTileDark = Color(0xFF262C35)
internal val CardIconTileBorderLight = Color(0xFFECEBE8)
internal val CardIconTileBorderDark = Color(0xFF31373F)

/**
 * The "checked, and you are on the newest build" dot on the 检查更新 row. Green in both tiers, as
 * drawn. An update that IS available takes the amber graphic tier of WARN instead, so the row has
 * one dot with two meanings and never two dots.
 */
val CardDotGood = Color(0xFF16A34A)

/**
 * The footer's hairline rules, which fade to transparent away from the ✦.
 *
 * The dark mock paints these and the tagline in COLD greys (#717684 / #8A90A0). §2.1 bans cold
 * neutrals on warm paper — and the drawer is the same sheet in both themes — so the dark tier uses
 * the warm `outline` instead. Recorded as a deviation in design-conformance.json.
 */
internal val CardFooterRuleLight = Color(0xFF777268)
internal val CardFooterRuleDark = Color(0xFF8D897E)

@Composable fun cardTileColor(): Color = if (isDarkSurface()) CardTileDark else CardTileLight
@Composable fun cardTileBorderColor(): Color = if (isDarkSurface()) CardTileBorderDark else CardTileBorderLight
@Composable fun cardDividerColor(): Color = if (isDarkSurface()) CardDividerDark else CardDividerLight
@Composable fun cardChipColor(): Color = if (isDarkSurface()) CardChipDark else CardChipLight
@Composable fun cardInkMutedColor(): Color = if (isDarkSurface()) CardInkMutedDark else CardInkMutedLight
@Composable fun cardIconTileColor(): Color = if (isDarkSurface()) CardIconTileDark else CardIconTileLight
@Composable fun cardIconTileBorderColor(): Color = if (isDarkSurface()) CardIconTileBorderDark else CardIconTileBorderLight
@Composable fun cardFooterRuleColor(): Color = if (isDarkSurface()) CardFooterRuleDark else CardFooterRuleLight

/** The mock's `shadow-sm` — a whisper in light, nothing in dark (the border carries it there). */
@Composable
fun cardTileShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

// ── Card page · theme sheet (docs/DESIGN.md §5.1, Stitch 基线-卡片页/主题设置 / 暗夜) ───────────
//
// Its own small family, and not a reuse of the `card*` tokens above, because the theme sheet is a
// RAISED layer over the drawer while those describe the drawer itself. The second card-page pull
// dropped the drawer's cards to paper-subtle #F4F3EE; the sheet mock kept white. Every value below
// is read off the sheet mock, light for light and dark for dark.
//
// The layering rule the two tiers share, despite looking different: THE OPTION CARD NEVER RISES
// ABOVE THE SHEET — its boundary is carried by the hairline. Light draws sheet and card in the
// same white and separates them with [CardThemeBorderLight]; dark recesses the card one step under
// the sheet and rings it. This is not §2.3's "card brighter than ground", and does not have to be:
// the sheet is already the raised thing, so a card inside it has nowhere left to rise to.

/** The sheet container. Dark happens to equal [CardTileDark]; light does not equal [CardTileLight]. */
internal val CardThemeSheetLight = Color(0xFFFFFFFF)
internal val CardThemeSheetDark = Color(0xFF1E232B)

/** One option card. */
internal val CardThemeOptionLight = Color(0xFFFFFFFF)
internal val CardThemeOptionDark = Color(0xFF161A22)

/** The hairline on the option card, the icon tile and the 「当前使用」 badge — one value for all three. */
internal val CardThemeBorderLight = Color(0xFFE9E8E4)
internal val CardThemeBorderDark = Color(0xFF2E343D)

/** The 40dp rounded tile behind each option's icon. */
internal val CardThemeIconTileLight = Color(0xFFF4F4F0)
internal val CardThemeIconTileDark = Color(0xFF0F1217)

/**
 * The selected radio and the save button's fill: a neutral near-black / near-white, deliberately
 * NOT the brand colour.
 *
 * The second exception to §1 原则3 after [FabContainerLight], and for the same reason: picking a
 * theme is not a runtime state, and a slab of brand blue in an app whose every other accent reports
 * one would be read as a status rather than as a choice.
 */
internal val CardThemeAccentLight = Color(0xFF181C24)
internal val CardThemeAccentDark = Color(0xFFE2E0DB)

/** Ink on [CardThemeAccentLight] / [CardThemeAccentDark]: 17.07:1 light, 15.71:1 dark. */
internal val CardThemeAccentInkLight = Color(0xFFFFFFFF)
internal val CardThemeAccentInkDark = Color(0xFF0F1217)

/**
 * The 「当前使用」 badge's fill. The light mock never drew this badge — it left the slot beside the
 * title as an empty span — so the light value is derived from [CardChipLight], the other small
 * neutral pill on this page. Dark is the mock's own #262C35.
 */
internal val CardThemeBadgeLight = Color(0xFFEFEEEA)
internal val CardThemeBadgeDark = Color(0xFF262C35)

@Composable fun cardThemeSheetColor(): Color = if (isDarkSurface()) CardThemeSheetDark else CardThemeSheetLight
@Composable fun cardThemeOptionColor(): Color = if (isDarkSurface()) CardThemeOptionDark else CardThemeOptionLight
@Composable fun cardThemeBorderColor(): Color = if (isDarkSurface()) CardThemeBorderDark else CardThemeBorderLight
@Composable fun cardThemeIconTileColor(): Color = if (isDarkSurface()) CardThemeIconTileDark else CardThemeIconTileLight
@Composable fun cardThemeAccentColor(): Color = if (isDarkSurface()) CardThemeAccentDark else CardThemeAccentLight
@Composable fun cardThemeAccentInkColor(): Color = if (isDarkSurface()) CardThemeAccentInkDark else CardThemeAccentInkLight
@Composable fun cardThemeBadgeColor(): Color = if (isDarkSurface()) CardThemeBadgeDark else CardThemeBadgeLight

// ── 模型选择 (docs/DESIGN.md §5.17, Stitch 基线-模型选择 / 暗夜, 2026-09-12) ─────────────────────
//
// Its own family for the same reason `card*` and `cardTheme*` are theirs: every value below is
// read off the model-selection mock, and that mock disagrees with the card page's about what a
// card is filled with (#161A22 here, #1E232B there). One family per screen, no silent sharing.
//
// THE LIGHT TIER IS NOT THE MOCK'S. The mock draws its neutrals from Tailwind's COLD `neutral`
// ramp — #FAFAFA / #F5F5F5 / #E5E5E5 / #A3A3A3 / #737373 — on a warm paper ground. §2.1 bans cold
// neutrals on warm paper, and the card page already resolved this the same way. Each cold step is
// therefore swapped for the repo's warm step of equal lightness, once, here:
//
//     #FAFAFA → #F4F4F0   #F5F5F5 → #EFEEEA   #E5E5E5 → #ECEBE8
//     #A3A3A3 → #A8A29E   #737373 → #605C54   #D4D4D4 → #C9C7C2
//
// The DARK tier needs no such swap — the mock's dark neutrals already are this repo's ladder
// (#161A22 = surfaceContainerLow, #1A1F27 = surfaceContainer, #1E232B = surfaceContainerHigh,
// #262C35 = surfaceContainerHighest, #8D897E = outline, #A8A49C = onSurfaceVariant) — so it is
// transcribed verbatim. Every swap is recorded row by row in design-conformance.json.
//
// The blues are NOT swapped. §2.1 bans cold NEUTRALS; a brand-blue tint is the brand colour.

/** The status card and every provider card. Light is white on paper; dark recesses one step. */
internal val ModelCardLight = Color(0xFFFFFFFF)
internal val ModelCardDark = Color(0xFF161A22)

/** The card's hairline ring. */
internal val ModelCardBorderLight = Color(0xFFECEBE8)
internal val ModelCardBorderDark = Color(0xFF262C35)

/** Inside a card: under the title bar, between rows, above the reasoning row. */
internal val ModelDividerLight = Color(0xFFEFEEEA)
internal val ModelDividerDark = Color(0xFF262C35)

/** A provider card's title bar — one step off the card fill, not off the ground. */
internal val ModelBarLight = Color(0xFFF4F4F0)
internal val ModelBarDark = Color(0xFF1A1F27)

/** Recessed slots ON a card: the reasoning row, the small neutral badges, the ✕ button. */
internal val ModelInsetLight = Color(0xFFEFEEEA)
internal val ModelInsetDark = Color(0xFF1E232B)

/** Badge text, the reasoning label, a quick-switch chip's model name. */
internal val ModelInkMutedLight = Color(0xFF605C54)
internal val ModelInkMutedDark = Color(0xFFA8A49C)

/** The faintest step: every provider subline, the 「N 项」 count, the 固定顶部 note. */
internal val ModelInkFaintLight = Color(0xFFA8A29E)
internal val ModelInkFaintDark = Color(0xFF8D897E)

/**
 * The brand accent: the status card's 6dp stripe, the effort dot, the in-flight spinner.
 *
 * Numerically the same pair as [SpinnerLight]/[SpinnerDark] and [PillarPinnedLight]/[PillarPinnedDark]
 * — three mocks independently landed on the same cobalt. Kept as its own pair rather than aliased,
 * because nothing says the model sheet's accent has to move when the session list's pillar does.
 */
internal val ModelAccentLight = Color(0xFF2563EB)
internal val ModelAccentDark = Color(0xFF3B82F6)

/** Ink on the accent tint: the selected row's name, 「正在切换至 X」, 「恢复默认」. */
internal val ModelAccentInkLight = Color(0xFF1D4ED8)
internal val ModelAccentInkDark = Color(0xFF60A5FA)

/** The selected row / chip: a blue tint, and the ring that carries it in both tiers. */
internal val ModelCurrentFillLight = Color(0xFFEFF6FF)
internal val ModelCurrentFillDark = Color(0xFF0C1A30)
internal val ModelCurrentBorderLight = Color(0xFFBFDBFE)
internal val ModelCurrentBorderDark = Color(0xFF3B82F6)

/** The 「切换中…」 badge's fill — one step deeper than the row tint it sits on. */
internal val ModelSwitchChipLight = Color(0xFFDBEAFE)
internal val ModelSwitchChipDark = Color(0xFF142A4D)

/**
 * The favourite star. Amber, where this app has painted it brand blue since it shipped: the mock
 * is explicit (`text-amber-500` / `#FBBF24`) and a blue star on a sheet whose selection state is
 * also blue made "starred" and "in use" the same colour.
 *
 * The dark value is exactly `StatusTone.WARN`'s dark text step. A coincidence of two mocks, not a
 * shared token — a star is not a warning.
 */
internal val ModelStarLight = Color(0xFFF59E0B)
internal val ModelStarDark = Color(0xFFFBBF24)

/** An unstarred star: present, clearly off, and not competing with the row's text. */
internal val ModelStarOffLight = Color(0xFFC9C7C2)
internal val ModelStarOffDark = Color(0xFF423F3A)

@Composable fun modelCardColor(): Color = if (isDarkSurface()) ModelCardDark else ModelCardLight
@Composable fun modelCardBorderColor(): Color = if (isDarkSurface()) ModelCardBorderDark else ModelCardBorderLight
@Composable fun modelDividerColor(): Color = if (isDarkSurface()) ModelDividerDark else ModelDividerLight
@Composable fun modelBarColor(): Color = if (isDarkSurface()) ModelBarDark else ModelBarLight
@Composable fun modelInsetColor(): Color = if (isDarkSurface()) ModelInsetDark else ModelInsetLight
@Composable fun modelInkMutedColor(): Color = if (isDarkSurface()) ModelInkMutedDark else ModelInkMutedLight
@Composable fun modelInkFaintColor(): Color = if (isDarkSurface()) ModelInkFaintDark else ModelInkFaintLight
@Composable fun modelAccentColor(): Color = if (isDarkSurface()) ModelAccentDark else ModelAccentLight
@Composable fun modelAccentInkColor(): Color = if (isDarkSurface()) ModelAccentInkDark else ModelAccentInkLight
@Composable fun modelCurrentFillColor(): Color = if (isDarkSurface()) ModelCurrentFillDark else ModelCurrentFillLight
@Composable fun modelCurrentBorderColor(): Color = if (isDarkSurface()) ModelCurrentBorderDark else ModelCurrentBorderLight
@Composable fun modelSwitchChipColor(): Color = if (isDarkSurface()) ModelSwitchChipDark else ModelSwitchChipLight
@Composable fun modelStarColor(): Color = if (isDarkSurface()) ModelStarDark else ModelStarLight
@Composable fun modelStarOffColor(): Color = if (isDarkSurface()) ModelStarOffDark else ModelStarOffLight

/** The mock's `shadow-warm-sm` — a whisper in light, nothing in dark (the ring carries it there). */
@Composable
fun modelCardShadow(): Dp = if (isDarkSurface()) 0.dp else 1.dp

// ── 会话行长按操作单 (docs/DESIGN.md §5.5, Stitch 基线-会话列表页/长按下拉菜单 / 暗夜, 2026-09-12) ──
//
// Its own family, for the same reason `card*`, `cardTheme*` and `model*` are theirs: every value
// below is read off the row-menu mock, and that mock does not agree with the others about what a
// bottom sheet is filled with. Light here is the PAPER itself (#FAF9F5 = surface) with a hairline
// top edge, where the theme sheet's mock is pure white and M3's own default is
// surfaceContainerLow. One family per screen, no silent sharing.
//
// Two rgba values are resolved to their opaque composite rather than kept as an alpha, the way
// the card page resolved its own: a fixture can pin a colour, not a blend.
//
//     divider light  rgba(119,114,104,0.12) over #FAF9F5 → #EAE9E4
//     divider dark   rgba(255,255,255,0.06) over #161A22 → #24282F
//     top edge light rgba(0,0,0,0.04)       over #FAF9F5 → #F0EFEB
//     top edge dark  rgba(255,255,255,0.08) over #161A22 → #292C34
//
// Two DARK values are swapped, not transcribed — the rest of the dark tier already is this repo's
// warm ladder (#161A22 surfaceContainerLow, #262C35 surfaceContainerHighest, #E2E0DB onSurface,
// #A8A49C onSurfaceVariant, #FFB4AB error) and is copied verbatim:
//
//     handle    #3A4049 → #423F3A   (cold grey; §2.1 bans cold neutrals, = outlineVariant dark)
//     ink faint #777268 → #8D897E   (the mock reached for the LIGHT tier's outline, = outline dark)
//
// Both swaps are recorded row by row in design-conformance.json.

/** The sheet ground. Light is the page's own paper; only the top edge separates them. */
internal val RowMenuSheetLight = Color(0xFFFAF9F5)
internal val RowMenuSheetDark = Color(0xFF161A22)

/** The hairline along the sheet's top edge — what keeps the light sheet off the page behind it. */
internal val RowMenuSheetBorderLight = Color(0xFFF0EFEB)
internal val RowMenuSheetBorderDark = Color(0xFF292C34)

/** The 36×4dp grab bar. */
internal val RowMenuHandleLight = Color(0xFFC9C7C2)
internal val RowMenuHandleDark = Color(0xFF423F3A)

/** The type chip's fill, and the ✕ button's. */
internal val RowMenuChipLight = Color(0xFFEFEEEA)
internal val RowMenuChipDark = Color(0xFF262C35)

/** The type chip's 1dp stroke — what keeps a #EFEEEA chip off #FAF9F5 paper at 1.05:1. */
internal val RowMenuChipBorderLight = Color(0xFFE0DEDA)
internal val RowMenuChipBorderDark = Color(0xFF373D45)

/** The type chip's text. The mock puts light on the variant tier and dark on the full one. */
internal val RowMenuChipInkLight = Color(0xFF494641)
internal val RowMenuChipInkDark = Color(0xFFE2E0DB)

/** Under the header, and above the delete row. */
internal val RowMenuDividerLight = Color(0xFFEAE9E4)
internal val RowMenuDividerDark = Color(0xFF24282F)

/** Trailing hints, the trailing project value, and its chevron. */
internal val RowMenuInkFaintLight = Color(0xFF777268)
internal val RowMenuInkFaintDark = Color(0xFF8D897E)

/** The ✕ glyph. */
internal val RowMenuCloseInkLight = Color(0xFF777268)
internal val RowMenuCloseInkDark = Color(0xFFA8A49C)

@Composable fun rowMenuSheetColor(): Color = if (isDarkSurface()) RowMenuSheetDark else RowMenuSheetLight
@Composable fun rowMenuSheetBorderColor(): Color = if (isDarkSurface()) RowMenuSheetBorderDark else RowMenuSheetBorderLight
@Composable fun rowMenuHandleColor(): Color = if (isDarkSurface()) RowMenuHandleDark else RowMenuHandleLight
@Composable fun rowMenuChipColor(): Color = if (isDarkSurface()) RowMenuChipDark else RowMenuChipLight
@Composable fun rowMenuChipBorderColor(): Color = if (isDarkSurface()) RowMenuChipBorderDark else RowMenuChipBorderLight
@Composable fun rowMenuChipInkColor(): Color = if (isDarkSurface()) RowMenuChipInkDark else RowMenuChipInkLight
@Composable fun rowMenuDividerColor(): Color = if (isDarkSurface()) RowMenuDividerDark else RowMenuDividerLight
@Composable fun rowMenuInkFaintColor(): Color = if (isDarkSurface()) RowMenuInkFaintDark else RowMenuInkFaintLight
@Composable fun rowMenuCloseInkColor(): Color = if (isDarkSurface()) RowMenuCloseInkDark else RowMenuCloseInkLight
