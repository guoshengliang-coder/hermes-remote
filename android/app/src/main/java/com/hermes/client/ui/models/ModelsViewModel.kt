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
    // The profile's configured default — this screen edits exactly that slot, so it must show it.
    val defaultModel: String? = null,
    val defaultProvider: String? = null,
    // favKey of the row whose set-default is in flight; non-null disables the list.
    val pendingKey: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val models: ModelRepository,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val profileManager: ProfileManager,
    private val configRepo: com.hermes.client.data.repository.ConfigRepository,
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
        val profile = profileManager.active.value
        runCatching { models.providers(profile) }
            .onSuccess { providers ->
                // The default model comes from config; its provider from the catalog. Best-effort:
                // the list is useful even when the config read fails (the summary card just hides).
                val defaultModel = runCatching {
                    (configRepo.get(profile)["model"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content?.ifBlank { null }
                }.getOrNull()
                _state.value = _state.value.copy(
                    providers = providers, loading = false, error = null,
                    defaultModel = defaultModel,
                    defaultProvider = resolveModelProvider(providers, null, defaultModel)
                        ?: providers.firstOrNull { it.isCurrent }?.slug,
                )
            }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.MODEL_LIST_FAILED, retryable = true, technicalCause = it.message, stage = "models_load"),
                )
            }
    }

    fun onQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    fun select(provider: String, model: String) {
        if (_state.value.pendingKey != null) return
        val key = com.hermes.client.data.repository.favKey(provider, model)
        _state.value = _state.value.copy(pendingKey = key, message = null)
        viewModelScope.launch {
            runCatching { models.set(provider, model, profileManager.active.value) }
                .onSuccess {
                    // Optimistic: mark the new default in place instead of flashing a full reload;
                    // the provider isCurrent flags refresh quietly in the background.
                    _state.value = _state.value.copy(
                        pendingKey = null,
                        defaultModel = model, defaultProvider = provider,
                        message = localizedText("默认模型已设为 $model", "Default set to $model"),
                    )
                    runCatching { models.providers(profileManager.active.value) }
                        .onSuccess { _state.value = _state.value.copy(providers = it) }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        pendingKey = null,
                        message = localizedText("设置默认模型失败（HR-RPC-005）", "Couldn't set the default model (HR-RPC-005)"),
                    )
                }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
