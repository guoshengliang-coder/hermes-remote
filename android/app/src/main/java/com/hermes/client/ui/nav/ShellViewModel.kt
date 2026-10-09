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
    val workspace: com.hermes.client.ui.workspace.WorkspacePreferences,
    private val accountSessions: com.hermes.client.data.auth.AccountSessionManager,
    private val credentials: com.hermes.client.data.auth.CredentialStore,
    private val runtimeStore: com.hermes.client.data.progress.SessionRuntimeStore,
    private val drafts: com.hermes.client.data.repository.DraftStore,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active
    val workspaceIdentity = kotlinx.coroutines.flow.MutableStateFlow("")
    val defaultDevice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Backend health for the shell's status strip + You-tab badge. */
    val health: StateFlow<com.hermes.client.data.network.GatewayHealth> = healthMonitor.health

    /** The Mac's Hermes against the app's REST contract; null when there is nothing to say. */
    val hermesContract: StateFlow<com.hermes.client.data.network.HermesContractNotice?> =
        healthMonitor.contract

    init {
        viewModelScope.launch { profileManager.refresh() }
        viewModelScope.launch {
            accountSessions.session.collect { account ->
                syncWorkspaceIdentity(account)
            }
        }
    }

    fun refreshWorkspaceIdentity() = syncWorkspaceIdentity(accountSessions.session.value)

    private fun syncWorkspaceIdentity(account: com.hermes.client.data.auth.AccountSession?) {
        defaultDevice.value = account?.selectedDeviceId
        val identity = com.hermes.client.ui.workspace.workspaceSessionKey(listOf(
            account?.baseUrl ?: runCatching { credentials.load()?.baseUrl }.getOrNull(), account?.accountId ?: "legacy",
        ))
        runtimeStore.bindPendingAttachmentOwner(identity)
        drafts.bindOwner(identity)
        workspaceIdentity.value = identity
    }

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
