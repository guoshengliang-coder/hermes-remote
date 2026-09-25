package com.hermes.client.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.domain.HistoryLocator

/** Supplied by ChatScreen; gallery and screenshot tests have no network loader. */
internal val LocalHistoryFullRowLoader = staticCompositionLocalOf<(suspend (HistoryLocator) -> MessageDto?)?> { null }

internal class FullHistoryRowState {
    var row by mutableStateOf<MessageDto?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<AppError?>(null)
    var attempt by mutableIntStateOf(0)
    fun retry() { attempt++ }
}

@Composable
internal fun rememberFullHistoryRow(source: HistoryLocator?, expanded: Boolean): FullHistoryRowState {
    val loader = LocalHistoryFullRowLoader.current
    val state = remember(source) { FullHistoryRowState() }
    LaunchedEffect(source, expanded, state.attempt, loader) {
        if (!expanded || source == null || state.row != null) return@LaunchedEffect
        state.loading = true
        state.error = null
        try {
            state.row = loader?.invoke(source)?.takeIf { it.id == source.rowId }
            if (state.row == null) state.error = AppError(AppErrorCode.HISTORY_PREVIEW_FAILED, retryable = true, stage = "history_full_row")
        } catch (cause: Exception) {
            state.error = AppError(AppErrorCode.HISTORY_PREVIEW_FAILED, retryable = true,
                technicalCause = cause.javaClass.simpleName, stage = "history_full_row")
        } finally {
            state.loading = false
        }
    }
    return state
}
