package com.hermes.client.data.repository

import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectTree
import com.hermes.client.domain.Session
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.ui.sessions.DEFAULT_PROJECT_ID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectCatalogTest {
    private val projectsRepo = mockk<ProjectsRepository>(relaxed = true)
    private val sessions = mockk<SessionRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val projectPrefs = mockk<ProjectPrefsStore>(relaxed = true)

    private fun catalog(active: String? = "default", default: String? = "default"): ProjectCatalog {
        every { profileManager.active } returns MutableStateFlow(active)
        every { profileManager.list } returns MutableStateFlow(
            listOfNotNull(default?.let { ProfileDto(name = it, isDefault = true) }),
        )
        every { projectPrefs.defaultProjectPath } returns flowOf(null)
        return ProjectCatalog(projectsRepo, sessions, profileManager, projectPrefs)
    }

    private fun project(id: String, label: String, path: String, repos: List<String> = emptyList()) =
        Project(
            id = id, label = label, path = path, color = null, icon = null, isAuto = false,
            sessionCount = 0, lastActive = null,
            repos = repos.map {
                com.hermes.client.domain.ProjectRepo(id = it, label = it, path = it, sessionCount = 0, lanes = emptyList())
            },
            previewSessions = emptyList(),
        )

    private fun session(cwd: String?, repo: String? = null) = Session(
        id = "s", title = "t", model = null, provider = null, messageCount = 1,
        profile = "default", source = "tui", cwd = cwd, gitRepoRoot = repo,
    )

    @Test fun only_the_default_identity_reads_the_gateways_list() {
        assertTrue(catalog(active = "default", default = "default").isManaged())
        assertFalse(catalog(active = "researcher", default = "default").isManaged())
        // No profile marked default: fall back to the name whose home is the Hermes root.
        assertTrue(catalog(active = "default", default = null).isManaged())
        assertFalse(catalog(active = "writer", default = null).isManaged())
    }

    /**
     * The regression this class exists for: the Projects page and both「移动到项目」pickers used to
     * build their lists separately, so once the page read the gateway they disagreed.
     */
    @Test fun refresh_publishes_the_gateway_list_to_every_reader() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(project("p_1", "小麦日报", "/Users/bs/Documents/xiaomai-daily-report")),
            activeId = null,
        )
        assertEquals(emptyList<Project>(), c.projects.value)
        c.refresh()
        assertEquals(listOf("p_1"), c.projects.value.map { it.id })
        assertEquals("小麦日报", c.projects.value.single().label)
    }

    /** Upstream's no-project bucket keeps this app's id, and stays pinned first. */
    @Test fun the_no_project_bucket_is_renamed_and_pinned_first() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(
                project("p_1", "小麦日报", "/Users/bs/Documents/xiaomai-daily-report"),
                project("__no_project__", "Home", "/Users/bs").copy(isNoProject = true),
            ),
            activeId = null,
        )
        assertEquals(listOf(DEFAULT_PROJECT_ID, "p_1"), c.refresh().map { it.id })
    }

    /** A chat's row must show the project's NAME, not the folder it happens to sit in. */
    @Test fun a_chat_is_named_by_the_project_that_owns_its_folder() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(project("p_1", "小麦日报", "/Users/bs/Documents/xiaomai-daily-report")),
            activeId = null,
        )
        c.refresh()
        assertEquals("小麦日报", c.nameFor(session("/Users/bs/Documents/xiaomai-daily-report"), null))
        // Nested chats belong to the project too.
        assertEquals("小麦日报", c.nameFor(session("/Users/bs/Documents/xiaomai-daily-report/src"), null))
        // The git root wins over cwd, matching how the gateway groups.
        assertEquals(
            "小麦日报",
            c.nameFor(session("/tmp/wherever", repo = "/Users/bs/Documents/xiaomai-daily-report"), null),
        )
    }

    /** Upstream projects span folders, so membership cannot be decided by the primary path alone. */
    @Test fun a_secondary_folder_still_names_the_project() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(
                project("p_1", "HermesGO", "/Users/bs/Projects/hermes-remote",
                        repos = listOf("/Users/bs/Projects/hermes-remote", "/Users/bs/Projects/hermes-ops")),
            ),
            activeId = null,
        )
        c.refresh()
        assertEquals("HermesGO", c.nameFor(session("/Users/bs/Projects/hermes-ops"), null))
    }

    /** The deepest project wins, so a project inside another does not get swallowed. */
    @Test fun the_most_specific_project_wins() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(
                project("p_outer", "Everything", "/Users/bs/Projects"),
                project("p_inner", "HermesGO", "/Users/bs/Projects/hermes-remote"),
            ),
            activeId = null,
        )
        c.refresh()
        assertEquals("HermesGO", c.nameFor(session("/Users/bs/Projects/hermes-remote/android"), null))
        assertEquals("Everything", c.nameFor(session("/Users/bs/Projects/something-else"), null))
    }

    /** A sibling whose name merely starts with the same characters is NOT inside the project. */
    @Test fun a_prefix_that_is_not_a_path_boundary_does_not_match() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(
            projects = listOf(project("p_1", "HermesGO", "/Users/bs/Projects/hermes")),
            activeId = null,
        )
        c.refresh()
        // /hermes-remote is a sibling of /hermes, not a child.
        assertEquals("hermes-remote", c.nameFor(session("/Users/bs/Projects/hermes-remote"), null))
    }

    /** Before the first fetch a row keeps its old subtitle rather than losing one. */
    @Test fun a_cold_cache_falls_back_to_the_folder_basename() {
        val c = catalog()
        assertEquals("xiaomai-daily-report", c.nameFor(session("/Users/bs/Documents/xiaomai-daily-report"), null))
        assertNull(c.nameFor(session(null), null))
    }

    /** Absence means the default project everywhere in the UI. */
    @Test fun the_default_project_folder_is_named_by_absence() = runTest {
        val c = catalog()
        coEvery { projectsRepo.tree(any()) } returns ProjectTree(emptyList(), null)
        c.refresh()
        assertNull(c.nameFor(session("/Users/bs/hermes"), "/Users/bs/hermes"))
    }
}
