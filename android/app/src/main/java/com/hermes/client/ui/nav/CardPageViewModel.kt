package com.hermes.client.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.AnalyticsRepository
import com.hermes.client.data.repository.ConfigRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.progress.isActive
import com.hermes.client.ui.activity.needsAttention
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Supporting data for the card page's info tiles and entry badges. */
data class CardPageUiState(
    /** Cron jobs currently failed or overdue for the active profile (the entry-row badge). */
    val cronAlerts: Int = 0,
    /** The connected Mac connector's DEVICE_ID, or null while unknown/offline. */
    val deviceId: String? = null,
    /** Last-7-days token total for the active profile; null until loaded. */
    val weekTokens: Long? = null,
    /** Last-7-days estimated cost for the active profile; null until loaded. */
    val weekCost: Double? = null,
    /** The active profile's configured default model (config "model"); null while unknown. */
    val defaultModel: String? = null,
)

/**
 * State for the card page (the app's ONLY profile-switch point). Profile identity comes from
 * [ProfileManager]; the tiles are best-effort extras — each fetch fails independently and the
 * card renders without it rather than blocking.
 */
@HiltViewModel
class CardPageViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val tools: ToolsRepository,
    private val analytics: AnalyticsRepository,
    private val configRepo: ConfigRepository,
    private val settingsStore: SettingsStore,
    private val rest: HermesRestApi,
    healthMonitor: com.hermes.client.data.network.GatewayHealthMonitor,
    runtimeStore: SessionRuntimeStore,
    private val updateBadge: com.hermes.client.update.UpdateBadge,
) : ViewModel() {
    /** Newer release's version name for the update entry row (throttled index precheck). */
    val updateAvailable: StateFlow<String?> = updateBadge.available

    fun refreshUpdateBadge() = viewModelScope.launch { updateBadge.refreshIfStale() }

    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    /** Gateway health for the device tile's connected/latency line. */
    val health: StateFlow<GatewayHealth> = healthMonitor.health

    /** Current theme mode for the quick-switch row (shared store with the appearance page). */
    val themeMode: StateFlow<ThemeMode> =
        settingsStore.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    private val _state = MutableStateFlow(CardPageUiState())
    val state: StateFlow<CardPageUiState> = _state.asStateFlow()

    /** Per-profile (running, waiting) counts for the switch list's sub-lines — the one
     *  deliberate cross-profile read: the switcher is where you decide where to go next. */
    data class ProfileActivity(val running: Int = 0, val waiting: Int = 0)
    private val _profileActivity = MutableStateFlow<Map<String, ProfileActivity>>(emptyMap())
    val profileActivity: StateFlow<Map<String, ProfileActivity>> = _profileActivity.asStateFlow()

    init {
        viewModelScope.launch {
            runtimeStore.runtimes.collect { runtimes ->
                _profileActivity.value = runtimes.entries
                    .groupBy { it.key.profile ?: "default" }
                    .mapValues { (_, entries) ->
                        ProfileActivity(
                            running = entries.count { it.value.phase.isActive },
                            waiting = entries.count {
                                it.value.phase in setOf(
                                    SessionRunPhase.WAITING_APPROVAL,
                                    SessionRunPhase.WAITING_CLARIFICATION,
                                    SessionRunPhase.WAITING_ATTENTION,
                                )
                            },
                        )
                    }
            }
        }
    }

    /** Profile a switch is in flight for (row spinner), or null. */
    private val _switching = MutableStateFlow<String?>(null)
    val switching: StateFlow<String?> = _switching.asStateFlow()

    /** Profile a switch just FAILED for; the UI toasts and the active profile stays put. */
    private val _switchFailed = MutableStateFlow<String?>(null)
    val switchFailed: StateFlow<String?> = _switchFailed.asStateFlow()

    fun clearSwitchFailed() { _switchFailed.value = null }

    fun switchProfile(name: String) {
        if (_switching.value != null) return
        _switching.value = name
        viewModelScope.launch {
            try {
                if (!profileManager.switchTo(name)) _switchFailed.value = name
            } finally {
                _switching.value = null
            }
        }
    }

    init {
        // Tiles follow the active profile like everything else.
        viewModelScope.launch { profileManager.active.collect { refresh() } }
    }

    /** Refresh the tiles (also called when the drawer opens). Each part is independent. */
    fun refresh() {
        val p = profileManager.active.value
        viewModelScope.launch {
            runCatching { tools.cronJobs(p) }.onSuccess { jobs ->
                _state.value = _state.value.copy(
                    cronAlerts = needsAttention(jobs, System.currentTimeMillis()).size,
                )
            }
        }
        viewModelScope.launch {
            runCatching { rest.relayHealth() }.onSuccess { h ->
                _state.value = _state.value.copy(
                    deviceId = h.devices.firstOrNull { it.online }?.deviceId,
                )
            }
        }
        viewModelScope.launch {
            runCatching { configRepo.get(p) }.onSuccess { cfg ->
                val model = (cfg["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.ifBlank { null }
                _state.value = _state.value.copy(defaultModel = model)
            }
        }
        viewModelScope.launch {
            runCatching { analytics.usage(p) }.onSuccess { usage ->
                val week = usage.daily.sortedBy { it.day }.takeLast(7)
                _state.value = _state.value.copy(
                    weekTokens = week.sumOf { it.inputTokens + it.outputTokens },
                    weekCost = week.sumOf { it.estimatedCost },
                )
            }
        }
    }
}
