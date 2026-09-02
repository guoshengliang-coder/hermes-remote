package com.hermes.client.ui.sessions

import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectLane
import com.hermes.client.domain.ProjectRepo
import com.hermes.client.domain.Session

/**
 * Group id for the DEFAULT project: the gateway's launch directory. Every session the phone
 * creates from the Sessions segment lands here (no explicit cwd), as does any desktop session
 * started without a folder. It is a real project, not "no project" — it is always listed first.
 */
const val DEFAULT_PROJECT_ID = "__default_project__"

/** The path a session is grouped by: the resolved git repo root, else its working directory. */
fun projectKeyOf(session: Session): String? =
    session.gitRepoRoot?.ifBlank { null } ?: session.cwd?.ifBlank { null }

/** Whether [path] is the gateway launch directory (the default project), ignoring trailing slashes. */
fun isDefaultProjectPath(path: String?, defaultProjectPath: String?): Boolean {
    val a = path?.trimEnd('/', '\\')?.ifBlank { null } ?: return false
    val b = defaultProjectPath?.trimEnd('/', '\\')?.ifBlank { null } ?: return false
    return a == b
}

/**
 * Display label of the session's project, or null when the session belongs to the default project.
 * Rows render nothing for the default project (absence = default); the chat subtitle and the
 * project picker spell it out as 「默认项目」.
 */
fun projectLabelOf(session: Session, defaultProjectPath: String? = null): String? =
    projectLabelOfPath(session.cwd, session.gitRepoRoot, defaultProjectPath)

/** [projectLabelOf] for raw workspace facts (chat `session.info` carries cwd/branch, not a Session). */
fun projectLabelOfPath(cwd: String?, gitRepoRoot: String?, defaultProjectPath: String? = null): String? {
    val key = gitRepoRoot?.ifBlank { null } ?: cwd?.ifBlank { null } ?: return null
    if (isDefaultProjectPath(key, defaultProjectPath)) return null
    return basename(key)
}

/**
 * Projects derived client-side from the session list, grouped by git repo root (cwd fallback).
 *
 * Stopgap: the gateway's `projects.tree` is pinned to the launch (default) profile and takes no
 * profile param, so it can't serve a selected tenant's projects to the phone. Until the gateway
 * gains a `profile` param, this lists every folder that has chats in the given sessions.
 *
 * The default project ([DEFAULT_PROJECT_ID]) is ALWAYS the first entry, even with zero sessions,
 * because the Sessions-segment FAB creates into it. Sessions with no cwd, or whose key equals
 * [defaultProjectPath] (learned from a top-level `session.create`), belong to it. The remaining
 * projects follow, most recently active first. Each project carries all its sessions in a single
 * lane; [Project.previewSessions] is the three most-recent.
 */
fun deriveProjectsFromSessions(
    sessions: List<Session>,
    defaultProjectPath: String? = null,
): List<Project> {
    val byKey = sessions.groupBy { s ->
        projectKeyOf(s)?.takeUnless { isDefaultProjectPath(it, defaultProjectPath) }
    }

    val projects = byKey
        .filterKeys { it != null }
        .map { (key, rows) -> buildProject(key!!, basename(key), key, rows) }
        .sortedWith(
            compareByDescending<Project> { it.lastActive ?: Long.MIN_VALUE }
                .thenBy { it.label.lowercase() },
        )

    val default = buildProject(
        id = DEFAULT_PROJECT_ID,
        label = "Default project",
        path = defaultProjectPath?.trimEnd('/', '\\')?.ifBlank { null },
        rows = byKey[null].orEmpty(),
    )
    return listOf(default) + projects
}

private fun buildProject(id: String, label: String, path: String?, rows: List<Session>): Project {
    val byRecency = rows.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }
    return Project(
        id = id,
        label = label,
        path = path,
        color = null,
        isAuto = true,
        sessionCount = rows.size,
        lastActive = rows.mapNotNull { it.lastActive }.maxOrNull(),
        repos = listOf(
            ProjectRepo(
                id = id,
                label = label,
                path = path,
                sessionCount = rows.size,
                lanes = listOf(
                    ProjectLane(id = "all", label = "", path = null, isMain = true, sessions = byRecency),
                ),
            ),
        ),
        previewSessions = byRecency.take(3),
    )
}

/** The project a session currently belongs to within [projects] (default when unmatched). */
fun projectOf(session: Session, projects: List<Project>): Project? {
    val key = projectKeyOf(session)
    return projects.firstOrNull { it.id != DEFAULT_PROJECT_ID && it.id == key }
        ?: projects.firstOrNull { it.id == DEFAULT_PROJECT_ID }
}

private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
