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
import com.hermes.client.data.repository.ProjectPrefsStore
import com.hermes.client.data.repository.ProjectsRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ViewModeStore
import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.auth.AccountSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
    /** Conversations Hermes had on messaging platforms; only fetched while the Bots segment is on. */
    val botSessions: List<Session> = emptyList(),
    val botsLoading: Boolean = false,
    val botError: AppError? = null,
    /** Channels configured on this Hermes. Zero AND no history means the Bots segment is hidden. */
    val configuredChannels: Int = 0,
)

/** Projects-mode state: [tree] is the overview; [scope] is the drilled-in hydrated project (null = overview). */
data class ProjectsUiState(
    val tree: List<Project> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
    val scope: Project? = null,
    /**
     * Whether this list came from the gateway's projects.db (so projects can be created, renamed
     * and removed) or from the client-side derivation (read-only). See [SessionsViewModel.loadProjectTree].
     */
    val managed: Boolean = false,
    /** Set by a failed edit; cleared once shown. Separate from [error], which owns the whole list. */
    val editError: AppError? = null,
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
    private val projectPrefs: ProjectPrefsStore,
    private val projectsRepo: ProjectsRepository,
    private val projectCatalog: com.hermes.client.data.repository.ProjectCatalog,
    private val draftStore: com.hermes.client.data.repository.DraftSnapshot,
    private val accountSessions: AccountSessionManager? = null,
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
    ): SessionRuntime? = values[SessionRuntimeKey(session.profile, session.id, session.deviceId)]
        ?: values.values.firstOrNull {
            it.key.sessionId == session.id && (session.deviceId == null || it.key.deviceId == session.deviceId)
        }

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
     *
     * **null means "not read yet", and the list must not render until it resolves** (HG-11). With
     * an `emptySet()` seed the first frame drew a list with no 已置顶 section; the pins then landed
     * a beat later and that whole section was INSERTED at the top of a LazyColumn that anchors on
     * the row already in view — so the section, and the pinned rows with it, ended up above the
     * viewport. The user had to scroll back up to find pins they had every reason to think were
     * lost. Reading a DataStore is cheap next to the network round trip the list already waits on,
     * so gating the first frame on it costs nothing visible.
     *
     * A pin store that cannot be read degrades to "no pins" rather than hanging that gate forever.
     */
    val pinnedTokens: StateFlow<Set<String>?> =
        pinStore.pinned
            .catch { emit(emptySet()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True if [session] is pinned, keyed by the session's own profile. Unread pins pin nothing. */
    fun isPinned(session: Session, tokens: Set<String>? = pinnedTokens.value): Boolean =
        PinStore.token(session.profile, session.id, session.deviceId) in tokens.orEmpty()

    /**
     * Conversations holding unsent composer text (HG-41). Same shape and the same `null` gate as
     * [pinnedTokens], and for the same reason: a marker that arrives a frame after the rows do is
     * a list that visibly changes under the user's eyes.
     */
    val draftTokens: StateFlow<Set<String>?> =
        draftStore.tokens
            .catch { emit(emptySet()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True if [session] has an unsent draft, keyed by the session's own profile. */
    fun hasDraft(session: Session, tokens: Set<String>? = draftTokens.value): Boolean =
        com.hermes.client.data.repository.SessionReadStore.token(
            session.profile, session.id, session.deviceId,
        ) in tokens.orEmpty()

    /**
     * Bumped when the user pins a session, so the list can bring the 已置顶 section into view.
     * A counter rather than a flag: pinning twice in a row must reveal twice.
     */
    private val _pinRevealRequests = MutableStateFlow(0L)
    val pinRevealRequests: StateFlow<Long> = _pinRevealRequests.asStateFlow()

    /** Persisted view mode (Sessions flat list vs the gateway project tree). */
    val viewMode: StateFlow<ViewMode> =
        viewModeStore.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewMode.SESSIONS)

    private val _projects = MutableStateFlow(ProjectsUiState())
    val projectsState: StateFlow<ProjectsUiState> = _projects.asStateFlow()

    /**
     * The gateway's launch directory (the default project), learned from a top-level
     * `session.create`. Null until one has been made on this device; derivation then folds any
     * session living in that folder into the default project instead of a same-named project.
     */
    val defaultProjectPath: StateFlow<String?> =
        projectPrefs.defaultProjectPath.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Projects whose one-time "sessions created here join <project>" notice was shown; null until loaded. */
    val introSeen: StateFlow<Set<String>?> =
        projectPrefs.introSeen.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markIntroSeen(projectId: String) {
        viewModelScope.launch { projectPrefs.markIntroSeen(projectId) }
    }

    /** Archived sessions for the Archived page (scoped to the active profile). */
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
        runCatching { sessions.archive(session.id, archived = false, session.profile, session.deviceId) }
            .onSuccess { loadArchived(); refresh() }
    }

    /**
     * What the one alert slot on this screen should say. Cron trouble and channel trouble share
     * it, merged root-cause-first: a channel that is down absorbs the deliveries it swallowed,
     * so one outage reads as one problem instead of one per report it stopped.
     */
    private val _health = MutableStateFlow(com.hermes.client.ui.activity.MergedHealth())
    val health: StateFlow<com.hermes.client.ui.activity.MergedHealth> = _health.asStateFlow()

    private fun refreshCronAlerts() = viewModelScope.launch {
        val profile = profileManager.active.value
        val jobs = runCatching { tools.cronJobs(profile) }.getOrNull() ?: return@launch
        // A channel read that fails leaves the merge with no root causes — cron alerts then stand
        // on their own, which is the pre-merge behaviour rather than a blank strip.
        val platforms = runCatching { tools.messagingPlatforms(profile) }.getOrDefault(emptyList())
        _health.value = com.hermes.client.ui.activity.mergeHealth(jobs, platforms, System.currentTimeMillis())
        _state.value = _state.value.copy(configuredChannels = platforms.count { it.configured })
    }

    /** Persist the chosen view mode; the [viewMode] observer in init fetches the tree when needed. */
    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { viewModeStore.set(mode) }
    }

    private var projectTreeJob: Job? = null

    /**
     * Whether the gateway's own project list is the one we may read and write.
     *
     * Upstream resolves `projects.db` from the gateway process's HERMES_HOME and no `projects.*`
     * method takes a profile, so those calls always land on the DEFAULT profile's database
     * (`is_default` marks exactly the profile whose home is that root). Reading it while the app
     * is scoped to another tenant would show one profile's folders around another's chats, so the
     * other profiles keep the client-side derivation and stay read-only.
     */
    private fun projectsAreManaged(): Boolean = projectCatalog.isManaged()

    /**
     * The list behind「移动到项目」and every row's project name. Shared with the Projects page
     * through [com.hermes.client.data.repository.ProjectCatalog] so the two cannot disagree.
     */
    val pickerProjects: StateFlow<List<Project>> = projectCatalog.projects

    /** Warm the shared list without disturbing the Projects page's own loading/error state. */
    fun refreshPickerProjects() {
        viewModelScope.launch { runCatching { projectCatalog.refresh() } }
    }

    /** Build the project overview (also the retry entry point). Latest-wins like [refresh]. */
    fun loadProjectTree() {
        projectTreeJob?.cancel()
        projectTreeJob = viewModelScope.launch {
            _projects.value = _projects.value.copy(loading = true, error = null)
            try {
                val managed = projectsAreManaged()
                val list = if (managed) serverProjects() else derivedProjects()
                // Keep the drilled-in project by id so a rebuild never kicks the user back to
                // the overview. Within a run only: Projects is a pushed page now, so a cold launch
                // deliberately opens on the overview (docs/DESIGN.md §5.3, 2026-09-09).
                val open = _projects.value.scope
                _projects.value = _projects.value.copy(
                    loading = false,
                    tree = list,
                    managed = managed,
                    // A rebuilt node carries fresh counts but only preview sessions, so a hydrated
                    // scope keeps its sessions rather than blanking the drill-in mid-refresh.
                    scope = open?.let { s -> list.firstOrNull { it.id == s.id }?.let { fresh -> mergeScope(fresh, s) } },
                )
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

    private suspend fun serverProjects(): List<Project> = projectCatalog.server()

    private suspend fun derivedProjects(): List<Project> = projectCatalog.derived()

    /** Carry a hydrated drill-in's sessions across a tree rebuild that only returns previews. */
    private fun mergeScope(fresh: Project, open: Project): Project =
        if (fresh.repos.sumOf { r -> r.lanes.sumOf { it.sessions.size } } == 0 && open.repos.isNotEmpty()) {
            fresh.copy(repos = open.repos)
        } else {
            fresh
        }

    /**
     * Drill into a project. Derived projects already carry all their sessions; server nodes carry
     * only preview rows, so those are hydrated with `projects.project_sessions`. The scope is set
     * first either way, so the page opens immediately and fills in.
     */
    fun enterProject(project: Project) {
        _projects.value = _projects.value.copy(scope = project)
        if (!_projects.value.managed || project.id == DEFAULT_PROJECT_ID) return
        viewModelScope.launch {
            runCatching { projectsRepo.projectSessions(project.id) }
                .onSuccess { hydrated ->
                    // Ignore a late arrival for a project the user already left.
                    if (hydrated != null && _projects.value.scope?.id == project.id) {
                        // Hydration returns the same node shape as the tree, so it needs the
                        // same __no_project__ → default-project rename.
                        _projects.value = _projects.value.copy(
                            scope = if (hydrated.isNoProject) hydrated.copy(id = DEFAULT_PROJECT_ID) else hydrated,
                        )
                    }
                }
        }
    }

    /** Return to the project overview. */
    fun exitProject() {
        _projects.value = _projects.value.copy(scope = null)
    }

    // ── Project management ──────────────────────────────────────────────────────────────────
    // Only reachable while [ProjectsUiState.managed]; every call writes the gateway's projects.db
    // and then rebuilds the tree, because upstream re-derives membership by path on every read.

    /** Create a project owning [folder]. Rebuilds the tree so the new row appears with its counts. */
    fun createProject(name: String, folder: String?, icon: String?, color: String?) =
        editProject("project_create") { projectsRepo.create(name.trim(), folder, icon, color) }

    /** Rename and/or restyle. Upstream patches only the fields it receives, so nulls are safe. */
    fun updateProject(id: String, name: String? = null, icon: String? = null, color: String? = null) =
        editProject("project_update") { projectsRepo.update(id, name?.trim(), icon, color) }

    fun addProjectFolder(id: String, path: String) =
        editProject("project_add_folder") { projectsRepo.addFolder(id, path) }

    fun removeProjectFolder(id: String, path: String) =
        editProject("project_remove_folder") { projectsRepo.removeFolder(id, path) }

    fun setProjectPrimaryFolder(id: String, path: String) =
        editProject("project_set_primary") { projectsRepo.setPrimary(id, path) }

    /**
     * Remove the grouping. Upstream deletes the row and its folder rows only — chats are untouched
     * and nothing on disk moves, so the folder reappears immediately as an auto project. Leaving
     * the drill-in is part of the action: the project the user was inside no longer exists.
     */
    fun deleteProject(id: String) =
        editProject("project_delete") {
            projectsRepo.delete(id)
            if (_projects.value.scope?.id == id) _projects.value = _projects.value.copy(scope = null)
        }

    private fun editProject(stage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                loadProjectTree()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _projects.value = _projects.value.copy(editError = projectEditError(e, stage))
            }
        }
    }

    /** One-shot: the edit failure has been shown. */
    fun clearProjectEditError() {
        _projects.value = _projects.value.copy(editError = null)
    }

    /** One directory level on the Mac, for the folder picker. Throws so the caller can show HR-SESS-012. */
    suspend fun browseFolder(path: String): List<com.hermes.client.data.network.FsEntryDto> {
        val page = sessions.browseFolder(path)
        if (page.error != null) throw IllegalStateException(page.error)
        return page.entries.filter { it.isDirectory }
    }

    /** Where the folder picker opens: the gateway's own working directory. */
    suspend fun defaultBrowseFolder(): String? = sessions.defaultBrowseFolder()

    /** The repo root containing [path], for the picker's "this is a git repo" hint. */
    suspend fun gitRootOf(path: String): String? = runCatching { sessions.gitRootOf(path) }.getOrNull()

    init {
        restoreSelectedRoute()
        viewModelScope.launch { profileManager.refresh() }
        // The list is scoped to the active profile (like the desktop, one tenant at a time), so it
        // reloads whenever the selected profile changes — including the first value once it loads.
        // The derived project tree is scoped the same way, so rebuild it too when it was loaded.
        viewModelScope.launch {
            profileManager.active.collect {
                refresh()
                refreshCronAlerts()
                if (_projects.value.tree.isNotEmpty()) loadProjectTree()
                if (_archived.value.sessions.isNotEmpty()) loadArchived()
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
    }

    // Coalesce refresh storms without cancelling an already-running HTTP request. Cancellation used
    // to make a title/completion event race the ON_RESUME refresh, occasionally leaving neither
    // result committed. If another request arrives while loading, one final pass runs afterward.
    private var refreshJob: Job? = null
    private var refreshVersion = 0L
    private var eventRefreshJob: Job? = null

    fun refresh() {
        // A row saying 思考中 is the store's belief, not the server's; a user-driven refresh asks.
        runtimeStore.probeActiveRuntimes(reason = "list-refresh", staleOnly = false)
        refreshVersion++
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            do {
                val handlingVersion = refreshVersion
                refreshOnce()
            } while (handlingVersion != refreshVersion)
        }
    }

    /** Returning to the list ends any chat-specific Mac route and restores the selected Mac. */
    fun onVisible() {
        restoreSelectedRoute()
        refresh()
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

    /**
     * Loads the Bots segment. Kept off the Chats path deliberately: the cross-profile list the
     * Chats view uses filters messaging sources out, so this is a second read of the same
     * endpoint rather than a filter over cached rows.
     */
    fun loadBots() = viewModelScope.launch {
        _state.value = _state.value.copy(botsLoading = true, botError = null)
        runCatching { sessions.botSessions() }
            .onSuccess { _state.value = _state.value.copy(botSessions = it, botsLoading = false) }
            .onFailure {
                _state.value = _state.value.copy(
                    botsLoading = false,
                    botError = AppError(
                        AppErrorCode.RPC_FAILED,
                        retryable = true,
                        technicalCause = it.message,
                        stage = "bot_sessions_load",
                    ),
                )
            }
    }

    /** How many channels this Hermes has configured — the Bots segment's visibility depends on it. */
    fun refreshChannelCount() = viewModelScope.launch {
        runCatching { tools.messagingPlatforms(profileManager.active.value) }
            .onSuccess { platforms ->
                _state.value = _state.value.copy(configuredChannels = platforms.count { it.configured })
            }
        // A failure leaves the count alone: the segment should not blink out because one poll
        // failed, and the session history alone can still justify showing it.
    }

    /** Refreshes the Chats list while the warm-start gate is up. Both segments read it. */
    suspend fun recoverForForeground(): Boolean {
        restoreSelectedRoute()
        refreshOnce()
        return !_state.value.unauthorized && _state.value.error == null
    }

    /** Warm-start recovery for the Projects page. */
    suspend fun recoverProjectsForForeground(): Boolean {
        restoreSelectedRoute()
        return try {
            // A launch/profile refresh may still be rebuilding the same tree. Cancel it before
            // the gate performs its authoritative refresh, otherwise the older response can land
            // last and reveal stale project contents after recovery completes.
            projectTreeJob?.cancel()
            val active = profileManager.active.value
            val all = sessions.listAllProfiles()
            val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            val tree = deriveProjectsFromSessions(scoped, defaultProjectPath.value)
            val openProjectId = _projects.value.scope?.id
            _projects.value = ProjectsUiState(
                tree = tree,
                scope = openProjectId?.let { id -> tree.firstOrNull { it.id == id } },
            )
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _projects.value = ProjectsUiState(
                error = AppError(AppErrorCode.RPC_FAILED, true, error.message, "projects_recovery"),
            )
            false
        }
    }

    /** Warm-start recovery for the Archived page. */
    suspend fun recoverArchivedForForeground(): Boolean {
        restoreSelectedRoute()
        return try {
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

    /** Outcome of [createSession]: the durable id, and whether a requested project folder was refused. */
    data class CreateResult(
        val id: String,
        val fellBackToDefault: Boolean,
        val deviceId: String? = null,
    )

    /**
     * Creates a session in [cwd] (a project folder) or, when null, in the gateway's launch
     * directory — the default project. A top-level create teaches [defaultProjectPath]. When the
     * gateway silently falls back because [cwd] no longer exists on the Mac, the session still
     * opens but [CreateResult.fellBackToDefault] is set so the UI can say so (HR-SESS-006).
     * Returns null if creation failed (so the UI doesn't crash).
     */
    suspend fun createSession(cwd: String? = null): CreateResult? {
        restoreSelectedRoute()
        val created = runCatching { chat.createSession(profileManager.active.value, cwd) }
            // runCatching also catches CancellationException — rethrow it so cancelling the caller
            // isn't swallowed and mistaken for a failed creation.
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return null
        if (cwd.isNullOrBlank()) {
            created.cwd?.let { projectPrefs.setDefaultProjectPath(it) }
            sessions.bindConversation(profileManager.active.value, created.id)
            return CreateResult(created.id, fellBackToDefault = false, deviceId = sessions.currentDeviceId())
        }
        val fellBack = created.cwd != null && !isDefaultProjectPath(created.cwd, cwd)
        sessions.bindConversation(profileManager.active.value, created.id)
        return CreateResult(created.id, fellBackToDefault = fellBack, deviceId = sessions.currentDeviceId())
    }

    private fun restoreSelectedRoute() {
        if (accountSessions?.restoreSelectedDeviceRoute() == true) chat.reconnect() else chat.connect()
    }

    /**
     * Re-homes [session] into [project] via `session.workspace.move`. Returns the product error on
     * failure (busy session, missing folder, …) or null on success; the list and tree refresh.
     */
    suspend fun moveToProject(session: Session, project: Project): AppError? {
        val path = project.path
            ?: return AppError(AppErrorCode.PROJECT_FOLDER_MISSING, retryable = false, stage = "workspace_move")
        return runCatching { chat.moveWorkspace(session.id, path, session.profile) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .fold(
                onSuccess = {
                    // The gateway answers a move with a `session.info` (running=false) for a live
                    // session; for a row that is not on screen the runtime store reads that as a
                    // completed-unread run. The user just acted on this session, so clear it —
                    // once now and once after the event has had time to land.
                    val key = runtimeStore.runtimes.value.keys.firstOrNull { it.sessionId == session.id }
                        ?: SessionRuntimeKey(session.profile, session.id, session.deviceId)
                    runtimeStore.markRead(key)
                    viewModelScope.launch { delay(750L); runtimeStore.markRead(key) }
                    refresh()
                    if (_projects.value.tree.isNotEmpty()) loadProjectTree()
                    null
                },
                onFailure = { workspaceMoveError(it) },
            )
    }

    fun rename(session: Session, title: String) = viewModelScope.launch {
        runCatching { sessions.rename(session.id, title, session.profile, session.deviceId) }.onSuccess { refresh() }
    }

    fun archive(session: Session) = viewModelScope.launch {
        // Archiving removes it from the active list — must carry the session's profile or the
        // gateway 404s (wrong per-profile DB) and the session never disappears.
        runCatching {
            sessions.archive(session.id, archived = true, session.profile, session.deviceId)
        }.onSuccess { refresh() }
    }

    fun delete(session: Session) = viewModelScope.launch {
        runCatching { sessions.delete(session.id, session.profile, session.deviceId) }.onSuccess { refresh() }
    }

    /** Pin/unpin keyed by the session's OWN profile, so it works regardless of the active one. */
    fun togglePin(session: Session) = viewModelScope.launch {
        // Pinning lifts the row into a section above wherever the reader is standing; without the
        // reveal it simply vanishes from under their finger (HG-11). Unpinning moves it back down
        // into its recency group, which needs no chase.
        if (pinStore.toggle(PinStore.token(session.profile, session.id, session.deviceId))) {
            _pinRevealRequests.value += 1
        }
    }
}
