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

    val dark = isSystemInDarkTheme()
    // The base design's containers are NEUTRAL light grey; in dark theme fall back to the
    // theme's surfaceVariant so contrast holds.
    val tile = if (dark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF5F4F2)
    val hairline = if (dark) MaterialTheme.colorScheme.outlineVariant else Color(0xFFEAE8E4)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ModalDrawerSheet(
        drawerState = drawerState ?: androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open),
        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp),
        drawerShape = RoundedCornerShape(topStart = 0.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 0.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
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
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
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
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(active, size = 56.dp)
                    Column(Modifier.weight(1f).padding(start = 18.dp)) {
                        Text(active ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp))
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
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Row(
                    Modifier.padding(vertical = 18.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                ) {
                    StatCell(
                        title = localized(language, "本周用量", "This week"),
                        value = state.weekTokens?.let { compactTokens(it) } ?: "—",
                        sub = state.weekCost?.let { localized(language, "预估 $%.2f".format(it), "est. $%.2f".format(it)) },
                        modifier = Modifier.weight(1f).clickable { onNavigate("usage") },
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
                    val healthy = health as? GatewayHealth.Healthy
                    StatCell(
                        title = localized(language, "远程设备", "Remote device"),
                        value = state.deviceId ?: localized(language, "未连接", "Offline"),
                        sub = when {
                            healthy != null && state.deviceId != null ->
                                localized(language, "已连接", "Connected") + (healthy.latencyMs?.let { " · " + formatLatency(it) } ?: "")
                            state.deviceId == null -> localized(language, "连接器离线", "Connector offline")
                            else -> null
                        },
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
    modifier: Modifier = Modifier,
    subColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // Type ramp derived from the reference by INTERNAL ratio (label anchored at 15sp):
    // 15sp label / 23sp bold value / 15sp sub. The value auto-shrinks (never truncates a
    // number) down to 17sp so narrow screens and long device names still fit their cell.
    // Content completeness beats type size: on large system font scales the fixed cell width
    // shrinks in sp terms, so BOTH the value and the sub-line auto-shrink (deep floors) instead
    // of ellipsizing "mac-…" / "已连接 · 2…".
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutoShrinkText(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 23.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
            minFontSize = 13.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 4.dp),
        )
        sub?.let {
            AutoShrinkText(
                it,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, color = subColor),
                minFontSize = 11.sp,
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
 * Single-line text that steps its font size down (never below [minFontSize]) until it fits,
 * instead of ellipsizing — numbers and device names must stay whole on narrow widths.
 */
@Composable
private fun AutoShrinkText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    // Re-measure from the top size whenever the text OR the available width regime changes.
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text,
        style = style.copy(fontSize = fontSize),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            // With overflow=Ellipsis, didOverflowWidth is FALSE once the text has been
            // ellipsized — isLineEllipsized is the signal that actually fires.
            val overflowed = result.didOverflowWidth || result.isLineEllipsized(0)
            if (overflowed && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
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
