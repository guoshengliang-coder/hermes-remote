package com.hermes.client.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/** Copy shared by the sign-in page, the device picker and the account settings page. */

internal fun formatOtpCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun accountErrorText(
    code: String,
    language: AppLanguage,
): String {
    return when (code) {
        "HR-AUTH-003" -> localized(language, "登录已过期，请重新登录。", "Your session expired. Sign in again.")
        "HR-AUTH-004", "HR-BIND-004" -> localized(language, "这台手机的登录已被撤销，请重新登录。", "This phone's session was revoked. Sign in again.")
        "HR-AUTH-005" -> localized(language, "为保护账号，这台手机已安全退出。", "This phone was signed out for account safety.")
        "HR-AUTH-006" -> localized(language, "为确认是你本人，请重新验证当前账号的登录方式。", "Verify your sign-in identity again to confirm it's you.")
        "HR-AUTH-009" -> localized(language, "邮箱验证码无效或已过期，请重新获取。", "The email code is invalid or expired. Request a new code.")
        "HR-AUTH-010" -> localized(language, "登录邮件发送失败，请稍后重试。", "The sign-in email couldn't be sent. Try again shortly.")
        "HR-AUTH-011", "HR-ACCOUNT-003" -> localized(language, "此 Relay 尚未启用邮箱登录，可继续使用旧版连接。", "Email sign-in isn't enabled on this Relay yet. You can continue with the legacy connection.")
        "HR-ACCOUNT-012" -> localized(language, "此 Hermes GO 账号正在永久删除，已无法再次登录。", "This Hermes GO account is being permanently deleted and can no longer sign in.")
        "HR-BIND-001" -> localized(language, "这个账号还没有连接 Desktop，请先在 Mac 上打开 Hermes Go Desktop。", "This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac.")
        "HR-BIND-009" -> localized(language, "请先选择要使用的 Mac。", "Choose which Mac to use.")
        "HR-BIND-011" -> localized(language, "这台 Mac 已无法由当前账号使用，请选择其他设备。", "That Mac is no longer available to this account. Choose another device.")
        "HR-CONN-005" -> localized(language, "Mac 当前离线，请启动 Hermes Go Desktop 后重试。", "The Mac is offline. Start Hermes Go Desktop and try again.")
        else -> localized(language, "账号服务暂时不可用，请稍后重试。", "The account service is temporarily unavailable. Try again shortly.")
    }
}
