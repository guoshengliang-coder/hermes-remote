package com.hermes.client.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.HermesApiException
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

enum class CronAction { PAUSE, RESUME, RUN, DELETE }

/**
 * A short failure line for a cron screen: what failed, then the code that failed it.
 *
 * The code is read off the throwable — the server's own stable code when this build knows it,
 * `HR-CRON-003` when it does not. Before HG-51 every one of these strings ended in a hand-typed
 * 「（HR-RPC-001）」 regardless of what had happened, which is a transport code asserting a cause
 * nobody had read off the wire. Shared by the list, the detail page and the editor so the next one
 * cannot drift back to a literal.
 */
internal fun cronFailureText(zh: String, en: String, error: Throwable): LocalizedText {
    val code = AppErrorCode.fromValue((error as? HermesApiException)?.errorCode)
        ?: AppErrorCode.CRON_ACTION_FAILED
    return localizedText("$zh（${code.value}）", "$en (${code.value})")
}

data class CronUiState(
    val jobs: List<CronJobDto> = emptyList(),
    val profile: String? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val message: LocalizedText? = null,
)

@HiltViewModel
class CronViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    init {
        // Reload cron jobs whenever the selected profile changes — cron jobs are per-profile.
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val p = profileManager.active.value
        val current = _state.value
        _state.value = current.copy(
            // A same-profile refresh keeps its stable rows. A profile switch must never flash the
            // previous identity's schedules while the new list loads.
            jobs = if (current.profile == p) current.jobs else emptyList(),
            loading = true,
            error = null,
            profile = p,
        )
        runCatching { tools.cronJobs(p) }
            .onSuccess { _state.value = _state.value.copy(jobs = it, loading = false) }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = it.message, stage = "cron_load"),
                )
            }
    }

    fun runAction(jobId: String, name: String, action: CronAction) = viewModelScope.launch {
        val p = _state.value.profile
        val lastRunBefore = _state.value.jobs.firstOrNull { it.id == jobId }?.lastRunAt
        val outcome = runCatching {
            when (action) {
                CronAction.PAUSE -> tools.pauseCron(jobId, p)
                CronAction.RESUME -> tools.resumeCron(jobId, p)
                CronAction.RUN -> tools.triggerCron(jobId, p)
                CronAction.DELETE -> tools.deleteCron(jobId, p)
            }
        }
        // A timed-out 「立即运行」 is not a failed one until the job record says so.
        val startedInBackground = action == CronAction.RUN &&
            triggerStartedDespiteTimeout(jobId, p, lastRunBefore, outcome.exceptionOrNull())
        val started = localizedText(
            "已触发 $name，正在后台运行",
            "Triggered $name — running in the background",
        )
        val message = outcome.fold(
            onSuccess = {
                when (action) {
                    CronAction.PAUSE -> localizedText("已暂停 $name", "Paused $name")
                    CronAction.RESUME -> localizedText("已恢复 $name", "Resumed $name")
                    CronAction.RUN -> localizedText("已触发 $name", "Triggered $name")
                    CronAction.DELETE -> localizedText("已删除 $name", "Deleted $name")
                }
            },
            // The code comes from the failure, not from a literal typed beside the verb (HG-51).
            // All four of these used to end in 「（HR-RPC-001）」 whatever had actually gone wrong.
            // The toast names the job first: that is what the user was looking at.
            onFailure = { error ->
                when (action) {
                    CronAction.PAUSE -> cronFailureText("无法暂停 $name", "Couldn't pause $name", error)
                    CronAction.RESUME -> cronFailureText("无法恢复 $name", "Couldn't resume $name", error)
                    CronAction.RUN -> cronFailureText("无法触发 $name", "Couldn't trigger $name", error)
                    CronAction.DELETE -> cronFailureText("无法删除 $name", "Couldn't delete $name", error)
                }
            },
        )
        _state.value = _state.value.copy(message = if (startedInBackground) started else message)
        if (outcome.isSuccess || startedInBackground) {
            runCatching { tools.cronJobs(p) }.onSuccess { jobs -> _state.value = _state.value.copy(jobs = jobs) }
        }
    }

    /**
     * Whether a timed-out 「立即运行」 nevertheless started the job.
     *
     * `POST .../trigger` runs the job synchronously upstream, so any run longer than the REST
     * timeout answers with a timeout while executing to completion on the Mac. `fire_claim` is the
     * live proof of that ([CronJobDto.isRunning]); a moved `last_run_at` catches the run that
     * finished between the timeout and this question. A job that cannot be re-read answers no, so
     * a genuinely unreachable gateway still reports the failure it is.
     */
    private suspend fun triggerStartedDespiteTimeout(
        jobId: String,
        profile: String?,
        lastRunBefore: String?,
        error: Throwable?,
    ): Boolean {
        if (error?.isTimeout() != true) return false
        val job = runCatching { tools.cronJob(jobId, profile) }.getOrNull() ?: return false
        return job.isRunning || (job.lastRunAt != null && job.lastRunAt != lastRunBefore)
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
