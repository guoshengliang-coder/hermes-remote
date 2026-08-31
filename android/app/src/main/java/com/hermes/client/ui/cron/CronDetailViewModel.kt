package com.hermes.client.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
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

    private fun act(success: LocalizedText, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { _state.value = _state.value.copy(message = success); load(jobId) }
            .onFailure { _state.value = _state.value.copy(message = localizedText("操作失败（HR-RPC-001）", "Operation failed (HR-RPC-001)")) }
    }

    fun pause() = act(localizedText("已暂停", "Paused")) { tools.pauseCron(jobId, profile) }
    fun resume() = act(localizedText("已恢复", "Resumed")) { tools.resumeCron(jobId, profile) }
    fun trigger() = act(localizedText("已触发", "Triggered")) { tools.triggerCron(jobId, profile) }

    fun delete() = viewModelScope.launch {
        runCatching { tools.deleteCron(jobId, profile) }
            .onSuccess { _state.value = _state.value.copy(deleted = true) }
            .onFailure { _state.value = _state.value.copy(message = localizedText("删除失败（HR-RPC-001）", "Delete failed (HR-RPC-001)")) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
