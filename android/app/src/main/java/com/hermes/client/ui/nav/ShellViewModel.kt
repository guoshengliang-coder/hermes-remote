package com.hermes.client.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val healthMonitor: com.hermes.client.data.network.GatewayHealthMonitor,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    /** Backend health for the shell's status strip + You-tab badge. */
    val health: StateFlow<com.hermes.client.data.network.GatewayHealth> = healthMonitor.health

    init { viewModelScope.launch { profileManager.refresh() } }

    /** Name of the profile a switch just failed for, or null. UI shows a retry affordance and
     *  the active profile is left untouched — switchTo is a gateway write and can fail. */
    private val _switchFailed = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val switchFailed: StateFlow<String?> = _switchFailed

    fun switchProfile(name: String) = viewModelScope.launch {
        if (!profileManager.switchTo(name)) _switchFailed.value = name
    }

    fun clearSwitchFailed() { _switchFailed.value = null }


    /** Fire an immediate health probe (Re-check button). */
    fun recheckHealth() = healthMonitor.recheck()

    /** Foreground/background gating for periodic probing. */
    fun onAppForeground() = healthMonitor.startForeground()
    fun onAppBackground() = healthMonitor.stopForeground()
}
