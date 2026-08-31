package com.hermes.client.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ConnectivityChecker
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class StartupReason { COLD_START, CONNECTION_RECOVERY }

enum class StartupFailure(val code: String) {
    DEVICE_OFFLINE("HR-CONN-001"),
    CONNECTION_FAILED("HR-CONN-002"),
    INITIAL_DATA_FAILED("HR-RPC-001"),
}

enum class StartupPhase(val progress: Float) {
    CONFIGURATION(0.12f),
    NETWORK(0.28f),
    AUTHENTICATION(0.52f),
    CONNECTION(0.74f),
    INITIAL_DATA(0.9f),
    READY(1f),
}

sealed interface StartupUiState {
    data object Hidden : StartupUiState

    data class Loading(
        val reason: StartupReason,
        val phase: StartupPhase,
    ) : StartupUiState

    data class Failed(
        val reason: StartupReason,
        val failure: StartupFailure,
    ) : StartupUiState
}

/**
 * Coordinates the work required for the first usable screen: configuration, network,
 * authentication, the WebSocket `gateway.ready` handshake, active-profile metadata and the first
 * session-list snapshot. Optional destinations still load progressively after the gate.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val credentials: CredentialStore,
    private val connectivity: ConnectivityChecker,
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val profiles: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow<StartupUiState>(StartupUiState.Hidden)
    val state: StateFlow<StartupUiState> = _state.asStateFlow()

    private var attemptJob: Job? = null

    init {
        // Automatic backoff may recover after the 15-second UI timeout. If that happens while the
        // failure actions are visible, dismiss the gate without requiring an unnecessary tap.
        viewModelScope.launch {
            chat.connectionState.collect { connection ->
                val failed = _state.value as? StartupUiState.Failed
                if (connection is ConnectionState.Connected && failed != null) {
                    when {
                        failed.reason == StartupReason.COLD_START &&
                            failed.failure != StartupFailure.INITIAL_DATA_FAILED ->
                            startAttempt(StartupReason.COLD_START, debounceMs = 0L)
                        failed.reason == StartupReason.CONNECTION_RECOVERY ->
                            _state.value = StartupUiState.Hidden
                    }
                }
            }
        }
    }

    /** Called once from Activity.onCreate. Configuration changes are not process-cold starts. */
    fun onActivityCreated(processColdStart: Boolean) {
        if (!processColdStart || credentials.load() == null) return
        startAttempt(StartupReason.COLD_START, debounceMs = 0L)
    }

    /** Re-evaluates connection readiness whenever the process returns to the foreground. */
    fun onForeground() {
        if (attemptJob?.isActive == true) return
        if (credentials.load() == null) {
            _state.value = StartupUiState.Hidden
            return
        }
        val failed = _state.value as? StartupUiState.Failed
        if (chat.connectionState.value is ConnectionState.Connected && connectivity.isOnline()) {
            if (failed?.reason == StartupReason.COLD_START) {
                startAttempt(StartupReason.COLD_START, debounceMs = 0L)
            } else {
                _state.value = StartupUiState.Hidden
            }
            return
        }
        startAttempt(StartupReason.CONNECTION_RECOVERY, debounceMs = HOT_START_DEBOUNCE_MS)
    }

    fun retry() {
        if (credentials.load() == null) {
            _state.value = StartupUiState.Hidden
            return
        }
        val failed = _state.value as? StartupUiState.Failed
        if (
            failed?.reason != StartupReason.COLD_START &&
            chat.connectionState.value is ConnectionState.Connected &&
            connectivity.isOnline()
        ) {
            _state.value = StartupUiState.Hidden
            return
        }
        startAttempt(
            reason = failed?.reason ?: StartupReason.CONNECTION_RECOVERY,
            debounceMs = 0L,
            forceReconnect = chat.connectionState.value !is ConnectionState.Connected,
        )
    }

    /** Lets cached UI remain usable; the next foreground transition will evaluate again. */
    fun continueOffline() {
        attemptJob?.cancel()
        attemptJob = null
        _state.value = StartupUiState.Hidden
    }

    private fun startAttempt(
        reason: StartupReason,
        debounceMs: Long,
        forceReconnect: Boolean = false,
    ) {
        attemptJob?.cancel()
        if (debounceMs == 0L) {
            _state.value = StartupUiState.Loading(reason, StartupPhase.CONFIGURATION)
        }
        attemptJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
                if (
                    chat.connectionState.value is ConnectionState.Connected &&
                    connectivity.isOnline()
                ) return@launch
                _state.value = StartupUiState.Loading(reason, StartupPhase.CONFIGURATION)
            }

            coroutineScope {
                val minimumDisplay = if (reason == StartupReason.COLD_START) {
                    async { delay(MINIMUM_COLD_START_MS) }
                } else null

                if (credentials.load() == null) {
                    minimumDisplay?.cancel()
                    _state.value = StartupUiState.Hidden
                    return@coroutineScope
                }

                _state.value = StartupUiState.Loading(reason, StartupPhase.NETWORK)
                if (!connectivity.isOnline()) {
                    minimumDisplay?.cancel()
                    _state.value = StartupUiState.Failed(
                        reason,
                        StartupFailure.DEVICE_OFFLINE,
                    )
                    return@coroutineScope
                }

                _state.value = StartupUiState.Loading(reason, StartupPhase.AUTHENTICATION)
                val current = chat.connectionState.value
                when {
                    forceReconnect -> chat.reconnect()
                    current is ConnectionState.Disconnected -> chat.connect()
                    current is ConnectionState.Reconnecting || current is ConnectionState.Error -> chat.reconnect()
                    else -> Unit
                }
                _state.value = StartupUiState.Loading(reason, StartupPhase.CONNECTION)

                val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                    chat.connectionState
                        .onEach { connection ->
                            _state.value = StartupUiState.Loading(
                                reason,
                                when (connection) {
                                    is ConnectionState.Connected -> if (reason == StartupReason.COLD_START) {
                                        StartupPhase.INITIAL_DATA
                                    } else {
                                        StartupPhase.READY
                                    }
                                    else -> StartupPhase.CONNECTION
                                },
                            )
                        }
                        .first { it is ConnectionState.Connected }
                } != null

                if (connected) {
                    if (reason == StartupReason.COLD_START) {
                        _state.value = StartupUiState.Loading(reason, StartupPhase.INITIAL_DATA)
                        val initialized = try {
                            withTimeoutOrNull(INITIAL_DATA_TIMEOUT_MS) {
                                profiles.refresh()
                                sessions.listAllProfiles()
                                true
                            } == true
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!initialized) {
                            minimumDisplay?.cancel()
                            _state.value = StartupUiState.Failed(
                                reason,
                                StartupFailure.INITIAL_DATA_FAILED,
                            )
                            return@coroutineScope
                        }
                    }
                    _state.value = StartupUiState.Loading(reason, StartupPhase.READY)
                    minimumDisplay?.await()
                    _state.value = StartupUiState.Hidden
                } else {
                    minimumDisplay?.cancel()
                    _state.value = StartupUiState.Failed(
                        reason,
                        StartupFailure.CONNECTION_FAILED,
                    )
                }
            }
        }
    }

    internal companion object {
        const val HOT_START_DEBOUNCE_MS = 200L
        const val MINIMUM_COLD_START_MS = 450L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val INITIAL_DATA_TIMEOUT_MS = 15_000L
    }
}
