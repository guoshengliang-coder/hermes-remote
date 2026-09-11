package com.hermes.client.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins `docs/design/stitch/design-system.md` — the design system this repository pushes back to
 * Stitch — to the theme the app actually renders.
 *
 * The push is only worth doing if what lands in Stitch is what the code does. This file is the
 * guarantee: every colour in the front matter and in the light/dark table, and every typography
 * step, must equal Color.kt / StatusColors.kt / Tiles.kt / Type.kt. Change a token in code without
 * regenerating the markdown and this fails; edit the markdown by hand to something the code does
 * not do and this fails too. The markdown is a *contract*, not a mock — DESIGN.md §7 item 8.
 *
 * Parsing is deliberately regex-level: the front matter is Stitch's own YAML shape, and pulling a
 * YAML library into the test classpath for four indent levels is not worth the dependency.
 */
class DesignSystemExportTest {

    private val file: File by lazy {
        // Unit tests run with the module directory (android/app) as the working directory —
        // ScreenshotTest relies on the same fact when it writes screenshots/<name>.png.
        val f = File("../../docs/design/stitch/design-system.md")
        check(f.isFile) { "design-system.md not found at ${f.absolutePath}" }
        f
    }
    private val text: String by lazy { file.readText() }

    private val frontMatter: String by lazy {
        check(text.startsWith("---\n")) { "design-system.md must start with a YAML front matter block" }
        val end = text.indexOf("\n---\n", startIndex = 4)
        check(end > 0) { "front matter never closes" }
        text.substring(4, end)
    }

