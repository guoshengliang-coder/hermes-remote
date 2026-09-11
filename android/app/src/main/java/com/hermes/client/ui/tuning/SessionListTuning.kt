package com.hermes.client.ui.tuning

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.ui.theme.HermesMono
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

// ─────────────────────────────────────────────────────────────────────────────────────────────
// TUNING-TEMP
//
// A temporary on-device panel for dialling in the session list's spacing, type and pillar
// colours by eye, because arguing about 15 vs 15.5sp in a pull request is slower than looking at
// it on a phone. The product owner tunes on the device, tells us the numbers, we write them into
// Type.kt / Tiles.kt / docs, and then THIS WHOLE THING GOES AWAY.
//
// To remove it, in one pass:   grep -rn TUNING-TEMP android/app/src
// That covers this package, the settings entry, the nav route, the provider in MainActivity, and
// every call site in the session list. Nothing else should ever read these values — if a second
// screen starts depending on the panel, the values have stopped being a spike and need promoting
// into the theme properly.
//
// Defaults below are exactly what the code ships today (docs/DESIGN.md §5.2), so an untouched
// panel changes nothing and "restore defaults" really does restore the shipped look.
// ─────────────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SessionListTuning(
    // Type — the three row steps.
    val titleSizeSp: Float = 15.5f,
    val titleWeightUnread: Int = 600,
    val titleWeightRead: Int = 500,
    val titleLineHeightSp: Float = 22.475f,
    val titleTrackingSp: Float = -0.155f,
    val sublineSizeSp: Float = 12f,
    val sublineLineHeightSp: Float = 17.4f,
    val statusSizeSp: Float = 12f,
    val statusWeight: Int = 500,
    val statusLineHeightSp: Float = 17.4f,
    // Type — the group header.
    val headerSizeSp: Float = 11f,
    val headerWeight: Int = 600,
    val headerTrackingSp: Float = 0.55f,
    // Spacing — what makes rows feel tight or loose.
    //
    // rowHeightDp is the row's EXACT height, not a minimum, and 72 means "leave Material alone".
    // Material's ListItem enforces its own floor (56 one-line / 72 two-line / 88 three-line), so a
    // minimum could only ever make rows taller — useless for the question actually being asked,
    // which is whether the list should be tighter. An exact height on the outer modifier gives the
    // ListItem fixed constraints, which overrides that floor in both directions.
    //
    // The default is therefore special-cased to apply no modifier at all, so an untouched panel
    // renders exactly what ships. Away from the default, content taller than the height clips —
    // acceptable while tuning, which is the only time this is non-default.
    val rowHeightDp: Float = 72f,
    val sublineGapDp: Float = 2f,
    val statusGapDp: Float = 4f,
    val headerPaddingVDp: Float = 8f,
    // The pillars.
    val pillarWidthDp: Float = 3f,
    val pillarHeightDp: Float = 12f,
    val pillarNeedsYouLight: String = "#D97706",
    val pillarPinnedLight: String = "#2563EB",
    val pillarTodayLight: String = "#059669",
    val pillarOlderLight: String = "#94A3B8",
    val pillarNeedsYouDark: String = "#F59E0B",
    val pillarPinnedDark: String = "#3B82F6",
    val pillarTodayDark: String = "#34D399",
    val pillarOlderDark: String = "#64748B",
) {
    /** True when nothing has been moved — the panel is showing the shipped design. */
    val isDefault: Boolean get() = this == SessionListTuning()
}

/** Four pillar colours resolved for one theme. */
data class PillarColors(val needsYou: Color, val pinned: Color, val today: Color, val older: Color)

/** `#RRGGBB` → Color, falling back to [fallback] so a half-typed hex never crashes the list. */
fun parseHex(value: String, fallback: Color = Color.Magenta): Color {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return fallback
    val n = hex.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or n)
}

/** Plain function, not an extension: the one call site refers to it fully qualified. */
fun pillarsOf(t: SessionListTuning, dark: Boolean): PillarColors = if (dark) {
    PillarColors(
        parseHex(t.pillarNeedsYouDark), parseHex(t.pillarPinnedDark),
        parseHex(t.pillarTodayDark), parseHex(t.pillarOlderDark),
    )
} else {
    PillarColors(
        parseHex(t.pillarNeedsYouLight), parseHex(t.pillarPinnedLight),
        parseHex(t.pillarTodayLight), parseHex(t.pillarOlderLight),
    )
}

/**
 * The values as a block of text to paste back into a message. This is how the numbers travel:
 * reading twenty steppers off a screenshot is exactly the transcription step that goes wrong.
 * Only the fields that were actually moved are listed, so a short list means a small decision.
 */
