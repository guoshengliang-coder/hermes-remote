package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

data class DraftModelReadState(val loading: Boolean = true, val error: AppError? = null)

/** Lives in the model picker, so a failed informational read never blocks the composer. */
@Composable
internal fun DraftModelReadStatus(state: DraftModelReadState, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    if (state.loading || state.error != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                state.error?.localizedMessage(language) ?: localized(language, "正在读取模型…", "Reading model…"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.error != null) TextButton(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
        }
    }
}
