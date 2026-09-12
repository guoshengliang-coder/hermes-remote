package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionPickerUiState(
    val loading: Boolean = true,
    val sessions: List<Session> = emptyList(),
    /** Archived conversations, fetched lazily and only surfaced while searching (§3.2). */
    val archived: List<Session> = emptyList(),
    val error: AppError? = null,
)

/**
 * Backs the session picker (docs/SESSION_EXCHANGE_REQUIREMENTS.md §3).
 *
 * Deliberately its own ViewModel rather than more surface on `ChatViewModel`: it owns a list, a
 * query and its own failure, none of which the chat cares about, and it must die when the picker
 * closes rather than hold a second copy of the session list for the life of the conversation.
 *
 * It does NOT apply the identity or self-exclusion filters — those are [sessionPickerCandidates],
 * kept pure so they can be tested without a gateway.
 */
@HiltViewModel
class SessionPickerViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionPickerUiState(sessions = sessions.cachedAllProfiles()))
    val state: StateFlow<SessionPickerUiState> = _state.asStateFlow()

    val activeProfile: StateFlow<String?> = profileManager.active

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { sessions.listAllProfiles() }
                .onSuccess { list -> _state.value = _state.value.copy(loading = false, sessions = list) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.value = _state.value.copy(
                        loading = false,
                        error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = e.message),
                    )
                }
        }
    }

    /**
     * Archived conversations, loaded on first search rather than up front: they are not offered
     * until the user types (§3.2), so fetching them on open would be a round trip nobody asked for.
     * A failure here is silent — the unarchived list is still perfectly usable, and an error banner
     * over a search that did find things would be a lie.
     */
    fun ensureArchivedLoaded() {
        if (_state.value.archived.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { sessions.archived(profileManager.active.value) }
                .onSuccess { list -> _state.value = _state.value.copy(archived = list) }
        }
    }
}
