package com.hermes.client.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.BuildConfig
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val loading: Boolean = false,
    val rows: List<UpdateRow> = emptyList(),
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val error: String? = null,
    val activeVersionCode: Int? = null,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val percent: Int? = null,
    val verifiedFile: File? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repository: UpdateRepositoryContract) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var monitorJob: Job? = null
    private var monitorGeneration: Long = 0

    init { resume() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { repository.fetch() }
            .onSuccess { index ->
                val latest = index.versions.firstOrNull { it.versionCode == index.latestVersionCode }
                _state.value = _state.value.copy(
                    loading = false,
                    rows = classifyVersions(index.versions, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID, UPDATE_CHANNEL, BuildConfig.EXPECTED_UPDATE_CERT_SHA256, Build.VERSION.SDK_INT),
                    latestVersionName = latest?.versionName,
                    latestVersionCode = latest?.versionCode,
                )
            }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Update check failed") }
    }

    fun download(version: UpdateVersion) {
        monitorJob?.cancel()
        val generation = ++monitorGeneration
        monitorJob = viewModelScope.launch {
            runCatching { repository.enqueue(version) }
                .onSuccess { id ->
                    _state.value = _state.value.copy(activeVersionCode=version.versionCode,phase=DownloadPhase.WAITING,error=null,verifiedFile=null)
                    monitor(id, version, generation)
                }
                .onFailure {
                    if (generation == monitorGeneration) {
                        _state.value = _state.value.copy(activeVersionCode=version.versionCode,phase=DownloadPhase.FAILED,error=it.message ?: "Unable to start download")
                    }
                }
        }
    }

    fun resume() {
        monitorJob?.cancel()
        val generation = ++monitorGeneration
        monitorJob = viewModelScope.launch {
            repository.saved()?.let { monitor(it.first, it.second, generation) }
        }
    }

    private suspend fun monitor(id: Long, version: UpdateVersion, generation: Long) {
        fun active() = generation == monitorGeneration && _state.value.activeVersionCode in setOf(null, version.versionCode)
        while (true) {
            if (!active()) return
            val snapshot = repository.query(id) ?: run { if(active()) _state.value=_state.value.copy(activeVersionCode=version.versionCode,phase=DownloadPhase.FAILED,error="Download record is no longer available"); return }
            val phase = mapDownloadStatus(snapshot.status)
            if (!active()) return
            _state.value = _state.value.copy(activeVersionCode=version.versionCode,phase=phase,percent=downloadPercent(snapshot.downloaded,snapshot.total))
            if (phase == DownloadPhase.DOWNLOADED) {
                val localUri=snapshot.localUri ?: run { _state.value=_state.value.copy(phase=DownloadPhase.FAILED,error="Downloaded file is unavailable"); return }
                _state.value=_state.value.copy(phase=DownloadPhase.VERIFYING)
                runCatching { repository.verify(version,localUri) }
                    .onSuccess { if(active()) _state.value=_state.value.copy(phase=DownloadPhase.INSTALLABLE,verifiedFile=it,error=null) }
                    .onFailure { if(active()) _state.value=_state.value.copy(phase=DownloadPhase.FAILED,error="APK verification failed") }
                return
            }
            if (phase==DownloadPhase.FAILED) { _state.value=_state.value.copy(error=friendlyDownloadError(snapshot.reason)); return }
            delay(750)
        }
    }

    fun install() {
        val file = _state.value.verifiedFile ?: return
        when (val result = repository.install(file)) {
            InstallResult.InstallerOpened -> _state.value = _state.value.copy(error = null)
            InstallResult.PermissionRequired -> _state.value = _state.value.copy(error = "Install permission is required; grant it and retry")
            is InstallResult.Failure -> _state.value = _state.value.copy(error = result.message)
        }
    }
}
