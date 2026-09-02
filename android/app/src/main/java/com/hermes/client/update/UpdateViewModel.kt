package com.hermes.client.update

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.BuildConfig
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The one download the page is currently responsible for. It carries its own [version] so a task
 * restored after process death renders on its own, without waiting for — or depending on — the
 * network index.
 */
data class UpdateTask(
    val version: UpdateVersion,
    val phase: DownloadPhase,
    val percent: Int? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    /** The raw DownloadManager pause/failure reason, mapped to copy by [downloadPauseText]. */
    val reason: Int? = null,
    val verifiedFile: File? = null,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val checkedOnce: Boolean = false,
    val lastCheckedAtMs: Long? = null,
    val rows: List<UpdateRow> = emptyList(),
    /** The manifest's `latestVersionCode` entry: the only release the page will install. */
    val latest: UpdateRow? = null,
    /** Everything older, kept readable but read-only. */
    val history: List<UpdateRow> = emptyList(),
    /** Failure of the index check; never overwrites a download failure and vice versa. */
    val checkError: AppError? = null,
    val taskError: AppError? = null,
    val task: UpdateTask? = null,
)

val UpdateUiState.taskIsSuperseded: Boolean
    get() {
        val active = task?.version?.versionCode ?: return false
        val recommended = latest ?: return false
        return recommended.version.versionCode > active
    }

