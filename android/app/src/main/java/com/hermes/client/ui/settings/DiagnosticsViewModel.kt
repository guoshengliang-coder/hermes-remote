package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {

    val enabled: StateFlow<Boolean> =
        settings.debugLogging.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live diagnostic entries (newest last in the buffer). */
    val entries: StateFlow<List<DebugLog.LogEntry>> = DebugLog.entries

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { settings.setDebugLogging(on) }
        // Apply immediately so capture starts/stops without waiting for the persisted-flow
        // collector; the app-start collector keeps it in sync across launches.
        DebugLog.setEnabled(on)
    }

    fun clear() = DebugLog.clear()

    /** Session ids the current entries mention, most recent first — the filter chips. */
    val sessionIds: StateFlow<List<String>> = DebugLog.entries
        .map(DebugLog::sessionIdsIn)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun export(sessionId: String? = null): String = DebugLog.export(sessionId)
}
