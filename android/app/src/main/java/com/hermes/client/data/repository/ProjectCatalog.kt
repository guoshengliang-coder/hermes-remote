package com.hermes.client.data.repository

import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.ui.sessions.DEFAULT_PROJECT_ID
import com.hermes.client.ui.sessions.deriveProjectsFromSessions
import com.hermes.client.ui.sessions.folderPaths
import com.hermes.client.ui.sessions.projectLabelOfPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The one list of projects the whole app shows — the Projects page, the「移动到项目」picker on the
 * session list, and the same picker behind the chat's workspace subtitle.
 *
 * It exists because those three used to build the list separately, and after the Projects page
 * started reading the gateway they disagreed: the page showed real project names, glyphs and
 * colours while both pickers still listed folder basenames (and junk roots like `/Users`, which
 * the gateway filters out and the derivation does not). Anything that needs "the projects you
 * could put a chat into" asks here.
 *
 * Two sources, one rule (see [isManaged]).
 */
class ProjectCatalog(
    private val projectsRepo: ProjectsRepository,
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
    private val projectPrefs: ProjectPrefsStore,
) {
    /**
     * Whether the gateway's own project list is the one to read.
     *
     * Upstream resolves `projects.db` from the gateway process's HERMES_HOME and no `projects.*`
     * method takes a profile, so those calls always land on the DEFAULT profile's database
     * (`is_default` marks exactly the profile whose home is that root). Reading it while the app
     * is scoped to another tenant would wrap one profile's folders around another's chats, so
     * every other profile keeps the client-side derivation.
     */
    fun isManaged(): Boolean {
        val active = profileManager.active.value ?: return false
        val default = profileManager.list.value.firstOrNull { it.isDefault }?.name ?: DEFAULT_PROFILE_NAME
        return active == default
    }

    private val _projects = MutableStateFlow<List<Project>>(emptyList())

    /**
     * The last list fetched, for surfaces that only need to NAME a chat's project and must not
     * each trigger their own fetch — the session rows, the archive, the search results and the
     * chat's workspace subtitle. Empty until someone calls [refresh]; [nameFor] falls back to the
     * folder basename while it is, which is exactly the pre-existing behaviour.
     */
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /**
     * Fetch the current list and publish it to [projects]. Throws when the gateway is the source
     * and the call fails — the Projects page turns that into its error state. A caller for whom an
     * empty sheet is worse than a stale list should catch and fall back to [derived].
     */
    suspend fun refresh(): List<Project> =
        (if (isManaged()) server() else derived()).also { _projects.value = it }

    /**
     * What to call [session]'s project, using the cached list.
     *
     * Longest-prefix match on the folders a project owns, against the session's git root and then
     * its cwd — the same rule the gateway groups by, so a chat's row agrees with the page it is
     * listed under. Null means the default project, which every caller renders as absence.
     *
     * Falls back to the folder basename when the cache is empty or nothing matches, so a row never
     * loses its subtitle waiting for a fetch.
     */
    fun nameFor(session: Session, defaultProjectPath: String?): String? =
        nameForPath(session.cwd, session.gitRepoRoot, defaultProjectPath)

    /** [nameFor] for raw workspace facts — the chat header has cwd/branch, not a Session. */
    fun nameForPath(cwd: String?, gitRepoRoot: String?, defaultProjectPath: String?): String? {
        val key = gitRepoRoot?.ifBlank { null } ?: cwd?.ifBlank { null } ?: return null
        val owner = _projects.value
            .filter { it.id != DEFAULT_PROJECT_ID }
            .flatMap { project -> project.folderPaths().map { it to project } }
            .filter { (folder, _) -> key == folder || key.startsWith("$folder/") }
            .maxByOrNull { (folder, _) -> folder.length }
            ?.second
        if (owner != null) return owner.label.ifBlank { null }
        // Cache cold, or the chat lives somewhere no project claims.
        return projectLabelOfPath(cwd, gitRepoRoot, defaultProjectPath)
    }

    /** The gateway's tree, normalized to this app's default-project convention. */
    suspend fun server(): List<Project> =
        projectsRepo.tree().projects
            .map(::normalize)
            .sortedBy { it.id != DEFAULT_PROJECT_ID }

    /** Folders that happen to hold chats, scoped to the active profile. Never fails. */
    suspend fun derived(): List<Project> {
        val active = profileManager.active.value
        val all = sessions.listAllProfiles()
        val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
        return deriveProjectsFromSessions(scoped, defaultProjectPath())
    }

    /** [derived] without a network call, for callers already holding a warm session cache. */
    suspend fun derivedFromCache(profile: String?): List<Project> {
        val scoped = sessions.cachedAllProfiles()
            .filter { profile.isNullOrBlank() || it.profile == profile }
        return deriveProjectsFromSessions(scoped, defaultProjectPath())
    }

    private suspend fun defaultProjectPath(): String? =
        runCatching { projectPrefs.defaultProjectPath.first() }.getOrNull()

    /**
     * Upstream calls the bucket for chats that belong to no project `__no_project__`; this app has
     * always called it 「默认项目」 and pins it first with the house glyph. Same idea, one name —
     * and renaming it on the way in keeps every convention built on that id working: pinned first,
     * created into with no cwd, marked 「当前」 by the picker.
     */
    private fun normalize(project: Project): Project =
        if (project.isNoProject) project.copy(id = DEFAULT_PROJECT_ID) else project

    companion object {
        /**
         * The profile whose HERMES_HOME is the Hermes root, and therefore the one whose
         * `projects.db` every `projects.*` call resolves to. Used only when `/api/profiles`
         * marked none.
         */
        const val DEFAULT_PROFILE_NAME = "default"
    }
}
