package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.bool
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.PinStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ViewModeStore
import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
    // I1: true when the server returned 401 — nav should route to Setup
    val unauthorized: Boolean = false,
)

/** Projects-mode state: [tree] is the overview; [scope] is the drilled-in hydrated project (null = overview). */
data class ProjectsUiState(
    val tree: List<Project> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
    val scope: Project? = null,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val chat: ChatRepository,
    private val profileManager: ProfileManager,
    private val pinStore: PinStore,
    private val viewModeStore: ViewModeStore,
    private val runtimeStore: SessionRuntimeStore,
    private val tools: com.hermes.client.data.repository.ToolsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SessionsUiState(
            sessions = sessions.cachedAllProfiles().let { cached ->
                val active = profileManager.active.value
                if (active.isNullOrBlank()) cached else cached.filter { it.profile == active }
            },
        ),
    )
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()
    val runtimes: StateFlow<Map<SessionRuntimeKey, SessionRuntime>> = runtimeStore.runtimes
    val unreadTokens: StateFlow<Set<String>> = runtimeStore.unreadTokens

    fun runtimeFor(
        session: Session,
        values: Map<SessionRuntimeKey, SessionRuntime> = runtimes.value,
    ): SessionRuntime? = values[SessionRuntimeKey(session.profile, session.id)]
        ?: values.values.firstOrNull { it.key.sessionId == session.id }

    /** The active profile, shown as a subtitle so the tenant context is always visible. */
    val activeProfile: StateFlow<String?> = profileManager.active

    /** All profiles, for the in-place profile switcher on the Chats top bar. */
    val profiles: StateFlow<List<com.hermes.client.data.network.ProfileDto>> = profileManager.list

    /** Name of the profile a switch just failed for (gateway write refused); null otherwise. */
    private val _switchFailed = MutableStateFlow<String?>(null)
    val switchFailed: StateFlow<String?> = _switchFailed.asStateFlow()

    fun clearSwitchFailed() { _switchFailed.value = null }

    /** Switch the active profile; the list re-fetches automatically (init collects active).
     *  On failure the active profile is left untouched and [switchFailed] carries the name. */
    fun switchProfile(name: String) = viewModelScope.launch {
        if (!profileManager.switchTo(name)) _switchFailed.value = name
    }

    /**
     * Raw pinned tokens ("<profile>/<sessionId>", device-local). The list spans all profiles, so
     * the UI must test each session against its OWN profile token — not the active profile — or a
     * pin made in another profile would vanish. Pins do not sync to desktop (no gateway pin API).
     */
    val pinnedTokens: StateFlow<Set<String>> =
        pinStore.pinned.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True if [session] is pinned, keyed by the session's own profile. */
    fun isPinned(session: Session, tokens: Set<String> = pinnedTokens.value): Boolean =
        PinStore.token(session.profile, session.id) in tokens

    /** Persisted view mode (Sessions flat list vs the gateway project tree). */
    val viewMode: StateFlow<ViewMode> =
        viewModeStore.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewMode.SESSIONS)

    private val _projects = MutableStateFlow(ProjectsUiState())
    val projectsState: StateFlow<ProjectsUiState> = _projects.asStateFlow()

    /** Archived sessions for the ARCHIVED segment (scoped to the active profile). */
    data class ArchivedUiState(
        val sessions: List<Session> = emptyList(),
        val loading: Boolean = false,
        val error: AppError? = null,
    )
    private val _archived = MutableStateFlow(ArchivedUiState())
    val archivedState: StateFlow<ArchivedUiState> = _archived.asStateFlow()

    fun loadArchived() = viewModelScope.launch {
        _archived.value = _archived.value.copy(loading = true, error = null)
        try {
            val active = profileManager.active.value
            val all = sessions.archivedAllProfiles()
            val list = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            _archived.value = ArchivedUiState(sessions = list)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _archived.value = ArchivedUiState(
                error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = e.message, stage = "archived_load"),
            )
        }
    }

    fun unarchive(session: Session) = viewModelScope.launch {
        runCatching { sessions.archive(session.id, archived = false, session.profile) }
            .onSuccess { loadArchived(); refresh() }
    }

    /** Cron jobs failed/overdue for the active profile — drives the list's alert strip. */
    private val _cronAlerts = MutableStateFlow(0)
    val cronAlerts: StateFlow<Int> = _cronAlerts.asStateFlow()

    private fun refreshCronAlerts() = viewModelScope.launch {
        runCatching { tools.cronJobs(profileManager.active.value) }.onSuccess { jobs ->
            _cronAlerts.value = com.hermes.client.ui.activity.needsAttention(jobs, System.currentTimeMillis()).size
        }
    }

    /** Persist the chosen view mode; the [viewMode] observer in init fetches the tree when needed. */
    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { viewModeStore.set(mode) }
    }

    private var projectTreeJob: Job? = null

    /** Build the project overview (also the retry entry point). Latest-wins like [refresh]. */
    fun loadProjectTree() {
        projectTreeJob?.cancel()
        projectTreeJob = viewModelScope.launch {
            _projects.value = _projects.value.copy(loading = true, error = null)
            try {
                // Stopgap: the gateway's projects.tree is pinned to the launch profile, so derive
                // projects client-side from the session list — filtered to the ACTIVE profile,
                // per the app-wide scope rule (everything follows the current profile). Re-wire
                // to a per-profile gateway RPC (see ProjectsRepository) once available.
                val active = profileManager.active.value
                val all = sessions.listAllProfiles()
                val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
                val derived = deriveProjectsFromSessions(scoped)
                _projects.value = _projects.value.copy(loading = false, tree = derived)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _projects.value = _projects.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = e.message, stage = "projects_load"),
                )
            }
        }
    }

    /** Drill into a project. Derived projects already carry all their sessions — no fetch needed. */
    fun enterProject(project: Project) {
        _projects.value = _projects.value.copy(scope = project)
    }

    /** Return to the project overview. */
    fun exitProject() {
        _projects.value = _projects.value.copy(scope = null)
    }

    init {
        chat.connect()
        viewModelScope.launch { profileManager.refresh() }
        // The list is scoped to the active profile (like the desktop, one tenant at a time), so it
        // reloads whenever the selected profile changes — including the first value once it loads.
        // The derived project tree is scoped the same way, so rebuild it too when it was loaded.
        viewModelScope.launch {
            profileManager.active.collect {
                refresh()
                refreshCronAlerts()
                if (_projects.value.tree.isNotEmpty() || viewMode.value == ViewMode.PROJECTS) loadProjectTree()
                if (viewMode.value == ViewMode.ARCHIVED) loadArchived()
            }
        }
        // The gateway auto-titles a new chat after its first message and pushes a `session.title`
        // event; re-fetch so the AI title replaces "Untitled" (and the now-non-empty chat appears).
        // This VM stays in the back stack while a chat is open, so it catches the event live.
        viewModelScope.launch {
            chat.events.collect { event ->
                val shouldRefresh = when (event.type) {
                    "session.title", "message.complete", "error", "gateway.ready" -> true
                    "session.info" -> event.bool("running") == false
                    else -> false
                }
                if (shouldRefresh) scheduleEventRefresh()
            }
        }
        // Fetch the project tree whenever Projects becomes the active view without a loaded tree.
        // Covers both the toggle tap AND a cold launch restored into Projects mode (persisted) —
        // the launch case previously never called loadProjectTree, so Projects showed a spurious
        // "No projects" until the user toggled.
        viewModelScope.launch {
            viewModeStore.mode.collect { mode ->
                if (mode == ViewMode.PROJECTS && _projects.value.tree.isEmpty() && !_projects.value.loading) {
                    loadProjectTree()
                }
                if (mode == ViewMode.ARCHIVED && _archived.value.sessions.isEmpty() && !_archived.value.loading) {
                    loadArchived()
                }
            }
        }
    }

    // Coalesce refresh storms without cancelling an already-running HTTP request. Cancellation used
    // to make a title/completion event race the ON_RESUME refresh, occasionally leaving neither
    // result committed. If another request arrives while loading, one final pass runs afterward.
    private var refreshJob: Job? = null
    private var refreshVersion = 0L
    private var eventRefreshJob: Job? = null

    fun refresh() {
        refreshVersion++
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            do {
                val handlingVersion = refreshVersion
                refreshOnce()
            } while (handlingVersion != refreshVersion)
        }
    }

    private suspend fun refreshOnce() {
        // Keep an already-rendered list completely steady during background reconciliation. The
        // blocking skeleton is only for the first load; terminal/reconnect refreshes stay invisible.
        _state.value = _state.value.copy(
            loading = _state.value.sessions.isEmpty() && !sessions.hasLoadedAllProfiles(),
            error = null,
            unauthorized = false,
        )
        try {
            val active = profileManager.active.value
            val all = sessions.listAllProfiles()
            // A profile switch during the request queues another pass. Do not briefly publish the
            // previous profile's list while that newer request is waiting.
            if (active != profileManager.active.value) return
            val list = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            _state.value = _state.value.copy(
                sessions = list,
                loading = false,
                error = null,
                unauthorized = false,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HermesApiException) {
            if (e.code == 401) {
                _state.value = SessionsUiState(unauthorized = true)
            } else {
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = e.message, stage = "sessions_load"),
                )
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = e.message, stage = "sessions_load"),
            )
        }
    }

    /** Refreshes whichever Chats segment is actually visible while the warm-start gate is up. */
    suspend fun recoverForForeground(): Boolean = when (viewMode.first()) {
        ViewMode.SESSIONS -> {
            refreshOnce()
            !_state.value.unauthorized && _state.value.error == null
        }
        ViewMode.PROJECTS -> try {
            val active = profileManager.active.value
            val all = sessions.listAllProfiles()
            val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            _projects.value = ProjectsUiState(tree = deriveProjectsFromSessions(scoped))
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _projects.value = ProjectsUiState(
                error = AppError(AppErrorCode.RPC_FAILED, true, error.message, "projects_recovery"),
            )
            false
        }
        ViewMode.ARCHIVED -> try {
            val active = profileManager.active.value
            val all = sessions.archivedAllProfiles()
            val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            _archived.value = ArchivedUiState(sessions = scoped)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _archived.value = ArchivedUiState(
                error = AppError(AppErrorCode.RPC_FAILED, true, error.message, "archived_recovery"),
            )
            false
        }
    }

    private fun scheduleEventRefresh() {
        eventRefreshJob?.cancel()
        eventRefreshJob = viewModelScope.launch {
            // The terminal event can precede SQLite visibility by a fraction of a second. Keep the
            // warm list on screen, then do a quick pass and one delayed authoritative pass.
            delay(250L)
            refresh()
            delay(1_250L)
            refresh()
            delay(3_000L)
            refresh()
        }
    }

    /**
     * Make [session]'s profile the active one before the chat opens. The list spans all profiles,
     * but resume/history/slash resolve against the gateway's active per-profile DB — so opening a
     * session from another tenant must switch the active profile first (and await it), or the chat
     * loads against the wrong profile. No-op when the session is already in the active profile.
     */
    suspend fun prepareOpen(session: Session): Boolean {
        val target = session.profile ?: return true
        return target == profileManager.active.value || profileManager.switchTo(target)
    }

    /** Returns the new session id, or null if creation failed (so the UI doesn't crash). */
    suspend fun createSession(): String? =
        runCatching { chat.createSession(profileManager.active.value) }
            // runCatching also catches CancellationException — rethrow it so cancelling the caller
            // isn't swallowed and mistaken for a failed creation.
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    fun rename(session: Session, title: String) = viewModelScope.launch {
        runCatching { sessions.rename(session.id, title, session.profile) }.onSuccess { refresh() }
    }

    fun archive(session: Session) = viewModelScope.launch {
        // Archiving removes it from the active list — must carry the session's profile or the
        // gateway 404s (wrong per-profile DB) and the session never disappears.
        runCatching { sessions.archive(session.id, archived = true, session.profile) }.onSuccess { refresh() }
    }

    fun delete(session: Session) = viewModelScope.launch {
        runCatching { sessions.delete(session.id, session.profile) }.onSuccess { refresh() }
    }

    /** Pin/unpin keyed by the session's OWN profile, so it works regardless of the active one. */
    fun togglePin(session: Session) = viewModelScope.launch {
        pinStore.toggle(PinStore.token(session.profile, session.id))
    }
}
