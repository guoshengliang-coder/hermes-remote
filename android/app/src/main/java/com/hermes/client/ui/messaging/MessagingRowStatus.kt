package com.hermes.client.ui.messaging

import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

/**
 * A messaging platform's at-a-glance state.
 *
 * Hermes computes this server-side and returns it as `state`; the app used to ignore that field and
 * derive its own from `enabled && gateway_running`. That derivation cannot see the difference
 * between "connected" and "saved but the gateway has not been restarted yet", so a freshly
 * configured channel — and one whose adapter failed to start — both rendered as a green 已连接
 * while the bot stayed silent. Trust the server value; derive nothing.
 */
enum class MessagingRowStatus { CONNECTED, PENDING_RESTART, FAILED, GATEWAY_STOPPED, NOT_CONFIGURED, DISABLED, UNKNOWN }

/** Pure: [MessagingPlatformDto.state] mapped to a row status. Unknown/absent values stay UNKNOWN. */
fun messagingRowStatus(platform: MessagingPlatformDto): MessagingRowStatus =
    when (platform.state?.trim()?.lowercase()?.ifBlank { null }) {
        "connected" -> MessagingRowStatus.CONNECTED
        "pending_restart" -> MessagingRowStatus.PENDING_RESTART
        "startup_failed", "error", "failed" -> MessagingRowStatus.FAILED
        "gateway_stopped" -> MessagingRowStatus.GATEWAY_STOPPED
        "not_configured" -> MessagingRowStatus.NOT_CONFIGURED
        "disabled" -> MessagingRowStatus.DISABLED
        // A Hermes that predates the `state` field, or a value added upstream after this release.
        // Fall back to what the older fields can honestly say, never to "connected".
        null -> if (!platform.configured) MessagingRowStatus.NOT_CONFIGURED
            else if (!platform.enabled) MessagingRowStatus.DISABLED
            else MessagingRowStatus.UNKNOWN
        else -> MessagingRowStatus.UNKNOWN
    }

/** Short label for the row. Never claims a connection the server did not report. */
fun messagingStatusText(status: MessagingRowStatus): LocalizedText = when (status) {
    MessagingRowStatus.CONNECTED -> localizedText("已连接", "Connected")
    MessagingRowStatus.PENDING_RESTART -> localizedText("已保存 · 重启网关后连接", "Saved · connects after a gateway restart")
    MessagingRowStatus.FAILED -> localizedText("未连接", "Not connected")
    MessagingRowStatus.GATEWAY_STOPPED -> localizedText("网关未运行", "Gateway is not running")
    MessagingRowStatus.NOT_CONFIGURED -> localizedText("未配置", "Not configured")
    MessagingRowStatus.DISABLED -> localizedText("已配置 · 未启用", "Configured · not enabled")
    MessagingRowStatus.UNKNOWN -> localizedText("状态未知", "Status unknown")
}
