package com.hermes.client.ui.nav

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The card page (modal drawer off the session list) — the app's ONLY profile-switch point,
 * plus the account-level tiles and entries: current-identity hero, switch list, usage/device
 * tiles, and the cron/settings/update entry rows (icon + title + optional badge + chevron).
 */
@Composable
fun CardPage(
    onNavigate: (String) -> Unit,
    drawerState: androidx.compose.material3.DrawerState? = null,
    vm: CardPageViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val switchFailed by vm.switchFailed.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val profileActivity by vm.profileActivity.collectAsStateWithLifecycle()

    LaunchedEffect(switchFailed) {
        switchFailed?.let {
            Toast.makeText(
                context,
                localized(language, "切换身份失败，仍在当前身份", "Couldn't switch profile — staying on the current one"),
                Toast.LENGTH_SHORT,
            ).show()
            vm.clearSwitchFailed()
        }
    }

    // Match the design: the sheet never spans the full screen (>=56dp of scrim stays visible),
    // sits on plain surface (the M3 default surfaceContainerLow reads purple against our mint
    // palette), and rounds only its end corners.
    // Wiring drawerState into the sheet is what gives it (predictive) back handling: the
    // system back gesture/key closes the card instead of finishing the activity.
    ModalDrawerSheet(
        drawerState = drawerState ?: androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open),
        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp),
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 0.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 0.dp,
        ),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding()) {
            // ── Hero: who am I right now ─────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(active, size = 48.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(active ?: "—", style = MaterialTheme.typography.titleLarge)
                    Text(
                        localized(language, "当前身份", "Active profile"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Switch list: every OTHER profile ─────────────────────────────────────
            val others = profiles.filter { it.name != active }
            if (others.isNotEmpty()) {
                Text(
                    localized(language, "切换身份", "SWITCH PROFILE"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
                others.forEach { p ->
                    val activity = profileActivity[p.name]
                    val subline = when {
                        activity == null -> null
                        activity.waiting > 0 -> localized(language, "${activity.waiting} 待处理", "${activity.waiting} waiting")
                        activity.running > 0 -> localized(language, "${activity.running} 个进行中", "${activity.running} running")
                        else -> null
                    }
                    ListItem(
                        leadingContent = { ProfileAvatar(p.name, size = 36.dp) },
                        headlineContent = { Text(p.name) },
                        supportingContent = subline?.let { { Text(it) } },
                        trailingContent = if (switching == p.name) ({
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }) else null,
                        modifier = Modifier.clickable(enabled = switching == null) { vm.switchProfile(p.name) },
                    )
                }
            }

            // ── Info tiles: weekly usage + remote device ─────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoTile(
                    title = localized(language, "本周用量", "This week"),
                    value = state.weekTokens?.let { compactTokens(it) + " token" } ?: "—",
                    sub = state.weekCost?.let { localized(language, "预估 $%.2f".format(it), "est. $%.2f".format(it)) },
                    modifier = Modifier.weight(1f).clickable { onNavigate("usage") },
                )
                val healthy = health as? GatewayHealth.Healthy
                InfoTile(
                    title = localized(language, "远程设备", "Remote device"),
                    value = state.deviceId ?: localized(language, "未连接", "Offline"),
                    sub = when {
                        healthy != null && state.deviceId != null ->
                            localized(language, "已连接", "Connected") + (healthy.latencyMs?.let { " · ${it} ms" } ?: "")
                        state.deviceId == null -> localized(language, "连接器离线", "Connector offline")
                        else -> null
                    },
                    subColor = if (healthy != null && state.deviceId != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // ── Entry rows: icon + title (+badge) + chevron, one shape for all ───────
            EntryRow(
                icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                label = localized(language, "定时任务", "Scheduled jobs"),
                badge = state.cronAlerts.takeIf { it > 0 },
                onClick = { onNavigate("cron") },
            )
            EntryRow(
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                label = localized(language, "设置", "Settings"),
                sub = localized(language, "连接、外观、消息渠道、智能体与工具…", "Connection, appearance, messaging, agents…"),
                onClick = { onNavigate("settings") },
            )
            EntryRow(
                icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                label = localized(language, "检查更新", "App updates"),
                sub = localized(language, "当前 v${com.hermes.client.BuildConfig.VERSION_NAME}", "Current v${com.hermes.client.BuildConfig.VERSION_NAME}"),
                onClick = { onNavigate("app_update") },
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoTile(
    title: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
    subColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = subColor, maxLines = 1) }
        }
    }
}

@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    sub: String? = null,
    badge: Int? = null,
) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(label) },
        supportingContent = sub?.let { { Text(it, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                badge?.let { Badge { Text(it.toString()) } }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun compactTokens(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 1_000 -> "%.1fK".format(v / 1_000.0)
    else -> v.toString()
}
