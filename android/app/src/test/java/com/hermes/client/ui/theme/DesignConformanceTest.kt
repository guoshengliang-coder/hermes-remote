package com.hermes.client.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps "the design source says X, we render Y" a fact the build can check.
 *
 * This exists because of how the app kept drifting from its own design source without anyone
 * noticing. Colours were compared once, screen by screen, by hand — and the roles that felt like
 * they already had an internal rule were never compared at all. "Using <tool>" is the case that
 * finally surfaced it: it resolved to `onSurfaceVariant`, the same grey as the subline beside it,
 * so a session actively running looked identical to a parked one. Nothing was broken, no test
 * failed, and it stayed that way until a user asked why the app did not match the mock.
 *
 * The fixture (`test/resources/design-conformance.json`) records, per visual role, what the design
 * source measures and what this codebase is supposed to render. It covers two families: `roles`
 * (colour, light + dark) and `type` (the four numbers of a text style — size, weight, line height,
 * tracking). Typography was the second thing to drift the same way colour did: the row title matched
 * the design on size, weight and tracking but not on line height, and the status line had borrowed
 * `labelMedium`, whose weight and tracking both point the opposite way from the design's status
 * text. Nothing failed, because nothing checked. Four things are now enforced:
 *
 *  1. **The code still matches its record.** If someone edits Color.kt or StatusColors.kt, the
 *     recorded `impl` value stops matching and this fails. Drift becomes loud.
 *  2. **Every deviation is explained.** A row whose `design` differs from its `impl` must carry a
 *     reason. Deviating is allowed — contrast floors force it regularly — but deviating *silently*
 *     is not.
 *  3. **New status colours cannot skip the comparison.** Every [StatusTone] must appear in the
 *     fixture, so adding one without recording what the design says fails here rather than shipping.
 *  4. **Type steps are pinned like colours.** Changing a size, weight, line height or tracking that
 *     the fixture records fails until the fixture says so too.
 *
 * What it deliberately does NOT do: fetch the design source. The recorded `design` values are a
 * transcription, and a transcription can go stale. When the design changes, this fixture is what
 * gets updated first — and the diff on this file is then the review of that change.
 */
class DesignConformanceTest {

    @Serializable
    private data class Role(
        val role: String,
        val ref: String,
        val design: List<String>,
        val impl: List<String>,
        val reason: String,
    )

    /**
     * A type step, as four strings in a fixed order: size, weight, line height, tracking. Strings
     * rather than numbers so the fixture reads like the design source does and so 21.75 survives
     * round-tripping. `note` records a disagreement inside the design source itself — the screen
     * against the design system — and is deliberately NOT asserted on; it exists so the next reader
     * knows the value was chosen, not copied from whichever source was open at the time.
     */
    @Serializable
    private data class TypeRole(
        val role: String,
        val ref: String,
        val design: List<String>,
        val impl: List<String>,
        val reason: String,
        val note: String = "",
    )

    @Serializable
    private data class Fixture(val roles: List<Role>, val type: List<TypeRole> = emptyList())

