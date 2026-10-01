package com.hermes.client.ui.settings

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OutputHapticsSettingsState(
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: AppError? = null,
) {
    val feedbackEnabled: Boolean get() = enabled && loaded && !saving && error == null
}

/** Keeps read/write failures out of the chat loop; a failed write can retry its exact choice. */
class OutputHapticsSettings(
    private val preference: Flow<Boolean>,
    private val write: suspend (Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(OutputHapticsSettingsState())
    val state = mutableState.asStateFlow()
    private var reader: Job? = null
    @Volatile private var failedChoice: Boolean? = null

    init { load() }

    private fun load() {
        reader?.cancel()
        mutableState.value = OutputHapticsSettingsState()
        reader = scope.launch {
            preference.catch { cause ->
                mutableState.update { it.copy(loaded = false, error = failure("output_haptics_read", cause)) }
            }.collect { enabled ->
                mutableState.update { it.copy(enabled = enabled, loaded = true) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (state.value.saving || !state.value.loaded) return
        failedChoice = enabled
        mutableState.update { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                write(enabled)
                failedChoice = null
                mutableState.update { it.copy(enabled = enabled, saving = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                mutableState.update { it.copy(saving = false, error = failure("output_haptics_save", cause)) }
            }
        }
    }

    fun retry() {
        if (state.value.saving) return
        val choice = failedChoice
        if (choice == null) load() else setEnabled(choice)
    }

    private fun failure(stage: String, cause: Throwable) = AppError(
        AppErrorCode.OUTPUT_HAPTICS_SETTINGS_FAILED,
        retryable = true,
        technicalCause = cause.message,
        stage = stage,
    )
}