    /** `key: '#hex'` lines under a top-level `<section>:` heading, two-space indented. */
    private fun frontMatterColors(): Map<String, String> {
        val lines = frontMatter.lines()
        val start = lines.indexOf("colors:")
        check(start >= 0) { "front matter has no colors: section" }
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(start + 1)) {
            if (!line.startsWith("  ")) break
            val m = Regex("""^  ([a-z0-9-]+): '?(#[0-9a-fA-F]{6})'?\s*$""").find(line) ?: error("unparsable colour line: $line")
            out[m.groupValues[1]] = m.groupValues[2].uppercase()
        }
        return out
    }

    private data class TypeStep(val size: Float, val weight: Int, val lineHeight: Float, val tracking: Float)

    private fun frontMatterTypography(): Map<String, TypeStep> {
        val lines = frontMatter.lines()
        val start = lines.indexOf("typography:")
        check(start >= 0) { "front matter has no typography: section" }
        val out = LinkedHashMap<String, TypeStep>()
        var name: String? = null
        val props = HashMap<String, String>()
        fun flush() {
            val n = name ?: return
            out[n] = TypeStep(
                size = props.getValue("fontSize").removeSuffix("px").toFloat(),
                weight = props.getValue("fontWeight").trim('\'').toInt(),
                lineHeight = props.getValue("lineHeight").removeSuffix("px").toFloat(),
                tracking = props.getValue("letterSpacing").removeSuffix("px").toFloat(),
            )
            props.clear()
        }
        for (line in lines.drop(start + 1)) {
            if (!line.startsWith("  ")) break
            val level = Regex("""^  ([a-z0-9-]+):\s*$""").find(line)
            if (level != null) { flush(); name = level.groupValues[1]; continue }
            val prop = Regex("""^    ([a-zA-Z]+): (.+?)\s*$""").find(line) ?: error("unparsable typography line: $line")
            props[prop.groupValues[1]] = prop.groupValues[2]
        }
        flush()
        return out
    }

    /** Rows of the `| 角色 | 浅色 | 深色 |` table in the body. `—` means "no value in this tier". */
    private fun bodyRoleTable(): Map<String, Pair<String?, String?>> {
        val row = Regex("""^\| ([a-z0-9-]+) \| (#[0-9A-Fa-f]{6}|—) \| (#[0-9A-Fa-f]{6}|—) \|\s*$""", RegexOption.MULTILINE)
        val out = LinkedHashMap<String, Pair<String?, String?>>()
        for (m in row.findAll(text)) {
            fun cell(s: String) = s.takeIf { it != "—" }?.uppercase()
            out[m.groupValues[1]] = cell(m.groupValues[2]) to cell(m.groupValues[3])
        }
        check(out.size > 30) { "role table not found or too short (${out.size} rows)" }
        return out
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    // ---- what the code says ---------------------------------------------------------------

    private val schemeRoles: Map<String, (ColorScheme) -> Color> = mapOf(
        "surface" to { it.surface }, "surface-dim" to { it.surfaceDim }, "surface-bright" to { it.surfaceBright },
        "surface-container-lowest" to { it.surfaceContainerLowest }, "surface-container-low" to { it.surfaceContainerLow },
        "surface-container" to { it.surfaceContainer }, "surface-container-high" to { it.surfaceContainerHigh },
        "surface-container-highest" to { it.surfaceContainerHighest },
        "on-surface" to { it.onSurface }, "on-surface-variant" to { it.onSurfaceVariant },
        "outline" to { it.outline }, "outline-variant" to { it.outlineVariant }, "surface-tint" to { it.surfaceTint },
        "primary" to { it.primary }, "on-primary" to { it.onPrimary },
        "primary-container" to { it.primaryContainer }, "on-primary-container" to { it.onPrimaryContainer },
        "secondary" to { it.secondary }, "on-secondary" to { it.onSecondary },
        "secondary-container" to { it.secondaryContainer }, "on-secondary-container" to { it.onSecondaryContainer },
        "tertiary" to { it.tertiary }, "on-tertiary" to { it.onTertiary },
        "tertiary-container" to { it.tertiaryContainer }, "on-tertiary-container" to { it.onTertiaryContainer },
        "error" to { it.error }, "on-error" to { it.onError },
        "error-container" to { it.errorContainer }, "on-error-container" to { it.onErrorContainer },
        "background" to { it.background }, "on-background" to { it.onBackground }, "surface-variant" to { it.surfaceVariant },
    )

    /** Tokens outside ColorScheme: (light, dark), null where the tier has no value. */
    private val extraRoles: Map<String, Pair<Color?, Color?>> = mapOf(
        "status-good" to (Color(statusArgb(StatusTone.GOOD, false)) to Color(statusArgb(StatusTone.GOOD, true))),
        "status-warn" to (Color(statusArgb(StatusTone.WARN, false)) to Color(statusArgb(StatusTone.WARN, true))),
        "status-bad" to (Color(statusArgb(StatusTone.BAD, false)) to Color(statusArgb(StatusTone.BAD, true))),
        "status-running" to (Color(statusArgb(StatusTone.RUNNING, false)) to Color(statusArgb(StatusTone.RUNNING, true))),
        "status-warn-graphic" to (Color(warnGraphicArgb(false)) to Color(warnGraphicArgb(true))),
        "subline-faint" to (SublineFaintLight to SublineFaintDark),
        "fab-container" to (FabContainerLight to FabContainerDark),
        "fab-outline" to (null to FabOutlineDark),
        "spinner" to (SpinnerLight to SpinnerDark),
        "incident-container" to (IncidentContainerLight to IncidentContainerDark),
        "incident-ink" to (OnIncidentLight to OnIncidentDark),
        // Card page (docs/DESIGN.md §5.1), second pull 2026-09-11.
        "card-drawer" to (CardDrawerLight to CardDrawerDark),
        "card-tile" to (CardTileLight to CardTileDark),
        "card-tile-border" to (CardTileBorderLight to CardTileBorderDark),
        "card-divider" to (CardDividerLight to CardDividerDark),
        "card-chip" to (CardChipLight to CardChipDark),
        "card-ink-muted" to (CardInkMutedLight to CardInkMutedDark),
        "card-icon-tile" to (CardIconTileLight to CardIconTileDark),
        "card-icon-tile-border" to (CardIconTileBorderLight to CardIconTileBorderDark),
        "card-dot-good" to (CardDotGood to CardDotGood),
        "card-footer-rule" to (CardFooterRuleLight to CardFooterRuleDark),
    )

    private val typeSteps: Map<String, TextStyle> = mapOf(
        "headline-sm" to HermesTypography.headlineSmall,
        "title-lg" to HermesTypography.titleLarge,
        "title-md" to HermesTypography.titleMedium,
        "title-sm" to HermesTypography.titleSmall,
        "body-lg" to HermesTypography.bodyLarge,
        "body-md" to HermesTypography.bodyMedium,
        "body-sm" to HermesTypography.bodySmall,
        "label-lg" to HermesTypography.labelLarge,
        "label-md" to HermesTypography.labelMedium,
        "label-sm" to HermesTypography.labelSmall,
        "session-row-title" to SessionRowTitle,
        "session-row-title-read" to SessionRowTitleRead,
        "session-row-subline" to SessionRowSubline,
        "session-row-status" to SessionRowStatus,
        "session-group-header" to SessionGroupHeader,
        "session-group-count" to SessionGroupCount,
        "session-group-note" to SessionGroupNote,
        "segment-label" to SegmentLabel,
        "card-wordmark" to CardWordmark,
        "card-chip" to CardChip,
        "card-identity-name" to CardIdentityName,
        "card-identity-sub" to CardIdentitySub,
        "card-node-title" to CardNodeTitle,
        "card-row-title" to CardRowTitle,
        "card-row-value" to CardRowValue,
        "card-footer" to CardFooter,
    )

    // ---- assertions -----------------------------------------------------------------------

    @Test
    fun `role table carries every scheme role and every extra token, in both tiers`() {
        val table = bodyRoleTable()
        for ((role, pick) in schemeRoles) {
            val (light, dark) = table[role] ?: error("role table is missing '$role'")
            assertEquals("light $role", hex(pick(HermesLightColors)), light)
            assertEquals("dark $role", hex(pick(HermesDarkColors)), dark)
        }
        for ((role, pair) in extraRoles) {
            val (light, dark) = table[role] ?: error("role table is missing '$role'")
            assertEquals("light $role", pair.first?.let(::hex), light)
            assertEquals("dark $role", pair.second?.let(::hex), dark)
        }
        val known = schemeRoles.keys + extraRoles.keys
        val stray = table.keys - known
        assertTrue("role table has rows the code does not define: $stray", stray.isEmpty())
    }

    @Test
    fun `front matter colours are the light tier of the role table`() {
        val fm = frontMatterColors()
        val table = bodyRoleTable()
        for ((role, value) in fm) {
            val light = table[role]?.first ?: error("front matter colour '$role' is not in the role table")
            assertEquals("front matter vs table: $role", light, value)
        }
        // Every light value the code has must be exported — Stitch only sees the front matter.
        val expected = (schemeRoles.keys + extraRoles.filterValues { it.first != null }.keys)
        val missing = expected - fm.keys
        assertTrue("front matter is missing light colours: $missing", missing.isEmpty())
    }

    @Test
    fun `typography steps equal Type_kt`() {
        val fm = frontMatterTypography()
        for ((name, style) in typeSteps) {
            val step = fm[name] ?: error("front matter typography is missing '$name'")
            assertEquals("$name size", style.fontSize.value, step.size, 0.001f)
            assertEquals("$name weight", style.fontWeight!!.weight, step.weight)
            assertEquals("$name lineHeight", style.lineHeight.value, step.lineHeight, 0.001f)
            assertEquals("$name tracking", style.letterSpacing.value, step.tracking, 0.001f)
        }
        val stray = fm.keys - typeSteps.keys
        assertTrue("front matter has typography steps the code does not define: $stray", stray.isEmpty())
    }
}
