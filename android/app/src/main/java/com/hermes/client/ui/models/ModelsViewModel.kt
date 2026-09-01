package com.hermes.client.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.ModelCatalogStore
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
    private val catalogStore: ModelCatalogStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    val favorites: StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Providers come from the process-wide catalog store (kept warm by the app-start
        // background refresh), so a warm cache renders instantly with no spinner. The store's
        // state is per-active-profile, so a stale back-stack entry can't show another
        // profile's models. The config-driven default is re-read whenever the profile flips.
        viewModelScope.launch {
            var seenProfile = false
            var lastProfile: String? = null
            catalogStore.state.collect { catalog ->
                val empty = catalog.providers.isEmpty()
                val error = empty && !catalog.refreshing && (catalog.failed || catalog.loaded)
                _state.value = _state.value.copy(
                    providers = catalog.providers,
                    loading = empty && !error,
                    error = if (error) {
                        AppError(AppErrorCode.MODEL_LIST_FAILED, retryable = true, stage = "models_load")
                    } else null,
                    defaultProvider = resolveModelProvider(catalog.providers, null, _state.value.defaultModel)
                        ?: _state.value.defaultProvider
                        ?: catalog.providers.firstOrNull { it.isCurrent }?.slug,
                )
                if (!seenProfile || catalog.profile != lastProfile) {
                    seenProfile = true
                    lastProfile = catalog.profile
                    loadDefault(catalog.profile)
                }
            }
        }
        // Safety net: if the app-start refresh was skipped (offline/unconfigured), entering this
        // screen fills the missing cache; a warm cache makes this a no-op.
        catalogStore.refresh(force = false)
    }

    /** Explicit retry from the error state — forces a store refresh and re-reads the default. */
    fun load() {
        catalogStore.refresh(force = true)
        viewModelScope.launch { loadDefault(profileManager.active.value) }
    }

    /** Commit a fresh catalog to this visible screen before the warm-start overlay is removed. */
    suspend fun recoverForForeground(): Boolean {
        _state.value = _state.value.copy(loading = true, error = null)
        val profile = profileManager.active.value
        return try {
            val providers = models.providers(profile)
            val defaultModel = runCatching {
                (configRepo.get(profile)["model"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.ifBlank { null }
            }.getOrNull()
            _state.value = _state.value.copy(
                providers = providers,
                loading = false,
                error = null,
                defaultModel = defaultModel,
                defaultProvider = resolveModelProvider(providers, null, defaultModel)
                    ?: providers.firstOrNull { it.isCurrent }?.slug,
            )
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = AppError(
                    AppErrorCode.MODEL_LIST_FAILED,
                    retryable = true,
                    technicalCause = error.message,
                    stage = "models_recovery",
                ),
            )
            false
        }
    }

    /**
     * Best-effort read of the configured default model; its provider resolves from the catalog.
     * The list is useful even when the config read fails (the summary card just hides).
     */
    private suspend fun loadDefault(profile: String?) {
        val defaultModel = runCatching {
            (configRepo.get(profile)["model"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.ifBlank { null }
        }.getOrNull()
        val providers = _state.value.providers
        _state.value = _state.value.copy(
            defaultModel = defaultModel,
            defaultProvider = resolveModelProvider(providers, null, defaultModel)
                ?: providers.firstOrNull { it.isCurrent }?.slug,
        )
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
                    // the shared store refresh updates isCurrent flags here AND in the chat sheet.
                    _state.value = _state.value.copy(
                        pendingKey = null,
                        defaultModel = model, defaultProvider = provider,
                        message = localizedText("默认模型已设为 $model", "Default set to $model"),
                    )
                    catalogStore.refresh(force = true)
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
