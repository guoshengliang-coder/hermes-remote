package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.GatewayHealth

/** Visual severity of the strip. Kept separate from color so it is unit-testable. */
enum class HealthStripStyle { ERROR, NEUTRAL, NONE }

fun healthStripStyle(health: GatewayHealth): HealthStripStyle = when (health) {
    is GatewayHealth.GatewayUnreachable -> HealthStripStyle.ERROR
    GatewayHealth.DeviceOffline -> HealthStripStyle.NEUTRAL
    is GatewayHealth.Healthy, GatewayHealth.Unknown -> HealthStripStyle.NONE
}

/** Short strip label; null when nothing should show. */
fun healthStripLabel(health: GatewayHealth, zh: Boolean = false): String? = when (health) {
    GatewayHealth.DeviceOffline -> if (zh) "设备已离线" else "You're offline"
    is GatewayHealth.GatewayUnreachable ->
        if (health.detail == "unauthorized") {
            if (zh) "Relay 拒绝了凭证" else "Gateway unauthorized"
        } else {
            if (zh) "Relay 暂时无法连接" else "Gateway unreachable"
        }
    is GatewayHealth.Healthy, GatewayHealth.Unknown -> null
}

/** Sheet detail copy for the current state. */
fun healthSheetBody(health: GatewayHealth, zh: Boolean = false): String = when (health) {
    is GatewayHealth.Healthy -> buildString {
        append(
            when {
                health.running -> if (zh) "Relay 运行中" else "Gateway running"
                else -> if (zh) "Relay 可达，但服务未运行" else "Gateway reachable, not running"
            },
        )
        health.version?.let { append(" · v").append(it) }
        health.latencyMs?.let { append(" · ").append(it).append(" ms") }
    }
    is GatewayHealth.GatewayUnreachable ->
        if (health.detail == "unauthorized") {
            if (zh) "Relay 拒绝了会话令牌（未授权），请检查 App Token。" else "The gateway rejected the session token (unauthorized)."
        } else {
            if (zh) "Relay 没有响应，可能正在重启或暂时不可用。" else "The gateway isn't responding. It may be down or restarting."
        }
    GatewayHealth.DeviceOffline -> if (zh) "设备当前没有网络，恢复后 Hermes 会自动重连。" else "Your device is offline — Hermes will reconnect automatically."
    GatewayHealth.Unknown -> if (zh) "检查中…" else "Checking…"
}

/**
 * Slim status strip shown across all screens ONLY when unhealthy. Applies its own status-bar
 * padding so it sits below the system bar; callers should consume the status-bars inset for the
 * content beneath it so the screen's own top bar does not add a second gap.
 */
@Composable
fun HealthStrip(health: GatewayHealth, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val zh = com.hermes.client.ui.localization.LocalAppLanguage.current == com.hermes.client.ui.localization.AppLanguage.ZH
    val label = healthStripLabel(health, zh) ?: return
    val bg = when (healthStripStyle(health)) {
        HealthStripStyle.ERROR -> MaterialTheme.colorScheme.errorContainer
        HealthStripStyle.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        HealthStripStyle.NONE -> Color.Transparent
    }
    val fg = when (healthStripStyle(health)) {
        HealthStripStyle.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = fg, modifier = Modifier.padding(end = 8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(end = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = fg)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSheet(health: GatewayHealth, onRecheck: () -> Unit, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val zh = com.hermes.client.ui.localization.LocalAppLanguage.current == com.hermes.client.ui.localization.AppLanguage.ZH
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                healthStripLabel(health, zh) ?: "Gateway",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                healthSheetBody(health, zh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRecheck) {
                    Text(if (zh) "重新检查" else "Re-check", color = accent, textAlign = TextAlign.End)
                }
            }
        }
    }
}
