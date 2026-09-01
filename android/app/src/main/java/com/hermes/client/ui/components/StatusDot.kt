package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor

data class ConnectionBannerModel(
    val message: String,
    val progress: Boolean,
    val error: AppError? = null,
)

fun connectionLabel(state: ConnectionState, language: AppLanguage = AppLanguage.EN): String = when (state) {
    ConnectionState.Connected -> localized(language, "已连接", "Connected")
    ConnectionState.Connecting -> localized(language, "正在连接…", "Connecting…")
    ConnectionState.Reconnecting -> localized(language, "正在重新连接…", "Reconnecting…")
    ConnectionState.Disconnected -> localized(language, "离线", "Offline")
    is ConnectionState.Error -> localized(language, "连接错误", "Connection error")
}

/** Friendlier, sentence-form copy for the chat offline/error banner (vs the terse [connectionLabel]). */
fun bannerLabel(state: ConnectionState, zh: Boolean = false): String = when (state) {
    ConnectionState.Disconnected ->
        if (zh) "连接已中断，将自动恢复（HR-CONN-004）。" else "Connection interrupted; restoring automatically (HR-CONN-004)."
    is ConnectionState.Error ->
        if (zh) "无法连接 Relay（HR-CONN-002），请重试。" else "Couldn't connect to the Relay (HR-CONN-002). Retry."
    ConnectionState.Connecting -> if (zh) "正在连接 Relay…" else "Connecting to the Relay…"
    ConnectionState.Reconnecting -> if (zh) "正在重新连接并恢复会话…" else "Reconnecting and restoring the conversation…"
    ConnectionState.Connected -> if (zh) "已连接" else "Connected"
}

fun connectionBannerModel(state: ConnectionState, zh: Boolean = false): ConnectionBannerModel = when (state) {
    ConnectionState.Connecting, ConnectionState.Reconnecting ->
        ConnectionBannerModel(bannerLabel(state, zh), progress = true)
    ConnectionState.Disconnected -> ConnectionBannerModel(
        bannerLabel(state, zh),
        progress = false,
        error = AppError(AppErrorCode.CONNECTION_INTERRUPTED, retryable = true, stage = "websocket"),
    )
    is ConnectionState.Error -> ConnectionBannerModel(
        bannerLabel(state, zh),
        progress = false,
        error = AppError(
            AppErrorCode.CONNECTION_FAILED,
            retryable = true,
            technicalCause = state.reason,
            stage = "websocket",
        ),
    )
    ConnectionState.Connected -> ConnectionBannerModel(bannerLabel(state, zh), progress = false)
}

/**
 * The traffic-light meaning of a connection state. Pure so the mapping is testable without a
 * Compose runtime; the colours themselves live in [com.hermes.client.ui.theme.statusColor].
 */
fun connectionTone(state: ConnectionState): StatusTone = when (state) {
    ConnectionState.Connected -> StatusTone.GOOD
    ConnectionState.Connecting, ConnectionState.Reconnecting -> StatusTone.WARN
    else -> StatusTone.BAD
}

@Composable
fun StatusDot(state: ConnectionState, modifier: Modifier = Modifier, showLabel: Boolean = false) {
    // Was three hardcoded, theme-blind values; the dark tier they lacked made the green and red
    // dots sit at ~3.2:1 on the dark surface. Now both tiers come from the shared status palette.
    val color = statusColor(connectionTone(state))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, LocalContentColor.current, CircleShape),
        )
        if (showLabel) {
            Text(
                text = connectionLabel(state, LocalAppLanguage.current),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