fun SessionListTuning.asReport(): String {
    val d = SessionListTuning()
    val changed = buildList {
        fun <T> row(name: String, now: T, def: T) { if (now != def) add("$name = $now   (默认 $def)") }
        row("titleSizeSp", titleSizeSp, d.titleSizeSp)
        row("titleWeightUnread", titleWeightUnread, d.titleWeightUnread)
        row("titleWeightRead", titleWeightRead, d.titleWeightRead)
        row("titleLineHeightSp", titleLineHeightSp, d.titleLineHeightSp)
        row("titleTrackingSp", titleTrackingSp, d.titleTrackingSp)
        row("sublineSizeSp", sublineSizeSp, d.sublineSizeSp)
        row("sublineLineHeightSp", sublineLineHeightSp, d.sublineLineHeightSp)
        row("statusSizeSp", statusSizeSp, d.statusSizeSp)
        row("statusWeight", statusWeight, d.statusWeight)
        row("statusLineHeightSp", statusLineHeightSp, d.statusLineHeightSp)
        row("headerSizeSp", headerSizeSp, d.headerSizeSp)
        row("headerWeight", headerWeight, d.headerWeight)
        row("headerTrackingSp", headerTrackingSp, d.headerTrackingSp)
        row("rowHeightDp", rowHeightDp, d.rowHeightDp)
        row("sublineGapDp", sublineGapDp, d.sublineGapDp)
        row("statusGapDp", statusGapDp, d.statusGapDp)
        row("headerPaddingVDp", headerPaddingVDp, d.headerPaddingVDp)
        row("pillarWidthDp", pillarWidthDp, d.pillarWidthDp)
        row("pillarHeightDp", pillarHeightDp, d.pillarHeightDp)
        row("pillarNeedsYou 浅/深", "$pillarNeedsYouLight / $pillarNeedsYouDark", "${d.pillarNeedsYouLight} / ${d.pillarNeedsYouDark}")
        row("pillarPinned 浅/深", "$pillarPinnedLight / $pillarPinnedDark", "${d.pillarPinnedLight} / ${d.pillarPinnedDark}")
        row("pillarToday 浅/深", "$pillarTodayLight / $pillarTodayDark", "${d.pillarTodayLight} / ${d.pillarTodayDark}")
        row("pillarOlder 浅/深", "$pillarOlderLight / $pillarOlderDark", "${d.pillarOlderLight} / ${d.pillarOlderDark}")
    }
    return if (changed.isEmpty()) {
        "会话列表调参：全部为默认值，没有改动。"
    } else {
        "会话列表调参（共 ${changed.size} 项与默认不同）：\n" + changed.joinToString("\n")
    }
}

private val Context.sessionListTuningDataStore by preferencesDataStore(name = "session_list_tuning")
private val KEY = stringPreferencesKey("tuning")

/** Device-local, survives restarts so two settings can be compared across sessions. */
class SessionListTuningStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val tuning: Flow<SessionListTuning> = context.sessionListTuningDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY]?.let { runCatching { json.decodeFromString<SessionListTuning>(it) }.getOrNull() }
                ?: SessionListTuning()
        }

    suspend fun save(value: SessionListTuning) {
        context.sessionListTuningDataStore.edit { it[KEY] = json.encodeToString(value) }
    }
}

val LocalSessionListTuning = staticCompositionLocalOf { SessionListTuning() }

// ── Derived values the session list actually reads ───────────────────────────────────────────

@Composable
fun tunedRowTitle(unread: Boolean): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontWeight = FontWeight(if (unread) t.titleWeightUnread else t.titleWeightRead),
        fontSize = t.titleSizeSp.sp,
        lineHeight = t.titleLineHeightSp.sp,
        letterSpacing = t.titleTrackingSp.sp,
    )
}

@Composable
fun tunedSubline(): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontFamily = HermesMono,
        fontWeight = FontWeight.Normal,
        fontSize = t.sublineSizeSp.sp,
        lineHeight = t.sublineLineHeightSp.sp,
        letterSpacing = 0.sp,
    )
}

@Composable
fun tunedStatus(mono: Boolean): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontFamily = if (mono) HermesMono else null,
        fontWeight = FontWeight(t.statusWeight),
        fontSize = t.statusSizeSp.sp,
        lineHeight = t.statusLineHeightSp.sp,
        letterSpacing = (-0.3).sp,
    )
}

@Composable
fun tunedGroupHeader(): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontFamily = HermesMono,
        fontWeight = FontWeight(t.headerWeight),
        fontSize = t.headerSizeSp.sp,
        lineHeight = (t.headerSizeSp * 1.27f).sp,
        letterSpacing = t.headerTrackingSp.sp,
    )
}

/**
 * The row height to force, or null to leave Material's own sizing alone.
 *
 * Null at the default is what keeps an untouched panel pixel-identical to what ships: no
 * modifier is applied at all, so ListItem picks its height exactly as it does in production.
 */
@Composable fun tunedRowHeightOrNull(): Dp? {
    val t = LocalSessionListTuning.current
    return if (t.rowHeightDp == SessionListTuning().rowHeightDp) null else t.rowHeightDp.dp
}
@Composable fun tunedSublineGap(): Dp = LocalSessionListTuning.current.sublineGapDp.dp
@Composable fun tunedStatusGap(): Dp = LocalSessionListTuning.current.statusGapDp.dp
@Composable fun tunedHeaderPaddingV(): Dp = LocalSessionListTuning.current.headerPaddingVDp.dp
@Composable fun tunedPillarWidth(): Dp = LocalSessionListTuning.current.pillarWidthDp.dp
@Composable fun tunedPillarHeight(): Dp = LocalSessionListTuning.current.pillarHeightDp.dp
