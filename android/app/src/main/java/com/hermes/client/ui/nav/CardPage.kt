package com.hermes.client.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The card page (modal drawer off the session list), v3 — matched to the real-device base
 * design: "Hermes" wordmark + settings gear up top; an identity card showing ONLY the current
 * profile (tap → the dedicated profile picker); one stats container (weekly usage | remote
 * device); then four shortcut rows — scheduled jobs, theme, model, app updates — icon + label
 * left, current value + chevron right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardPage(
    onNavigate: (String) -> Unit,
    drawerState: androidx.compose.material3.DrawerState? = null,
    vm: CardPageViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val active by vm.active.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    var themeSheet by remember { mutableStateOf(false) }

    // The card page renders at design scale regardless of the system font size — the user's
    // explicit call after outsized text on large-font devices. Scoped to this sheet only; every
    // reading surface (chat, lists, settings) still honours the system preference.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val cardDensity = remember(baseDensity) {
        androidx.compose.ui.unit.Density(baseDensity.density, fontScale = minOf(baseDensity.fontScale, 1.0f))
    }

    val dark = isSystemInDarkTheme()
    // The base design's containers are NEUTRAL light grey; in dark theme fall back to the
    // theme's surfaceVariant so contrast holds.
    // Measured off the reference: card fill sits 1-2 grey steps from the sheet (near-white),
    // the outline carried by a whisper of shadow — not by a heavy fill.
    val tile = if (dark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFFAFAF8)
    val tileShadow = if (dark) 0.dp else 1.dp
    val hairline = if (dark) MaterialTheme.colorScheme.outlineVariant else Color(0xFFECECEA)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ModalDrawerSheet(
        drawerState = drawerState ?: androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open),
        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp),
        drawerShape = RoundedCornerShape(topStart = 0.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 0.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides cardDensity,
        ) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState())
                .statusBarsPadding().padding(horizontal = 24.dp),
        ) {
            // ── Wordmark + settings gear ─────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Hermes",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { onNavigate("settings") },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Settings, contentDescription = localized(language, "设置", "Settings"))
                    }
                }
            }

            // ── Identity card: current profile only; tap → profile picker ────────────
            Surface(
                onClick = { onNavigate("profiles") },
                shape = RoundedCornerShape(20.dp),
                color = tile,
                shadowElevation = tileShadow,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(active, size = 48.dp)
                    Column(Modifier.weight(1f).padding(start = 18.dp)) {
                        Text(active ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
                        Text(
                            localized(language, "当前身份", "Active profile"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = muted, modifier = Modifier.size(24.dp))
                }
            }

            // ── Stats: one container, two halves, hairline between ───────────────────
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = tile,
                shadowElevation = tileShadow,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                // The two cells SHARE one value size and one sub size: when either overflows,
                // both step down together, so "14.7M" and "mac-mini" never render at
                // mismatched sizes on the same row.
                val weekValue = state.weekTokens?.let { compactTokens(it) } ?: "—"
                val deviceValue = state.deviceId ?: localized(language, "未连接", "Offline")
                var statValueSp by remember(weekValue, deviceValue) { mutableStateOf(23f) }
                var statSubSp by remember(weekValue, deviceValue) { mutableStateOf(15f) }
                Row(
                    Modifier.padding(vertical = 18.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                ) {
                    StatCell(
                        title = localized(language, "本周用量", "This week"),
                        value = weekValue,
                        sub = state.weekCost?.let { localized(language, "预估 $%.2f".format(it), "est. $%.2f".format(it)) },
                        valueSp = statValueSp, subSp = statSubSp,
                        onValueOverflow = { if (statValueSp > 13f) statValueSp -= 1f },
                        onSubOverflow = { if (statSubSp > 11f) statSubSp -= 1f },
                        modifier = Modifier.weight(1f).clickable { onNavigate("usage") },
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
                    val healthy = health as? GatewayHealth.Healthy
                    StatCell(
                        title = localized(language, "远程设备", "Remote device"),
                        value = deviceValue,
                        sub = when {
                            healthy != null && state.deviceId != null ->
                                localized(language, "已连接", "Connected") + (healthy.latencyMs?.let { " · " + formatLatency(it) } ?: "")
                            state.deviceId == null -> localized(language, "连接器离线", "Connector offline")
                            else -> null
                        },
                        valueSp = statValueSp, subSp = statSubSp,
                        onValueOverflow = { if (statValueSp > 13f) statValueSp -= 1f },
                        onSubOverflow = { if (statSubSp > 11f) statSubSp -= 1f },
                        subColor = if (state.deviceId == null) MaterialTheme.colorScheme.error else muted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Shortcut rows: icon + label | current value + chevron ────────────────
            Column(Modifier.padding(top = 10.dp)) {
                ShortcutRow(
                    icon = Icons.Outlined.Schedule,
                    label = localized(language, "定时任务", "Scheduled jobs"),
                    badge = state.cronAlerts.takeIf { it > 0 },
                    onClick = { onNavigate("cron") },
                )
                HorizontalDivider(color = hairline)
                ShortcutRow(
                    icon = Icons.Outlined.DarkMode,
                    label = localized(language, "主题", "Theme"),
                    value = themeLabel(themeMode, language),
                    onClick = { themeSheet = true },
                )
                HorizontalDivider(color = hairline)
                ShortcutRow(
                    icon = Icons.Outlined.ViewInAr,
                    label = localized(language, "模型", "Model"),
                    value = state.defaultModel ?: "—",
                    onClick = { onNavigate("models") },
                )
                HorizontalDivider(color = hairline)
                ShortcutRow(
                    icon = Icons.Outlined.SystemUpdateAlt,
                    label = localized(language, "检查更新", "App updates"),
                    value = "v${com.hermes.client.BuildConfig.VERSION_NAME}",
                    onClick = { onNavigate("app_update") },
                )
            }
        }
        }
    }

    if (themeSheet) {
        ModalBottomSheet(onDismissRequest = { themeSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    localized(language, "主题", "Theme"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
                listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
                    ListItem(
                        leadingContent = { RadioButton(selected = themeMode == mode, onClick = null) },
                        headlineContent = { Text(themeLabel(mode, language)) },
                        modifier = Modifier.clickable { vm.setThemeMode(mode); themeSheet = false },
                    )
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode, language: com.hermes.client.ui.localization.AppLanguage): String = when (mode) {
    ThemeMode.SYSTEM -> localized(language, "随系统", "System")
    ThemeMode.LIGHT -> localized(language, "浅色", "Light")
    ThemeMode.DARK -> localized(language, "深色", "Dark")
}

@Composable
private fun StatCell(
    title: String,
    value: String,
    sub: String?,
    valueSp: Float,
    subSp: Float,
    onValueOverflow: () -> Unit,
    onSubOverflow: () -> Unit,
    modifier: Modifier = Modifier,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // Sizes are CONTROLLED by the parent so both cells stay in lockstep; the wrap-to-two-lines
    // fallback stays per-cell (only the overlong value needs it).
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FitText(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
            fontSizeSp = valueSp, minSp = 13f, onOverflow = onValueOverflow,
            modifier = Modifier.padding(top = 5.dp, bottom = 4.dp),
        )
        sub?.let {
            FitText(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(color = subColor),
                fontSizeSp = subSp, minSp = 11f, onOverflow = onSubOverflow,
            )
        }
    }
}

@Composable
private fun ShortcutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    badge: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 21.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 20.dp),
        )
        // Neutral badge — same palette as the rest of the sheet, no alert colour.
        badge?.let {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        value?.let {
            AutoShrinkText(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                minFontSize = 12.sp,
                modifier = Modifier.widthIn(max = 160.dp).padding(start = 8.dp),
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp).size(22.dp),
        )
    }
}

/**
 * Fit strategy, in order: (1) step the font size down to [minFontSize] on ONE line; (2) still
 * overflowing at the floor → keep the floor size and wrap to TWO lines; (3) only a two-line
 * overflow ellipsizes. Numbers and device names should never truncate before all of that.
 */
/**
 * Parent-controlled fit text: font size comes from shared state (both stat cells shrink in
 * lockstep via [onOverflow]); at [minSp] the text wraps to two lines; only a two-line overflow
 * ellipsizes.
 */
@Composable
private fun FitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    fontSizeSp: Float,
    minSp: Float,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lines by remember(text) { mutableStateOf(1) }
    Text(
        text,
        style = style.copy(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.25f).sp),
        maxLines = lines,
        softWrap = lines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            // With overflow=Ellipsis, didOverflowWidth is FALSE once ellipsized —
            // isLineEllipsized is the signal that actually fires.
            val overflowed = result.didOverflowWidth ||
                result.isLineEllipsized(result.lineCount - 1)
            if (overflowed) {
                if (fontSizeSp > minSp) onOverflow() else if (lines == 1) lines = 2
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun AutoShrinkText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    // Re-measure from the top size whenever the text changes.
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    var lines by remember(text) { mutableStateOf(1) }
    Text(
        text,
        style = style.copy(
            fontSize = fontSize,
            // Keep the leading proportional once shrunk/wrapped; the original lineHeight
            // belongs to the full-size single-line form.
            lineHeight = (fontSize.value * 1.25f).sp,
        ),
        maxLines = lines,
        softWrap = lines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            // With overflow=Ellipsis, didOverflowWidth is FALSE once the text has been
            // ellipsized — isLineEllipsized is the signal that actually fires.
            val overflowed = result.didOverflowWidth ||
                result.isLineEllipsized(result.lineCount - 1)
            if (overflowed) {
                if (fontSize.value > minFontSize.value) {
                    fontSize = (fontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
                } else if (lines == 1) {
                    lines = 2
                }
            }
        },
        modifier = modifier,
    )
}

/** "242 ms" below a second, "1.1 s" above — four-digit ms never earns its width. */
private fun formatLatency(ms: Long): String =
    if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

private fun compactTokens(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 1_000 -> "%.1fK".format(v / 1_000.0)
    else -> v.toString()
}
