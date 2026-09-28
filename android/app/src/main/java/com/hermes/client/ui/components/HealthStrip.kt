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
import androidx.compose.material.icons.rounded.Warning
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
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.network.HermesContractNotice
import com.hermes.client.data.network.HermesContractSeverity
import com.hermes.client.data.network.isUnhealthy
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedShortLabel
import com.hermes.client.ui.localization.localizedSummary
import com.hermes.client.ui.localization.localizedSummaryForValue

/** The strip and sheet take a `zh` flag; the catalogue takes [AppLanguage]. */
private fun language(zh: Boolean): AppLanguage = if (zh) AppLanguage.ZH else AppLanguage.EN

/** Visual severity of the strip. Kept separate from color so it is unit-testable. */
enum class HealthStripStyle { ERROR, NEUTRAL, NONE }

/**
 * The contract notice only speaks while the Relay itself is fine: an unreachable gateway is the
 * more basic problem, and a report fetched before it went away says nothing about now.
 */
private fun contractShown(health: GatewayHealth, contract: HermesContractNotice?): HermesContractNotice? =
    contract?.takeIf { !health.isUnhealthy() }

/** Whether the shell should draw the strip at all. */
fun healthStripVisible(health: GatewayHealth, contract: HermesContractNotice? = null): Boolean =
    healthStripStyle(health, contract) != HealthStripStyle.NONE

fun healthStripStyle(health: GatewayHealth, contract: HermesContractNotice? = null): HealthStripStyle =
    when (health) {
        is GatewayHealth.GatewayUnreachable -> HealthStripStyle.ERROR
        GatewayHealth.DeviceOffline -> HealthStripStyle.NEUTRAL
        is GatewayHealth.Healthy, GatewayHealth.Unknown -> when (contractShown(health, contract)?.severity) {
            HermesContractSeverity.BREAKING -> HealthStripStyle.ERROR
            HermesContractSeverity.DEGRADED -> HealthStripStyle.NEUTRAL
            null -> HealthStripStyle.NONE
        }
    }

/** Short strip label; null when nothing should show. */
fun healthStripLabel(health: GatewayHealth, zh: Boolean = false, contract: HermesContractNotice? = null): String? =
    when (health) {
        GatewayHealth.DeviceOffline -> AppErrorCode.DEVICE_OFFLINE.localizedShortLabel(language(zh))
        is GatewayHealth.GatewayUnreachable -> unreachableStripLabel(health.detail, zh)
        is GatewayHealth.Healthy, GatewayHealth.Unknown -> when (contractShown(health, contract)?.severity) {
            HermesContractSeverity.BREAKING -> if (zh) "Mac 上的 Hermes 不兼容" else "Hermes on the Mac is incompatible"
            HermesContractSeverity.DEGRADED -> if (zh) "Mac 上的 Hermes 部分功能不可用" else "Some Hermes features are unavailable"
            null -> null
        }
    }

/**
 * Strip label for a gateway the probe could not reach. A diagnosed `HR-CONN-*` code resolves to
 * its registered short form (docs/ERROR_HANDLING.md), so the strip names the link the diagnosis
 * actually points at — the phone's network, the Relay, the Mac, or Hermes on the Mac — instead of
 * re-typing that phrase here, where it used to drift from the sheet and the registry.
 *
 * The non-code details keep their own copy: `unauthorized` is an auth answer, not a diagnosis,
 * and a probe that ran without the public-check client has no link to name at all.
 */
fun unreachableStripLabel(detail: String?, zh: Boolean = false): String {
    val lang = language(zh)
    return when (detail) {
        "unauthorized" -> localized(lang, "连接凭据无效", "Connection credentials invalid")
        null, "", "unreachable" -> localized(lang, "暂时连不上服务", "Couldn't reach the service")
        else -> AppErrorCode.fromValue(detail)?.localizedShortLabel(lang)
            ?: localized(lang, "暂时连不上服务", "Couldn't reach the service")
    }
}

/** Localized name for a Connector feature key; an unknown key is named generically, never raw. */
fun hermesContractFeatureLabel(feature: String, zh: Boolean): String = when (feature) {
    "status" -> if (zh) "连接状态" else "status"
    "sessions" -> if (zh) "会话列表" else "conversation list"
    "history" -> if (zh) "历史记录" else "history"
    "search" -> if (zh) "搜索" else "search"
    "profiles" -> if (zh) "身份" else "profiles"
    "projects" -> if (zh) "项目文件夹" else "project folders"
    "config" -> if (zh) "设置" else "settings"
    "cron" -> if (zh) "定时任务" else "scheduled tasks"
    "models" -> if (zh) "模型" else "models"
    "tools" -> if (zh) "工具" else "tools"
    "skills" -> if (zh) "技能" else "skills"
    "analytics" -> if (zh) "用量统计" else "usage"
    "voice" -> if (zh) "语音输入" else "voice input"
    "messaging" -> if (zh) "消息渠道" else "messaging channels"
    else -> if (zh) "其他功能" else "other features"
}

