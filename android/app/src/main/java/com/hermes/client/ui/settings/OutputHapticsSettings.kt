package com.hermes.client.ui.settings

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.hermes.client.data.haptics.OutputHapticConfig
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    val config: OutputHapticConfig = OutputHapticConfig(),
) {
    val feedbackEnabled: Boolean get() = enabled && loaded && !saving && error == null
}

/** Keeps read/write failures out of the chat loop; a failed write can retry its exact choice. */
class OutputHapticsSettings(
    private val preference: Flow<Boolean>,
    private val write: suspend (Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val configPreference: Flow<OutputHapticConfig> = flowOf(OutputHapticConfig()),
    private val writeConfig: suspend (OutputHapticConfig) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(OutputHapticsSettingsState())
    val state = mutableState.asStateFlow()
    private var reader: Job? = null
    private sealed interface Choice {
        data class Enabled(val value: Boolean) : Choice
        data class Config(val value: OutputHapticConfig) : Choice
    }
    @Volatile private var failedChoice: Choice? = null

    init { load() }

    private fun load() {
        reader?.cancel()
        mutableState.value = OutputHapticsSettingsState()
        reader = scope.launch {
            combine(preference, configPreference) { enabled, config -> enabled to config }.catch { cause ->
                mutableState.update { it.copy(loaded = false, error = failure("output_haptics_read", cause)) }
            }.collect { (enabled, config) ->
                mutableState.update { it.copy(enabled = enabled, config = config.normalized(), loaded = true) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = save(Choice.Enabled(enabled))
    fun setConfig(config: OutputHapticConfig) = save(Choice.Config(config.normalized()))

    private fun save(choice: Choice) {
        if (state.value.saving || !state.value.loaded) return
        failedChoice = choice
        mutableState.update { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                when (choice) {
                    is Choice.Enabled -> write(choice.value)
                    is Choice.Config -> writeConfig(choice.value)
                }
                failedChoice = null
                mutableState.update { when (choice) {
                    is Choice.Enabled -> it.copy(enabled = choice.value, saving = false, error = null)
                    is Choice.Config -> it.copy(config = choice.value, saving = false, error = null)
                } }
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
        if (choice == null) load() else save(choice)
    }

    private fun failure(stage: String, cause: Throwable) = AppError(
        AppErrorCode.OUTPUT_HAPTICS_SETTINGS_FAILED,
        retryable = true,
        technicalCause = cause.message,
        stage = stage,
    )
}
