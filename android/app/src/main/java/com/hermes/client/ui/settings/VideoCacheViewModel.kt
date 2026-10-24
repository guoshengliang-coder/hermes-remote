package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.videoCacheError
import com.hermes.client.data.video.ArtifactRangeRepository
import com.hermes.client.data.video.VideoCacheCapacity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class VideoCacheViewModel @Inject constructor(private val ranges: ArtifactRangeRepository) : ViewModel() {
    val cache = ranges.cacheState
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)
    private var lastAction: suspend () -> Unit = { ranges.refreshCache(); Unit }
    init { refresh() }
    fun refresh() = run { ranges.refreshCache(); Unit }
    fun select(value: VideoCacheCapacity) = run { ranges.setCacheCapacity(value) }
    fun clear() = run { ranges.clearCache(); Unit }
    fun retry() = run(lastAction)
    private fun run(action: suspend () -> Unit) {
        if (busy.value) return
        lastAction = action; busy.value = true; error.value = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = videoCacheError(failure) }
            finally { busy.value = false }
        }
    }
}
