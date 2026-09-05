package com.hermes.client.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.normalizeGatewayBaseUrl
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ConnectivityChecker
import com.hermes.client.data.network.GatewayProbeResult
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ViewModeStore
import com.hermes.client.ui.sessions.ViewMode
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

enum class StartupReason { COLD_START, INITIAL_SETUP, CONNECTION_RECOVERY }

enum class StartupFailure(val code: String) {
    DEVICE_OFFLINE("HR-CONN-001"),
    CONNECTION_FAILED("HR-CONN-002"),
    CONNECTOR_OFFLINE("HR-CONN-005"),
    INITIAL_DATA_FAILED("HR-RPC-001"),
    CONFIGURATION_FAILED("HR-CONFIG-001"),
    INVALID_URL("HR-CONFIG-003"),
    AUTHENTICATION_FAILED("HR-AUTH-001"),
}

enum class StartupPhase(val progress: Float) {
    CONFIGURATION(0.12f),
    NETWORK(0.28f),
    AUTHENTICATION(0.52f),
    CONNECTION(0.74f),
    INITIAL_DATA(0.9f),
    READY(1f),
}

sealed interface StartupDestination {
    data object Sessions : StartupDestination
    data object Search : StartupDestination
    data object Models : StartupDestination
    data class Chat(val sessionId: String, val profile: String?) : StartupDestination
    data object Static : StartupDestination
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

    /** The startup gate yields to the editable connection screen for non-retryable config faults. */
    data class RepairRequired(
        val reason: StartupReason,
        val failure: StartupFailure,
    ) : StartupUiState
}

