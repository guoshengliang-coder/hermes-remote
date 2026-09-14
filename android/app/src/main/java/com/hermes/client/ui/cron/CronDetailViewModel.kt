package com.hermes.client.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
import com.hermes.client.ui.localization.asLocalizedText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CronDetailUiState(
    val job: CronJobDto? = null,
    val runs: List<CronRunDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val message: LocalizedText? = null,
    /**
     * The last failed action (pause / resume / run now / delete), kept so the page can show its
     * code and — behind 展开 — the cause, rather than letting a snackbar carry the only account of
     * it and then disappear (HG-51).
     */
    val actionError: AppError? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class CronDetailViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CronDetailUiState())
    val state: StateFlow<CronDetailUiState> = _state.asStateFlow()

    private var jobId: String = ""
    private val profile: String? get() = profileManager.active.value

    fun load(id: String) {
        jobId = id
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val job = runCatching { tools.cronJob(id, profile) }.getOrNull()
            val runs = runCatching { tools.cronRuns(id, profile) }.getOrNull() ?: emptyList()
            _state.value = if (job == null) {
                _state.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, stage = "cron_detail_load"),
                )
            } else {
                _state.value.copy(job = job, runs = runs, loading = false)
            }
        }
    }

    /**
     * Turn a failed action into an error that says what actually happened (HG-51).
     *
     * This used to print a flat 「操作失败（HR-RPC-001）」 for every throwable — a transport code
     * asserting a cause nobody had read off the wire. It covered a refused request, a timeout, a
     * missing endpoint and an unconfigured gateway alike, and threw the real reason away. A user
     * reporting it therefore had nothing to report: the request was not logged either.
     *
     * Now the server's own stable code wins when this build knows it, `HR-CRON-003` is the honest
     * floor when it does not, and the cause is kept for the details toggle. [stage] names the
     * action so the diagnostic says which button was pressed.
     */
    private fun actionError(error: Throwable, stage: String): AppError {
        val reported = (error as? HermesApiException)?.errorCode
        return AppError(
            code = AppErrorCode.fromValue(reported) ?: AppErrorCode.CRON_ACTION_FAILED,
            // Honest default: most of these are worth pressing again, and the two the server marks
            // otherwise (a deleted job, a refused binding) carry their own code and their own
            // retryability with it.
            retryable = true,
            technicalCause = error.message,
            stage = stage,
        )
    }

    private fun act(success: LocalizedText, stage: String, block: suspend () -> Unit) =
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    _state.value = _state.value.copy(message = success, actionError = null)
                    load(jobId)
                }
                .onFailure { error ->
                    val failure = actionError(error, stage)
                    _state.value = _state.value.copy(
                        message = failure.asLocalizedText(),
                        actionError = failure,
                    )
                }
        }

    fun pause() = act(localizedText("已暂停", "Paused"), "cron_pause") { tools.pauseCron(jobId, profile) }
    fun resume() = act(localizedText("已恢复", "Resumed"), "cron_resume") { tools.resumeCron(jobId, profile) }
    fun trigger() = act(localizedText("已触发", "Triggered"), "cron_trigger") { tools.triggerCron(jobId, profile) }

    fun delete() = viewModelScope.launch {
        runCatching { tools.deleteCron(jobId, profile) }
            .onSuccess { _state.value = _state.value.copy(deleted = true) }
            .onFailure { error ->
                val failure = actionError(error, "cron_delete")
                _state.value = _state.value.copy(
                    message = failure.asLocalizedText(),
                    actionError = failure,
                )
            }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
