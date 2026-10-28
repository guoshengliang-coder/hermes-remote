package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedSummary

/** Local cleanup is deliberately named differently from withdrawing a provably unsent turn. */
@Composable
internal fun UnknownDeliveryActions(
    messageId: String,
    recovery: MessageDeliveryRecovery,
    onCheck: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCopyDiagnostic: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    var confirmRemoval by remember(messageId) { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(localized(language, "投递结果未知", "Delivery unknown"),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(AppErrorCode.MESSAGE_OUTCOME_UNKNOWN.compact, maxLines = 1, softWrap = false,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recovery.checked) {
            Text(localized(language, "已同步会话，仍无法确认这条消息是否送达。", "Conversation synced. This message's delivery still cannot be confirmed."),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recovery.error?.let { error ->
            if (error.code != AppErrorCode.UNKNOWN_NOTICE_REMOVE_FAILED) {
                Text(localized(language, "核对未完成", "Status check failed"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(error.localizedSummary(language), modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Text(error.code.compact, maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onCopyDiagnostic(error.sanitizedDiagnostic()) }) {
                Text(localized(language, "复制诊断信息", "Copy diagnostics"))
            }
        }
        // Separate, wrapping action lines: a 360dp viewport with 1.3x text cannot fit both labels.
        TextButton(onClick = { onCheck(messageId) }, enabled = !recovery.checking && !recovery.removing) {
            Text(if (recovery.checking) localized(language, "正在核对…", "Checking…")
                else localized(language, "重新核对", "Check status"),
                color = if (recovery.checking) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current)
        }
        TextButton(onClick = { confirmRemoval = true }, enabled = !recovery.removing) {
            Text(if (recovery.removing) localized(language, "正在移除…", "Removing…")
                else localized(language, "移除本机提示", "Remove local notice"),
                color = if (recovery.removing) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current)
        }
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(localized(language, "移除本机提示？", "Remove local notice?")) },
            text = { Text(localized(language,
                "只移除此手机上的未知提示。不会取消 Mac 上可能已开始的任务，不会删除服务端历史，也不会自动重发。移除前可先复制原消息。",
                "This removes only the unknown notice on this phone. It won't cancel work that may have started on the Mac, delete server history, or resend the message. You can copy the original message first.")) },
            confirmButton = { TextButton(onClick = { confirmRemoval = false; onRemove(messageId) }) {
                Text(localized(language, "移除本机提示", "Remove local notice"))
            } },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) {
                Text(localized(language, "取消", "Cancel"))
            } },
        )
    }
}
