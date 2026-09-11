package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ProjectCatalog
import com.hermes.client.data.repository.ProjectPrefsStore
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Supplies [LocalProjectNames] for the whole app.
 *
 * Exists so that naming a chat's project does not force four screens to each hold a project list.
 * It owns nothing: the list lives in [ProjectCatalog], and this only keeps it warm (on start and
 * on every identity switch) and exposes the lookup as a plain function for composition.
 */
@HiltViewModel
class ProjectNamesViewModel @Inject constructor(
    private val catalog: ProjectCatalog,
    private val profileManager: ProfileManager,
    projectPrefs: ProjectPrefsStore,
) : ViewModel() {

    val nameFor: StateFlow<(Session) -> String?> =
        combine(catalog.projects, projectPrefs.defaultProjectPath) { _, defaultPath ->
            { session: Session -> catalog.nameFor(session, defaultPath) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000)) { _ -> null }

    init {
        // Projects are per-identity from the app's point of view, so the list is refetched
        // whenever the identity changes — including its first value on launch.
        viewModelScope.launch {
            profileManager.active.collect { runCatching { catalog.refresh() } }
        }
    }
}
