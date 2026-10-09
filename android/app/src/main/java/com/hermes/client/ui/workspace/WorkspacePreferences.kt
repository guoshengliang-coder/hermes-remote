package com.hermes.client.ui.workspace

import com.hermes.client.data.repository.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Optional persistence. A failed disk read/write leaves an immediately usable in-memory choice. */
class WorkspacePreferenceState(
    source: Flow<WorkspacePreference>,
    write: suspend (WorkspacePreference) -> Unit,
    scope: CoroutineScope,
) {
    private val current = MutableStateFlow(WorkspacePreference())
    val state = current.asStateFlow()
    private val changes = Channel<WorkspacePreference>(Channel.CONFLATED)
    @Volatile private var edited = false

    init {
        scope.launch { source.catch { }.collect { if (!edited) current.value = it.normalized() } }
        scope.launch {
            for (choice in changes) {
                try { write(choice) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the choice in memory; layout remains usable. */ }
            }
        }
    }

    fun set(value: WorkspacePreference) { edited = true; current.value = value.normalized(); changes.trySend(current.value) }
    fun mode(value: WorkspaceMode) = set(state.value.copy(mode = value))
    fun width(value: Float) = set(state.value.copy(listWidth = value))
    fun resetWidth() = width(300f)
}

@Singleton
class WorkspacePreferences @Inject constructor(settings: SettingsStore, scope: CoroutineScope) {
    val choices = WorkspacePreferenceState(settings.workspacePreference, settings::setWorkspacePreference, scope)
}
