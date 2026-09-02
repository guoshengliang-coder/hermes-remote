package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDerivationTest {
    private fun s(
        id: String,
        profile: String,
        repo: String?,
        lastActive: Long?,
        cwd: String? = null,
    ) = Session(
        id = id, title = id, model = null, provider = null, messageCount = 1,
        profile = profile, lastActive = lastActive, gitRepoRoot = repo, cwd = cwd,
    )

    @Test fun default_project_is_always_first_even_when_empty() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("a", "personal", "/u/andrew/personal/travel-business", 100),
                s("b", "personal", "/u/andrew/personal/travel-business", 200),
                s("c", "dito", "/u/andrew/work/clients/dito/Southington", 150),
            ),
        )
        // Default project (empty) + two repo-projects, travel-business first (more recent).
        assertEquals(listOf(DEFAULT_PROJECT_ID, "/u/andrew/personal/travel-business", "/u/andrew/work/clients/dito/Southington"), out.map { it.id })
        assertEquals(0, out.first().sessionCount)
        assertNull(out.first().path) // launch dir unknown until a top-level create teaches it
        val travel = out.single { it.id == "/u/andrew/personal/travel-business" }
        assertEquals("travel-business", travel.label)
        assertEquals(2, travel.sessionCount)
        assertEquals(2, travel.repos.single().lanes.single().sessions.size)
    }

    @Test fun spans_profiles_within_one_repo() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("p", "personal", "/u/shared", 10),
                s("o", "odos", "/u/shared", 20),
            ),
        )
        val proj = out.single { it.id != DEFAULT_PROJECT_ID }
        assertEquals(2, proj.sessionCount)
        // Sessions retain their own profile so the UI can open against the right DB.
        assertEquals(setOf("personal", "odos"), proj.repos.single().lanes.single().sessions.map { it.profile }.toSet())
    }

    @Test fun sessions_without_a_folder_belong_to_the_default_project() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("x", "personal", "/u/andrew/personal/inbound", 300),
                s("loose1", "personal", null, 50),
                s("loose2", "odos", "", 60),
            ),
        )
        assertEquals(2, out.size)
        val default = out.first()
        assertEquals(DEFAULT_PROJECT_ID, default.id)
        assertEquals(2, default.sessionCount) // null AND blank repo both default
        assertEquals(listOf("loose2", "loose1"), default.previewSessions.map { it.id }) // newest first
        assertEquals(60L, default.lastActive)
    }

    @Test fun sessions_in_the_known_launch_directory_fold_into_the_default_project() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("home", "personal", null, 10, cwd = "/Users/me/"),
                s("homeRepo", "personal", "/Users/me", 20, cwd = "/Users/me/sub"),
                s("real", "personal", "/Users/me/proj", 30),
            ),
            defaultProjectPath = "/Users/me",
        )
        assertEquals(listOf(DEFAULT_PROJECT_ID, "/Users/me/proj"), out.map { it.id })
        assertEquals(2, out.first().sessionCount)
        assertEquals("/Users/me", out.first().path) // picker can move sessions back here
    }

    @Test fun orders_projects_newest_first_and_previews_recent() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("old", "personal", "/u/a", 100),
                s("new", "personal", "/u/b", 999),
                s("b2", "personal", "/u/b", 500),
            ),
        )
        assertEquals(listOf(DEFAULT_PROJECT_ID, "/u/b", "/u/a"), out.map { it.id }) // /u/b more recent → first
        assertEquals(listOf("new", "b2"), out[1].previewSessions.map { it.id }) // recent-first previews
    }

    @Test fun falls_back_to_cwd_when_no_git_repo_root() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("a", "personal", null, 1, cwd = "/u/andrew/personal/travel-business"),
                // git root wins as the key when both are present.
                s("b", "personal", "/u/andrew/personal/flights", 2, cwd = "/u/andrew/personal/flights/sub"),
            ),
        )
        assertEquals(
            setOf(DEFAULT_PROJECT_ID, "/u/andrew/personal/travel-business", "/u/andrew/personal/flights"),
            out.map { it.id }.toSet(),
        )
        assertEquals("travel-business", out.single { it.id == "/u/andrew/personal/travel-business" }.label)
    }

    @Test fun empty_input_yields_only_the_empty_default_project() {
        val out = deriveProjectsFromSessions(emptyList())
        assertEquals(listOf(DEFAULT_PROJECT_ID), out.map { it.id })
        assertTrue(out.single().sessionCount == 0)
    }

    @Test fun project_label_is_the_folder_basename_or_null_for_default() {
        assertEquals("flights", projectLabelOf(s("b", "p", "/u/andrew/personal/flights", 1, cwd = "/u/andrew/personal/flights/sub")))
        assertEquals("travel-business", projectLabelOf(s("a", "p", null, 1, cwd = "/u/andrew/personal/travel-business/")))
        assertNull(projectLabelOf(s("loose", "p", null, 1)))
        assertNull(projectLabelOf(s("home", "p", null, 1, cwd = "/Users/me"), defaultProjectPath = "/Users/me/"))
        assertEquals("me", projectLabelOf(s("home", "p", null, 1, cwd = "/Users/me"), defaultProjectPath = null))
    }

    @Test fun projectOf_resolves_a_session_to_its_derived_project_or_default() {
        val sessions = listOf(
            s("a", "p", "/u/proj", 1),
            s("loose", "p", null, 2),
        )
        val projects = deriveProjectsFromSessions(sessions)
        assertEquals("/u/proj", projectOf(sessions[0], projects)?.id)
        assertEquals(DEFAULT_PROJECT_ID, projectOf(sessions[1], projects)?.id)
    }
}