    private val fixture: Fixture by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/design-conformance.json")) {
            "design-conformance.json is missing from test resources"
        }.bufferedReader().readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(Fixture.serializer(), text)
    }

    /** Resolve what the code ACTUALLY renders for a role, from the real sources of truth. */
    private fun actual(ref: String, dark: Boolean): String {
        val (kind, name) = ref.split(":", limit = 2)
        val scheme: ColorScheme = if (dark) HermesDarkColors else HermesLightColors
        val color: Int = when (kind) {
            "status" -> statusArgb(StatusTone.valueOf(name), dark)
            "scheme" -> when (name) {
                "primary" -> scheme.primary
                "onSurface" -> scheme.onSurface
                "onSurfaceVariant" -> scheme.onSurfaceVariant
                "outline" -> scheme.outline
                "outlineVariant" -> scheme.outlineVariant
                "surface" -> scheme.surface
                "surfaceContainerLow" -> scheme.surfaceContainerLow
                else -> error("unknown scheme role '$name' in ref '$ref'")
            }.toArgb()
            // The graphic tier of WARN: the pillar and the waiting dot, which the design source
            // paints a brighter amber than the words beside them.
            "warnGraphic" -> warnGraphicArgb(dark)
            "tiles" -> when (name) {
                "fab" -> if (dark) FabContainerDark else FabContainerLight
                "spinner" -> if (dark) SpinnerDark else SpinnerLight
                "incidentBg" -> if (dark) IncidentContainerDark else IncidentContainerLight
                "incidentInk" -> if (dark) OnIncidentDark else OnIncidentLight
                "sublineFaint" -> if (dark) SublineFaintDark else SublineFaintLight
                "pillarPinned" -> if (dark) PillarPinnedDark else PillarPinnedLight
                "pillarToday" -> if (dark) PillarTodayDark else PillarTodayLight
                "pillarOlder" -> if (dark) PillarOlderDark else PillarOlderLight
                // Card page (docs/DESIGN.md §5.1), second pull 2026-09-11.
                "cardDrawer" -> if (dark) CardDrawerDark else CardDrawerLight
                "cardTile" -> if (dark) CardTileDark else CardTileLight
                "cardTileBorder" -> if (dark) CardTileBorderDark else CardTileBorderLight
                "cardDivider" -> if (dark) CardDividerDark else CardDividerLight
                "cardChip" -> if (dark) CardChipDark else CardChipLight
                "cardInkMuted" -> if (dark) CardInkMutedDark else CardInkMutedLight
                "cardIconTile" -> if (dark) CardIconTileDark else CardIconTileLight
                "cardIconTileBorder" -> if (dark) CardIconTileBorderDark else CardIconTileBorderLight
                "cardDotGood" -> CardDotGood
                "cardFooterRule" -> if (dark) CardFooterRuleDark else CardFooterRuleLight
                // Card page · theme sheet (docs/DESIGN.md §5.1 主题弹层).
                "cardThemeSheet" -> if (dark) CardThemeSheetDark else CardThemeSheetLight
                "cardThemeOption" -> if (dark) CardThemeOptionDark else CardThemeOptionLight
                "cardThemeBorder" -> if (dark) CardThemeBorderDark else CardThemeBorderLight
                "cardThemeIconTile" -> if (dark) CardThemeIconTileDark else CardThemeIconTileLight
                "cardThemeAccent" -> if (dark) CardThemeAccentDark else CardThemeAccentLight
                "cardThemeAccentInk" -> if (dark) CardThemeAccentInkDark else CardThemeAccentInkLight
                "cardThemeBadge" -> if (dark) CardThemeBadgeDark else CardThemeBadgeLight
                // 模型选择 (docs/DESIGN.md §5.17 模型选择).
                "modelCard" -> if (dark) ModelCardDark else ModelCardLight
                "modelCardBorder" -> if (dark) ModelCardBorderDark else ModelCardBorderLight
                "modelDivider" -> if (dark) ModelDividerDark else ModelDividerLight
                "modelBar" -> if (dark) ModelBarDark else ModelBarLight
                "modelInset" -> if (dark) ModelInsetDark else ModelInsetLight
                "modelInkMuted" -> if (dark) ModelInkMutedDark else ModelInkMutedLight
                "modelInkFaint" -> if (dark) ModelInkFaintDark else ModelInkFaintLight
                "modelAccent" -> if (dark) ModelAccentDark else ModelAccentLight
                "modelAccentInk" -> if (dark) ModelAccentInkDark else ModelAccentInkLight
                "modelCurrentFill" -> if (dark) ModelCurrentFillDark else ModelCurrentFillLight
                "modelCurrentBorder" -> if (dark) ModelCurrentBorderDark else ModelCurrentBorderLight
                "modelSwitchChip" -> if (dark) ModelSwitchChipDark else ModelSwitchChipLight
                "modelStar" -> if (dark) ModelStarDark else ModelStarLight
                "modelStarOff" -> if (dark) ModelStarOffDark else ModelStarOffLight
                else -> error("unknown tiles role '$name' in ref '$ref'")
            }.toArgb()
            else -> error("unknown ref kind '$kind' in ref '$ref'")
        }
        return hex(color)
    }

    /** Resolve a text style from the real sources of truth, as the fixture's four-string tuple. */
    private fun actualType(ref: String): List<String> {
        val (kind, name) = ref.split(":", limit = 2)
        check(kind == "type") { "unknown ref kind '$kind' in ref '$ref'" }
        val style: TextStyle = when (name) {
            "SessionRowTitle" -> SessionRowTitle
            "SessionRowTitleRead" -> SessionRowTitleRead
            "SessionRowSubline" -> SessionRowSubline
            "SessionRowStatus" -> SessionRowStatus
            "SessionGroupHeader" -> SessionGroupHeader
            "SessionGroupCount" -> SessionGroupCount
            "SessionGroupNote" -> SessionGroupNote
            "SegmentLabel" -> SegmentLabel
            "CardWordmark" -> CardWordmark
            "CardChip" -> CardChip
            "CardIdentityName" -> CardIdentityName
            "CardIdentitySub" -> CardIdentitySub
            "CardNodeTitle" -> CardNodeTitle
            "CardRowTitle" -> CardRowTitle
            "CardRowValue" -> CardRowValue
            "CardFooter" -> CardFooter
            "CardThemeTitle" -> CardThemeTitle
            "CardThemeOptionTitle" -> CardThemeOptionTitle
            "CardThemeBadge" -> CardThemeBadge
            "CardThemeCta" -> CardThemeCta
            "ModelSheetTitle" -> ModelSheetTitle
            "ModelCardName" -> ModelCardName
            "ModelCardProvider" -> ModelCardProvider
            "ModelCardAction" -> ModelCardAction
            "ModelEffortValue" -> ModelEffortValue
            "ModelEffortLabel" -> ModelEffortLabel
            "ModelBadge" -> ModelBadge
            "ModelRowBadge" -> ModelRowBadge
            "ModelGroupTitle" -> ModelGroupTitle
            "ModelGroupCount" -> ModelGroupCount
            "ModelRowName" -> ModelRowName
            "ModelRowProvider" -> ModelRowProvider
            "ModelQuickChipProvider" -> ModelQuickChipProvider
            "labelMedium" -> HermesTypography.labelMedium
            "bodyLarge" -> HermesTypography.bodyLarge
            "titleMedium" -> HermesTypography.titleMedium
            else -> error("unknown type role '$name' in ref '$ref'")
        }
        return listOf(
            num(style.fontSize.value),
            checkNotNull(style.fontWeight) { "$ref leaves fontWeight unset" }.weight.toString(),
            num(style.lineHeight.value),
            num(style.letterSpacing.value),
        )
    }

    @Test fun implementation_matches_what_the_fixture_records() {
        val drift = fixture.roles.flatMap { r ->
            listOf(false, true).mapIndexedNotNull { i, dark ->
                val got = actual(r.ref, dark)
                val want = r.impl[i].uppercase()
                val tier = if (dark) "dark" else "light"
                if (got == want) null else "${r.role} [$tier] via ${r.ref}: fixture says $want, code renders $got"
            }
        }
        assertTrue(
            "The code no longer matches design-conformance.json. Update the fixture in the same " +
                "change that moves the colour, so the diff shows what was decided:\n  " +
                drift.joinToString("\n  "),
            drift.isEmpty(),
        )
    }

    @Test fun typography_matches_what_the_fixture_records() {
        val labels = listOf("字号", "字重", "行高", "字距")
        val drift = fixture.type.flatMap { t ->
            val got = actualType(t.ref)
            got.indices.mapNotNull { i ->
                if (got[i] == t.impl[i]) null
                else "${t.role} ${labels[i]} via ${t.ref}: fixture says ${t.impl[i]}, code renders ${got[i]}"
            }
        }
        assertTrue(
            "A text style no longer matches design-conformance.json. Type drifts as quietly as " +
                "colour did — update the fixture in the same change that moves the value:\n  " +
                drift.joinToString("\n  "),
            drift.isEmpty(),
        )
    }

    @Test fun every_deviation_from_the_design_carries_a_reason() {
        val colour = fixture.roles.map { Triple(it.role, it.design, it.impl) to it.reason }
        val type = fixture.type.map { Triple(it.role, it.design, it.impl) to it.reason }
        val unexplained = (colour + type).filter { (row, reason) ->
            val (_, design, impl) = row
            design.map { it.uppercase() } != impl.map { it.uppercase() } && reason.isBlank()
        }.map { it.first.first }
        assertTrue(
            "These roles differ from the design source with no reason recorded. Deviating is fine — " +
                "contrast floors force it — but it has to be written down:\n  " +
                unexplained.joinToString("\n  "),
            unexplained.isEmpty(),
        )
    }

    /**
     * The anti-recurrence clause. A new status colour that nobody compared against the design is
     * exactly what produced the grey "running" state; this makes that omission fail the build.
     */
    @Test fun every_status_tone_has_been_compared() {
        val covered = fixture.roles
            .filter { it.ref.startsWith("status:") }
            .map { StatusTone.valueOf(it.ref.removePrefix("status:")) }
            .toSet()
        val missing = StatusTone.entries.filterNot { it in covered }
        assertTrue(
            "These status tones are not in design-conformance.json, so nothing checks them " +
                "against the design source: $missing",
            missing.isEmpty(),
        )
    }

    /** A row that claims agreement must actually agree — otherwise the reason column lies. */
    @Test fun rows_with_no_reason_really_do_match_the_design() {
        val rows = fixture.roles.filter { it.reason.isBlank() }.map { it.role to (it.design to it.impl) } +
            fixture.type.filter { it.reason.isBlank() }.map { it.role to (it.design to it.impl) }
        for ((role, values) in rows) {
            val (design, impl) = values
            assertEquals(
                "$role records no reason, so its design and impl values must be identical",
                design.map { it.uppercase() },
                impl.map { it.uppercase() },
            )
        }
    }

    /** Format a `sp` value the way the fixture writes it: 15, not 15.0; 21.75 stays 21.75. */
    private fun num(v: Float) =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun hex(argb: Int) = "#%02X%02X%02X".format(
        (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF,
    )
}
