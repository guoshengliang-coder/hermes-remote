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
    //
    // No per-step line height any more: the mock drives all three from ONE `leading-[…]` on the row
    // container, and it moved as one lever between pulls (1.45 → 1.35). Three separate knobs invite
    // a half-applied change, so line height is derived as `size × lineHeightMultiplier`.
    val titleSizeSp: Float = 14.5f,
    val titleWeightUnread: Int = 600,
    val titleWeightRead: Int = 500,
    val titleTrackingSp: Float = -0.145f,
    val sublineSizeSp: Float = 11.5f,
    val statusSizeSp: Float = 11.5f,
    val statusWeight: Int = 500,
    val statusTrackingSp: Float = -0.2875f,
    val lineHeightMultiplier: Float = 1.35f,
    // Type — the group header.
    val headerSizeSp: Float = 11f,
    val headerWeight: Int = 600,
    val headerTrackingSp: Float = 0.55f,
    // Spacing — what makes rows feel tight or loose.
    //
    // rowPaddingVDp is the knob now, and the row's height is whatever the content plus this padding
    // comes to — the mock's own model (`px-4 py-1.5`), which is why it survives CJK wrapping and
    // font scaling. It was briefly deleted as un-settable, and that was true only while the row was
    // a Material `ListItem`, which owns its internal padding and refuses to go below 56/72/88dp.
    // The row is drawn by hand now, so this is the real lever.
    val rowPaddingVDp: Float = 6f,
    val sublineGapDp: Float = 2f,
    val statusGapDp: Float = 2f,
    val sublineGlyphDp: Float = 13f,
    val headerPaddingVDp: Float = 2f,
    // The top bar. It is part of the same density pass, so it needs knobs too, or that half cannot
    // be judged on the device alongside the rows.
    val topBarHeightDp: Float = 48f,
    val avatarSizeDp: Float = 32f,
    val topBarGlyphDp: Float = 20f,
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
        row("titleTrackingSp", titleTrackingSp, d.titleTrackingSp)
        row("sublineSizeSp", sublineSizeSp, d.sublineSizeSp)
        row("statusSizeSp", statusSizeSp, d.statusSizeSp)
        row("statusWeight", statusWeight, d.statusWeight)
        row("statusTrackingSp", statusTrackingSp, d.statusTrackingSp)
        row("lineHeightMultiplier", lineHeightMultiplier, d.lineHeightMultiplier)
        row("headerSizeSp", headerSizeSp, d.headerSizeSp)
        row("headerWeight", headerWeight, d.headerWeight)
        row("headerTrackingSp", headerTrackingSp, d.headerTrackingSp)
        row("rowPaddingVDp", rowPaddingVDp, d.rowPaddingVDp)
        row("sublineGapDp", sublineGapDp, d.sublineGapDp)
        row("statusGapDp", statusGapDp, d.statusGapDp)
        row("sublineGlyphDp", sublineGlyphDp, d.sublineGlyphDp)
        row("headerPaddingVDp", headerPaddingVDp, d.headerPaddingVDp)
        row("topBarHeightDp", topBarHeightDp, d.topBarHeightDp)
        row("avatarSizeDp", avatarSizeDp, d.avatarSizeDp)
        row("topBarGlyphDp", topBarGlyphDp, d.topBarGlyphDp)
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

/**
 * `size × multiplier`, rounded the same way `ui/theme/Type.kt` rounds it. Identical arithmetic is
 * the point: at its defaults this panel must reproduce the shipped styles exactly, and float noise
 * (11.5f × 1.35f = 15.525001) would make "untouched panel" differ from "no panel".
 */
private fun scaled(sizeSp: Float, multiplier: Float) =
    (kotlin.math.round(sizeSp * multiplier * 1000f) / 1000f).sp

// ── Derived values the session list actually reads ───────────────────────────────────────────

@Composable
fun tunedRowTitle(unread: Boolean): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontWeight = FontWeight(if (unread) t.titleWeightUnread else t.titleWeightRead),
        fontSize = t.titleSizeSp.sp,
        lineHeight = scaled(t.titleSizeSp, t.lineHeightMultiplier),
        letterSpacing = t.titleTrackingSp.sp,
    ).merge(com.hermes.client.ui.theme.ExactLineBox)
}

@Composable
fun tunedSubline(): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontFamily = HermesMono,
        fontWeight = FontWeight.Normal,
        fontSize = t.sublineSizeSp.sp,
        lineHeight = scaled(t.sublineSizeSp, t.lineHeightMultiplier),
        letterSpacing = 0.sp,
    ).merge(com.hermes.client.ui.theme.ExactLineBox)
}

@Composable
fun tunedStatus(mono: Boolean): TextStyle {
    val t = LocalSessionListTuning.current
    return TextStyle(
        fontFamily = if (mono) HermesMono else null,
        fontWeight = FontWeight(t.statusWeight),
        fontSize = t.statusSizeSp.sp,
        lineHeight = scaled(t.statusSizeSp, t.lineHeightMultiplier),
        letterSpacing = t.statusTrackingSp.sp,
    ).merge(com.hermes.client.ui.theme.ExactLineBox)
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

@Composable fun tunedRowPaddingV(): Dp = LocalSessionListTuning.current.rowPaddingVDp.dp
@Composable fun tunedSublineGap(): Dp = LocalSessionListTuning.current.sublineGapDp.dp
@Composable fun tunedStatusGap(): Dp = LocalSessionListTuning.current.statusGapDp.dp
@Composable fun tunedSublineGlyph(): Dp = LocalSessionListTuning.current.sublineGlyphDp.dp
@Composable fun tunedHeaderPaddingV(): Dp = LocalSessionListTuning.current.headerPaddingVDp.dp
@Composable fun tunedTopBarHeight(): Dp = LocalSessionListTuning.current.topBarHeightDp.dp
@Composable fun tunedAvatarSize(): Dp = LocalSessionListTuning.current.avatarSizeDp.dp
@Composable fun tunedTopBarGlyph(): Dp = LocalSessionListTuning.current.topBarGlyphDp.dp
@Composable fun tunedPillarWidth(): Dp = LocalSessionListTuning.current.pillarWidthDp.dp
@Composable fun tunedPillarHeight(): Dp = LocalSessionListTuning.current.pillarHeightDp.dp
