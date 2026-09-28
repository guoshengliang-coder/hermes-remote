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
import com.hermes.client.ui.localization.localizedSummary
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

/**
 * Friendlier, sentence-form copy for the chat offline/error banner (vs the terse [connectionLabel]).
 *
 * The two coded states resolve to the registered connection copy rather than re-typing it, so the
 * banner, the health sheet and the startup page can never disagree about the same code. The code
 * stays inline because the banner has no room for a separate code line.
 */
fun bannerLabel(state: ConnectionState, zh: Boolean = false): String {
    val language = if (zh) AppLanguage.ZH else AppLanguage.EN
    return when (state) {
        ConnectionState.Disconnected -> bannerLine(AppErrorCode.CONNECTION_INTERRUPTED, language)
        is ConnectionState.Error -> bannerLine(AppErrorCode.CONNECTION_FAILED, language)
        ConnectionState.Connecting -> localized(language, "正在连接服务…", "Connecting to the service…")
        ConnectionState.Reconnecting -> localized(language, "正在重新连接并恢复会话…", "Reconnecting and restoring the conversation…")
        ConnectionState.Connected -> localized(language, "已连接", "Connected")
    }
}

/** A registered summary plus its stable code, the shape the chat banner uses for a coded state. */
private fun bannerLine(code: AppErrorCode, language: AppLanguage): String {
    val summary = AppError(code, retryable = true).localizedSummary(language)
    return if (language == AppLanguage.ZH) "$summary（${code.value}）" else "$summary (${code.value})"
}

fun connectionBannerModel(state: ConnectionState, zh: Boolean = false): ConnectionBannerModel = when (state) {
    ConnectionState.Connecting, ConnectionState.Reconnecting ->
        ConnectionBannerModel(bannerLabel(state, zh), progress = true)
    // Calm styling on purpose: an interruption restores itself (the foreground owner reconnects
    // immediately), so it is a progress state that happens to carry a code — not a failure. Only
    // ConnectionState.Error, which means the Relay actually refused us, gets the error colours.
    // The AppError is kept so "详情 / Details" and copy-diagnostics stay available (HR-CONN-004).
    ConnectionState.Disconnected -> ConnectionBannerModel(
        bannerLabel(state, zh),
        progress = true,
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
