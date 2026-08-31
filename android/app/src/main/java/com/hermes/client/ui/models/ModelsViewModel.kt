package com.hermes.client.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelsUiState(
    val providers: List<ModelProviderDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val message: LocalizedText? = null,
    val query: String = "",
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val models: ModelRepository,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    val favorites: StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Model options/current are per-profile (upstream scopes both read and write) —
        // reload whenever the active profile changes so a stale back-stack entry can't
        // show another profile's models.
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { models.providers(profileManager.active.value) }
            .onSuccess { _state.value = _state.value.copy(providers = it, loading = false, error = null) }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = it.message, stage = "models_load"),
                )
            }
    }

    fun onQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    fun select(provider: String, model: String) = viewModelScope.launch {
        runCatching { models.set(provider, model, profileManager.active.value) }
            .onSuccess { _state.value = _state.value.copy(message = localizedText("默认模型已设为 $model", "Default set to $model")); load() }
            .onFailure { _state.value = _state.value.copy(message = localizedText("设置默认模型失败（HR-RPC-001）", "Couldn't set the default model (HR-RPC-001)")) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