@HiltViewModel
class UpdateViewModel(
    private val repository: UpdateRepositoryContract,
    private val clock: () -> Long,
    private val deviceSdk: Int,
) : ViewModel() {
    @Inject constructor(repository: UpdateRepositoryContract) : this(repository, System::currentTimeMillis, Build.VERSION.SDK_INT)
    internal constructor(repository: UpdateRepositoryContract, clock: () -> Long) : this(repository, clock, 26)

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var monitorJob: Job? = null
    private var monitorGeneration: Long = 0

    /**
     * Page entry. The index check and the restore of a persisted download are independent: either
     * can fail or come back empty without disturbing the other.
     */
    fun onOpen() {
        resume()
        check()
    }

    fun check() {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true, checkError = null)
            runCatching { repository.fetch() }
                .onSuccess { index ->
                    val rows = classifyVersions(
                        index.versions,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.APPLICATION_ID,
                        UPDATE_CHANNEL,
                        BuildConfig.EXPECTED_UPDATE_CERT_SHA256,
                        deviceSdk,
                    )
                    val latest = rows.firstOrNull { it.version.versionCode == index.latestVersionCode }
                    _state.value = _state.value.copy(
                        checking = false,
                        checkedOnce = true,
                        lastCheckedAtMs = clock(),
                        rows = rows,
                        latest = latest,
                        history = rows.filter { it.version.versionCode != latest?.version?.versionCode },
                    )
                }
                .onFailure {
                    // Rows already on screen stay: a failed refresh must not blank the page.
                    _state.value = _state.value.copy(
                        checking = false,
                        checkError = AppError(AppErrorCode.UPDATE_CHECK_FAILED, retryable = true, technicalCause = it.message, stage = "update_check"),
                    )
                }
        }
    }

    /** Restore a persisted job. The repository self-heals corrupt or already-installed records. */
    private fun resume() {
        if (_state.value.task != null) return
        val generation = ++monitorGeneration
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            val saved = runCatching { repository.saved() }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(task = UpdateTask(saved.second, DownloadPhase.WAITING))
            monitor(saved.first, saved.second, generation)
        }
    }

    /**
     * Start downloading [version]. Only the manifest's latest release — or the version of the task
     * already on screen, so retry works with no index — is installable from this page.
     */
    fun download(version: UpdateVersion) {
        val current = _state.value
        if (current.taskIsSuperseded) return
        val recommended = current.latest
        val allowed = (version.versionCode == recommended?.version?.versionCode && recommended.eligibility == VersionEligibility.UPDATE) ||
            (recommended == null && version.versionCode == current.task?.version?.versionCode)
        if (!allowed) return
        if (current.task?.phase in BUSY_DOWNLOAD_PHASES) return

        // Claim the slot synchronously: enqueue() suspends, and a second tap that lands before it
        // returns would otherwise queue a duplicate DownloadManager job.
        _state.value = current.copy(task = UpdateTask(version, DownloadPhase.ENQUEUING), taskError = null)
        val generation = ++monitorGeneration
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            runCatching { repository.enqueue(version) }
                .onSuccess { id ->
                    if (generation != monitorGeneration) return@launch
                    update(generation) { it.copy(phase = DownloadPhase.WAITING) }
                    monitor(id, version, generation)
                }
                .onFailure {
                    fail(generation, AppErrorCode.UPDATE_ENQUEUE_FAILED, "download_enqueue", it.message)
                }
        }
    }

    /** Retry the task on screen (a failed download, or one that failed verification). */
    fun retry() {
        val task = _state.value.task ?: return
        // Keep the failed task until download() claims the slot. Its version is the offline
        // authorization context when the index refresh failed and latest is unavailable.
        _state.value = _state.value.copy(taskError = null)
        download(task.version)
    }

    /** Drop the job, its persisted metadata, and the partial file. */
    fun cancel() {
        val original = _state.value.task ?: return
        if (original.phase == DownloadPhase.CANCELLING) return
        val generation = ++monitorGeneration
        monitorJob?.cancel()
        _state.value = _state.value.copy(task = original.copy(phase = DownloadPhase.CANCELLING), taskError = null)
        monitorJob = viewModelScope.launch {
            runCatching { repository.cancel() }
                .onSuccess {
                    if (generation == monitorGeneration) _state.value = _state.value.copy(task = null, taskError = null)
                }
                .onFailure {
                    if (generation == monitorGeneration) {
                        _state.value = _state.value.copy(
                            task = original,
                            taskError = AppError(
                                AppErrorCode.UPDATE_CLEANUP_FAILED,
                                retryable = true,
                                technicalCause = it.message,
                                stage = "download_cleanup",
                            ),
                        )
                    }
                }
        }
    }

    fun install() {
        if (_state.value.taskIsSuperseded) return
        val file = _state.value.task?.verifiedFile ?: return
        when (val result = repository.install(file)) {
            InstallResult.InstallerOpened -> _state.value = _state.value.copy(taskError = null)
            InstallResult.PermissionRequired -> _state.value = _state.value.copy(
                taskError = AppError(AppErrorCode.INSTALL_PERMISSION_REQUIRED, retryable = true, stage = "installer_permission"),
            )
            is InstallResult.Failure -> _state.value = _state.value.copy(
                taskError = AppError(AppErrorCode.UPDATE_INSTALLER_FAILED, retryable = true, technicalCause = result.message, stage = "installer_open"),
            )
        }
    }

    private suspend fun monitor(id: Long, version: UpdateVersion, generation: Long) {
        while (generation == monitorGeneration) {
            val snapshot = runCatching { repository.query(id) }.getOrElse {
                fail(generation, AppErrorCode.UPDATE_DOWNLOAD_FAILED, "download_query", it.message)
                return
            }
            if (snapshot == null) {
                fail(generation, AppErrorCode.UPDATE_FILE_MISSING, "download_record", null)
                return
            }
            val phase = mapDownloadStatus(snapshot.status)
            update(generation) {
                it.copy(
                    phase = phase,
                    percent = downloadPercent(snapshot.downloaded, snapshot.total),
                    downloadedBytes = snapshot.downloaded,
                    totalBytes = snapshot.total,
                    reason = snapshot.reason.takeIf { _ -> phase == DownloadPhase.PAUSED || phase == DownloadPhase.FAILED },
                )
            }
            when (phase) {
                DownloadPhase.DOWNLOADED -> {
                    val localUri = snapshot.localUri
                    if (localUri == null) {
                        fail(generation, AppErrorCode.UPDATE_FILE_MISSING, "downloaded_file", null)
                        return
                    }
                    update(generation) { it.copy(phase = DownloadPhase.VERIFYING) }
                    runCatching { repository.verify(version, localUri) }
                        .onSuccess { file ->
                            if (generation != monitorGeneration) return
                            _state.value = _state.value.copy(
                                task = _state.value.task?.copy(phase = DownloadPhase.INSTALLABLE, verifiedFile = file),
                                taskError = null,
                            )
                        }
                        .onFailure { fail(generation, AppErrorCode.UPDATE_VERIFICATION_FAILED, "apk_verification", it.message) }
                    return
                }
                DownloadPhase.FAILED -> {
                    fail(generation, AppErrorCode.UPDATE_DOWNLOAD_FAILED, "download_failed", downloadReasonDiagnostic(snapshot.reason))
                    return
                }
                // A pause is not a failure: keep polling, DownloadManager may resume on its own.
                else -> delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun update(generation: Long, transform: (UpdateTask) -> UpdateTask) {
        if (generation != monitorGeneration) return
        val task = _state.value.task ?: return
        _state.value = _state.value.copy(task = transform(task))
    }

    private fun fail(generation: Long, code: AppErrorCode, stage: String, cause: String?) {
        if (generation != monitorGeneration) return
        val task = _state.value.task ?: return
        _state.value = _state.value.copy(
            task = task.copy(phase = DownloadPhase.FAILED, verifiedFile = null),
            taskError = AppError(code, retryable = true, technicalCause = cause, stage = stage),
        )
    }

    private companion object { const val POLL_INTERVAL_MS = 750L }
}