/**
 * The contract part of the sheet: the registered explanation, then which features are affected.
 * The code itself is rendered on its own line by [HealthSheet] (docs/DESIGN.md §5.11).
 */
fun hermesContractSheetBody(contract: HermesContractNotice, zh: Boolean): String = buildString {
    append(contract.error.localizedSummary(if (zh) AppLanguage.ZH else AppLanguage.EN))
    val features = contract.features.map { hermesContractFeatureLabel(it, zh) }.distinct()
    if (features.isNotEmpty()) {
        append("\n")
        append(if (zh) "受影响：" else "Affected: ")
        append(features.joinToString(if (zh) "、" else ", "))
    }
    contract.hermesVersion?.let { append("\n").append(if (zh) "Hermes 版本 " else "Hermes version ").append(it) }
}

/** Sheet detail copy for the current state. */
fun healthSheetBody(health: GatewayHealth, zh: Boolean = false, contract: HermesContractNotice? = null): String {
    contractShown(health, contract)?.let { return hermesContractSheetBody(it, zh) }
    return gatewaySheetBody(health, zh)
}

private fun gatewaySheetBody(health: GatewayHealth, zh: Boolean): String = when (health) {
    is GatewayHealth.Healthy -> buildString {
        append(
            when {
                health.running -> if (zh) "服务运行正常" else "Service running"
                else -> if (zh) "服务地址可访问，但服务未运行" else "Service address reachable, but service not running"
            },
        )
        health.version?.let { append(" · v").append(it) }
        health.latencyMs?.let { append(" · ").append(it).append(" ms") }
    }
    is GatewayHealth.GatewayUnreachable ->
        when (health.detail) {
            "unauthorized" -> if (zh) "连接凭据无效，请检查连接设置。" else "Connection credentials are invalid. Check connection settings."
            null, "", "unreachable" -> if (zh) "暂时连不上服务，尚不能确定原因。请稍后重试。" else "Couldn't reach the service. The cause is unclear. Try again later."
            else -> localizedSummaryForValue(health.detail, language(zh))
                ?: if (zh) "暂时连不上服务，尚不能确定原因。请稍后重试。" else "Couldn't reach the service. The cause is unclear. Try again later."
        }
    GatewayHealth.DeviceOffline -> AppErrorCode.DEVICE_OFFLINE.localizedSummary(language(zh))
    GatewayHealth.Unknown -> if (zh) "检查中…" else "Checking…"
}

fun healthErrorCode(health: GatewayHealth): String? = when (health) {
    GatewayHealth.DeviceOffline -> AppErrorCode.DEVICE_OFFLINE.value
    is GatewayHealth.GatewayUnreachable -> when (health.detail) {
        "unauthorized" -> "HR-AUTH-001"
        // A diagnosed code is already the identity; an undiagnosed probe is the generic failure.
        else -> AppErrorCode.fromValue(health.detail)?.value ?: AppErrorCode.CONNECTION_FAILED.value
    }
    is GatewayHealth.Healthy, GatewayHealth.Unknown -> null
}

/**
 * Slim status strip shown across all screens ONLY when unhealthy. Applies its own status-bar
 * padding so it sits below the system bar; callers should consume the status-bars inset for the
 * content beneath it so the screen's own top bar does not add a second gap.
 */
@Composable
fun HealthStrip(
    health: GatewayHealth,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contract: HermesContractNotice? = null,
) {
    val zh = com.hermes.client.ui.localization.LocalAppLanguage.current == com.hermes.client.ui.localization.AppLanguage.ZH
    val label = healthStripLabel(health, zh, contract) ?: return
    val style = healthStripStyle(health, contract)
    // The gateway states keep their cloud; a contract finding is about the Mac's Hermes, not the
    // network, so it carries a warning sign instead.
    val icon = if (health.isUnhealthy()) Icons.Rounded.CloudOff else Icons.Rounded.Warning
    val bg = when (style) {
        HealthStripStyle.ERROR -> MaterialTheme.colorScheme.errorContainer
        HealthStripStyle.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        HealthStripStyle.NONE -> Color.Transparent
    }
    val fg = when (style) {
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
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(end = 8.dp))
        // The label takes the remaining width and wraps, so a long label (English, large font
        // scale) can never push the chevron — the only sign the strip is tappable — off screen.
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = fg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSheet(
    health: GatewayHealth,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
    contract: HermesContractNotice? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val zh = com.hermes.client.ui.localization.LocalAppLanguage.current == com.hermes.client.ui.localization.AppLanguage.ZH
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = com.hermes.client.ui.components.hermesSheetState()) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                healthStripLabel(health, zh, contract) ?: "Gateway",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                healthSheetBody(health, zh, contract),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // The code on its own line, labelSmall in the secondary colour (docs/DESIGN.md §5.11).
            val errorCode = healthErrorCode(health) ?: contractShown(health, contract)?.error?.code?.value
            errorCode?.let { code ->
                Text(
                    code,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRecheck) {
                    Text(if (zh) "重新检查" else "Re-check", color = accent, textAlign = TextAlign.End)
                }
            }
        }
    }
}
