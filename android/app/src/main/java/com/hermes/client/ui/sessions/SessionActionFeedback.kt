package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.ui.components.reduceMotion
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.localization.l10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class SessionActionFeedback(private val scope: CoroutineScope) {
    private var job: Job? = null
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppError?>(null)
        private set

    fun submit(work: suspend () -> AppError?, success: () -> Unit) {
        if (pending || error?.let(::isArchiveFailure) == true) return
        pending = true
        error = null
        job = scope.launch {
            try {
                val result = work()
                ensureActive()
                error = result
                if (result == null) success()
            } finally {
                pending = false
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun clearError() { error = null }
}

@Composable
internal fun rememberSessionActionFeedback(vararg keys: Any?): SessionActionFeedback {
    val scope = rememberCoroutineScope()
    val feedback = remember(scope, *keys) { SessionActionFeedback(scope) }
    DisposableEffect(feedback) { onDispose { feedback.cancel() } }
    return feedback
}

@Composable
internal fun SessionActionProgress(label: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val indicator = Modifier.size(14.dp).clearAndSetSemantics {}
        val track = color.copy(alpha = .28f)
        if (reduceMotion()) {
            CircularProgressIndicator(
                progress = { .75f }, modifier = indicator, strokeWidth = 2.dp, color = color, trackColor = track,
            )
        } else {
            CircularProgressIndicator(modifier = indicator, strokeWidth = 2.dp, color = color, trackColor = track)
        }
        Text(label, color = color)
    }
}


@Composable
internal fun SessionActionError(feedback: SessionActionFeedback, onRefresh: () -> Unit) {
    val error = feedback.error ?: return
    var details by remember(error) { mutableStateOf(false) }
    Text(error.localizedMessage(LocalAppLanguage.current), color = MaterialTheme.colorScheme.error)
    if (isArchiveFailure(error)) {
        TextButton(onClick = { details = !details }) { Text(l10n("查看详情", "View details")) }
        if (details) Text(error.sanitizedDiagnostic())
        TextButton(onClick = onRefresh) { Text(l10n("刷新状态", "Refresh state")) }
    }
}

@Composable
internal fun SessionActionDialog(
    feedback: SessionActionFeedback,
    title: String,
    action: String,
    pendingLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val canConfirm = enabled && !feedback.pending && feedback.error?.let(::isArchiveFailure) != true
    val dismiss = {
        if (!feedback.pending) {
            feedback.clearError()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                SessionActionError(feedback) {
                    dismiss()
                    onRefresh()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = onConfirm,
            ) {
                if (feedback.pending) SessionActionProgress(pendingLabel, color)
                else if (destructive && canConfirm) Text(action, color = color)
                else Text(action)
            }
        },
        dismissButton = {
            TextButton(enabled = !feedback.pending, onClick = dismiss) { Text(l10n("取消", "Cancel")) }
        },
    )
}
