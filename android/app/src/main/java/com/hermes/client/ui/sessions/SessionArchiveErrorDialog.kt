package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

internal fun isArchiveFailure(error: AppError): Boolean = error.code in setOf(
    AppErrorCode.SESSION_MUTATION_UNCONFIRMED, AppErrorCode.SESSION_MUTATION_REFUSED,
    AppErrorCode.ARCHIVE_INCOMPATIBLE, AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED,
    AppErrorCode.SESSION_ARCHIVE_REFUSED,
)

/** Refresh reads state only. An ambiguous archive must never expose a blind write retry. */
@Composable
fun SessionArchiveErrorDialog(error: AppError, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    val language = LocalAppLanguage.current
    var details by remember(error) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (error.code in setOf(AppErrorCode.SESSION_MUTATION_UNCONFIRMED, AppErrorCode.SESSION_MUTATION_REFUSED))
            localized(language, "会话操作", "Session operation") else localized(language, "归档状态", "Archive state")) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(error.localizedMessage(language))
                TextButton(onClick = { details = !details }) {
                    Text(localized(language, "查看详情", "View details"))
                }
                if (details) Text(error.sanitizedDiagnostic())
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text(localized(language, "刷新状态", "Refresh state")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "关闭", "Close")) } },
    )
}