/**
 * Coordinates the work required for the first usable screen: configuration, network,
 * authentication, the WebSocket `gateway.ready` handshake, active-profile metadata and the first
 * critical snapshot for the destination that will be revealed.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val credentials: CredentialStore,
    private val connectivity: ConnectivityChecker,
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val profiles: ProfileManager,
    private val rest: HermesRestApi,
    private val models: ModelRepository,
    private val viewModes: ViewModeStore,
    private val runtimes: SessionRuntimeStore,
    private val foregroundRecovery: ForegroundRecoveryCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow<StartupUiState>(StartupUiState.Hidden)
    val state: StateFlow<StartupUiState> = _state.asStateFlow()
    private val _repairCompletion = MutableStateFlow(0L)
    val repairCompletion: StateFlow<Long> = _repairCompletion.asStateFlow()

    private var attemptJob: Job? = null
    private var repairReason: StartupReason? = null
    private var activeDestination: StartupDestination = StartupDestination.Sessions
    @Volatile private var appForeground = false

    init {
        // The gate covers the whole app, so when it appears — and how far it got before giving up
        // — is the first thing anyone diagnosing a "the app showed an error on startup" report
        // needs. Nothing else records it: the failure code alone cannot say which reason opened
        // the gate or which phase it died in.
        //
        // Collecting the StateFlow conflates phases that pass before this collector resumes, which
        // is the right trade rather than a gap: a phase worth seeing is one the attempt sat in, and
        // that one is never superseded in time to be dropped. The alternative — logging at every
        // assignment — would scatter the same statement across a dozen sites.
        viewModelScope.launch {
            _state.collect { current -> DebugLog.log("startup", describe(current)) }
        }
        // Automatic backoff may recover after the 15-second UI timeout. If that happens while the
        // failure actions are visible, dismiss the gate without requiring an unnecessary tap.
        viewModelScope.launch {
            chat.connectionState.collect { connection ->
                val failed = _state.value as? StartupUiState.Failed
                if (connection is ConnectionState.Connected && failed != null) {
                    when {
                        failed.failure != StartupFailure.INITIAL_DATA_FAILED ->
                            startAttempt(failed.reason, debounceMs = 0L)
                    }
                } else if (
                    appForeground &&
                    connection !is ConnectionState.Connected &&
                    _state.value is StartupUiState.Hidden &&
                    attemptJob?.isActive != true &&
                    runCatching { credentials.load() }.getOrNull() != null
                ) {
                    startAttempt(StartupReason.CONNECTION_RECOVERY, HOT_START_DEBOUNCE_MS)
                }
            }
        }
    }

    private fun describe(state: StartupUiState): String = when (state) {
        StartupUiState.Hidden -> "hidden"
        is StartupUiState.Loading -> "${state.reason} · ${state.phase}"
        is StartupUiState.Failed -> "${state.reason} · FAILED ${state.failure} (${state.failure.code})"
        is StartupUiState.RepairRequired ->
            "${state.reason} · REPAIR ${state.failure} (${state.failure.code})"
    }

    /** Called once from Activity.onCreate. Configuration changes are not process-cold starts. */
    fun onActivityCreated(processColdStart: Boolean) {
        if (!processColdStart) return
        val config = loadConfiguration(StartupReason.COLD_START) ?: return
        if (!isUsableConfiguration(config, StartupReason.COLD_START)) return
        startAttempt(StartupReason.COLD_START, debounceMs = 0L)
    }

    /** Re-evaluates connection readiness whenever the process returns to the foreground. */
    fun onForeground() {
        appForeground = true
        if (attemptJob?.isActive == true) return
        if (_state.value is StartupUiState.RepairRequired) return
        val config = loadConfiguration(StartupReason.CONNECTION_RECOVERY) ?: run {
            _state.value = StartupUiState.Hidden
            return
        }
        if (!isUsableConfiguration(config, StartupReason.CONNECTION_RECOVERY)) return
        val failed = _state.value as? StartupUiState.Failed
        if (chat.connectionState.value is ConnectionState.Connected && connectivity.isOnline()) {
            if (failed != null) {
                startAttempt(failed.reason, debounceMs = 0L)
            } else {
                _state.value = StartupUiState.Hidden
            }
            return
        }
        startAttempt(StartupReason.CONNECTION_RECOVERY, debounceMs = HOT_START_DEBOUNCE_MS)
    }

    fun onBackground() {
        appForeground = false
    }

    fun onActiveDestinationChanged(destination: StartupDestination) {
        activeDestination = destination
    }

    fun retry() {
        if (_state.value is StartupUiState.RepairRequired) return
        val failed = _state.value as? StartupUiState.Failed
        val reason = failed?.reason ?: StartupReason.CONNECTION_RECOVERY
        val config = loadConfiguration(reason) ?: run {
            _state.value = StartupUiState.Hidden
            return
        }
        if (!isUsableConfiguration(config, reason)) return
        startAttempt(
            reason = reason,
            debounceMs = 0L,
            forceReconnect = chat.connectionState.value !is ConnectionState.Connected,
        )
    }

    /** Opens the editable connection screen without treating the app as usable offline. */
    fun requestConfigurationRepair() {
        val current = _state.value
        val reason = when (current) {
            is StartupUiState.Loading -> current.reason
            is StartupUiState.Failed -> current.reason
            is StartupUiState.RepairRequired -> current.reason
            StartupUiState.Hidden -> StartupReason.CONNECTION_RECOVERY
        }
        val failure = (current as? StartupUiState.Failed)?.failure
            ?: StartupFailure.CONNECTION_FAILED
        attemptJob?.cancel()
        attemptJob = null
        repairReason = reason
        _state.value = StartupUiState.RepairRequired(reason, failure)
    }

    /** Called after the repair screen has persisted edited values. */
    fun onConfigurationSaved() {
        val reason = repairReason ?: StartupReason.CONNECTION_RECOVERY
        startAttempt(reason, debounceMs = 0L, forceReconnect = true)
    }

    /** First-time setup also completes WebSocket, profile and first-screen readiness before entry. */
    fun onInitialConfigurationSaved() {
        repairReason = StartupReason.INITIAL_SETUP
        startAttempt(StartupReason.INITIAL_SETUP, debounceMs = 0L, forceReconnect = true)
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
                ) {
                    _state.value = StartupUiState.Hidden
                    return@launch
                }
                _state.value = StartupUiState.Loading(reason, StartupPhase.CONFIGURATION)
            }

            coroutineScope {
                val minimumDisplay = if (
                    reason == StartupReason.COLD_START || reason == StartupReason.INITIAL_SETUP
                ) {
                    async { delay(MINIMUM_COLD_START_MS) }
                } else null

                val config = loadConfiguration(reason) ?: run {
                    minimumDisplay?.cancel()
                    _state.value = StartupUiState.Hidden
                    return@coroutineScope
                }
                if (!isUsableConfiguration(config, reason)) {
                    minimumDisplay?.cancel()
                    return@coroutineScope
                }

                _state.value = StartupUiState.Loading(reason, StartupPhase.NETWORK)
                // Read connectivity, but do not decide on it. The probe that follows is a better
                // answer to the same question and is one step away; short-circuiting here on a
                // single capability read put a full-screen "your device has no network" in front
                // of users whose traffic was flowing. It still chooses between the two codes
                // below, so "check your network" and "can't reach the Relay" stay distinct.
                val connectivitySaysOffline = !connectivity.isOnline()

                _state.value = StartupUiState.Loading(reason, StartupPhase.AUTHENTICATION)
                when (val probe = rest.probeStatusFor(config.baseUrl, config.token)) {
                    GatewayProbeResult.Reachable -> Unit
                    is GatewayProbeResult.Unauthorized -> {
                        minimumDisplay?.cancel()
                        requireConfigurationRepair(reason, StartupFailure.AUTHENTICATION_FAILED)
                        return@coroutineScope
                    }
                    is GatewayProbeResult.InvalidEndpoint -> {
                        minimumDisplay?.cancel()
                        requireConfigurationRepair(reason, StartupFailure.INVALID_URL)
                        return@coroutineScope
                    }
                    is GatewayProbeResult.ServerFailure -> {
                        minimumDisplay?.cancel()
                        fail(
                            reason,
                            if (probe.errorCode == "device_offline") {
                                StartupFailure.CONNECTOR_OFFLINE
                            } else {
                                StartupFailure.CONNECTION_FAILED
                            },
                        )
                        return@coroutineScope
                    }
                    is GatewayProbeResult.Unreachable -> {
                        minimumDisplay?.cancel()
                        fail(
                            reason,
                            if (connectivitySaysOffline) StartupFailure.DEVICE_OFFLINE
                            else StartupFailure.CONNECTION_FAILED,
                        )
                        return@coroutineScope
                    }
                }
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
                                    is ConnectionState.Connected -> StartupPhase.INITIAL_DATA
                                    else -> StartupPhase.CONNECTION
                                },
                            )
                        }
                        .first { it is ConnectionState.Connected }
                } != null

                if (connected) {
                    _state.value = StartupUiState.Loading(reason, StartupPhase.INITIAL_DATA)
                    val initialized = try {
                        withTimeoutOrNull(INITIAL_DATA_TIMEOUT_MS) {
                            profiles.refresh()
                            if (reason == StartupReason.COLD_START || reason == StartupReason.INITIAL_SETUP) {
                                sessions.listAllProfiles()
                                true
                            } else {
                                recoverActiveDestination()
                            }
                        } == true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!initialized) {
                        minimumDisplay?.cancel()
                        fail(reason, StartupFailure.INITIAL_DATA_FAILED)
                        return@coroutineScope
                    }
                    _state.value = StartupUiState.Loading(reason, StartupPhase.READY)
                    // Only the cold start earns the completion flourish. Holding a hot start's
                    // overlay half a second past the moment it is ready is the "it flashed the
                    // splash screen for no reason" complaint, on a screen that was already usable.
                    val completionAnimation = minimumDisplay?.let { async { delay(SUCCESS_COMPLETION_MS) } }
                    minimumDisplay?.await()
                    completionAnimation?.await()
                    _state.value = StartupUiState.Hidden
                    if (repairReason != null) {
                        repairReason = null
                        _repairCompletion.value += 1
                    }
                } else {
                    minimumDisplay?.cancel()
                    fail(reason, StartupFailure.CONNECTION_FAILED)
                }
            }
        }
    }

    /**
     * Terminal failure, blocking only when there is nothing behind the gate to fall back to.
     *
     * On a cold start the overlay is the whole screen and a full-stop error is the only honest
     * thing to show. On a hot start the app is already rendered and the user was looking at it a
     * second ago, so covering it for a failure that heals itself trades a working screen for a
     * dead one. Those failures go back to the surfaces that own them — the health strip for
     * reachability, the chat banner for the socket, the screen's own refresh for data — all of
     * which report without taking the app away.
     *
     * Configuration and authentication faults do not come through here: they route to
     * [requireConfigurationRepair], and they still take over on a hot start because the app
     * genuinely cannot work until the user fixes them.
     */
    private fun fail(reason: StartupReason, failure: StartupFailure) {
        if (reason == StartupReason.CONNECTION_RECOVERY) {
            DebugLog.log("startup", "hot start ${failure.code} — leaving the app visible")
            _state.value = StartupUiState.Hidden
            return
        }
        _state.value = StartupUiState.Failed(reason, failure)
    }

    private fun loadConfiguration(reason: StartupReason): GatewayConfig? = try {
        credentials.load()
    } catch (_: Exception) {
        requireConfigurationRepair(reason, StartupFailure.CONFIGURATION_FAILED)
        null
    }

    private fun isUsableConfiguration(config: GatewayConfig, reason: StartupReason): Boolean {
        if (runCatching { normalizeGatewayBaseUrl(config.baseUrl) }.isFailure) {
            requireConfigurationRepair(reason, StartupFailure.INVALID_URL)
            return false
        }
        if (config.token.isBlank() && !config.isGated) {
            requireConfigurationRepair(reason, StartupFailure.AUTHENTICATION_FAILED)
            return false
        }
        return true
    }

    private fun requireConfigurationRepair(reason: StartupReason, failure: StartupFailure) {
        repairReason = reason
        _state.value = StartupUiState.RepairRequired(reason, failure)
    }

    private suspend fun recoverActiveDestination(): Boolean =
        foregroundRecovery.recoverActive() ?: recoverActiveDestinationFallback()

    private suspend fun recoverActiveDestinationFallback(): Boolean = when (val destination = activeDestination) {
        StartupDestination.Sessions -> when (viewModes.mode.first()) {
            ViewMode.ARCHIVED -> {
                sessions.archivedAllProfiles()
                true
            }
            ViewMode.SESSIONS, ViewMode.PROJECTS -> {
                sessions.listAllProfiles()
                true
            }
        }
        StartupDestination.Search -> {
            sessions.listAllProfiles()
            sessions.archivedAllProfiles()
            true
        }
        StartupDestination.Models -> {
            models.providers(profiles.active.value)
            true
        }
        is StartupDestination.Chat -> runtimes.recoverVisibleSession(
            SessionRuntimeKey(destination.profile ?: profiles.active.value, destination.sessionId),
        )
        StartupDestination.Static -> true
    }

    internal companion object {
        // A dropped socket is normally self-healing: HermesGatewayClient reconnects on its own
        // 500ms→10s backoff. Waiting out that first window before running a recovery pass means a
        // routine blip costs nothing at all — no /api/status probe, no destination recovery, no
        // full transcript re-fetch. At 200ms every one of the 55 reconnects observed in a single
        // day (2026-09-03) paid for a complete recovery.
        const val HOT_START_DEBOUNCE_MS = 3_000L
        const val MINIMUM_COLD_START_MS = 450L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val INITIAL_DATA_TIMEOUT_MS = 15_000L
        const val SUCCESS_COMPLETION_MS = 520L
    }
}
