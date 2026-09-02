package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSublinePartsTest {
    private fun s(model: String?, cwd: String? = null, repo: String? = null, branch: String? = null) = Session(
        id = "x", title = "x", model = model, provider = null, messageCount = 1,
        profile = "work", cwd = cwd, gitRepoRoot = repo, gitBranch = branch,
    )

    @Test fun project_lead_then_model() {
        val parts = sessionSublineParts(s("claude-opus-5", repo = "/Users/me/CodeX project/hermes-remote"))
        assertEquals("hermes-remote", parts.lead)
        assertEquals("claude-opus-5", parts.model)
    }

    @Test fun default_project_has_no_lead_segment() {
        val loose = sessionSublineParts(s("claude-opus-5"))
        assertNull(loose.lead)
        assertEquals("claude-opus-5", loose.model)
        val inLaunchDir = sessionSublineParts(s("claude-opus-5", cwd = "/Users/me"), defaultProjectPath = "/Users/me")
        assertNull(inLaunchDir.lead)
    }

    @Test fun blank_model_is_dropped_and_no_content_is_empty() {
        val onlyProject = sessionSublineParts(s("  ", cwd = "/u/proj"))
        assertEquals("proj", onlyProject.lead)
        assertNull(onlyProject.model)
        assertTrue(sessionSublineParts(s(null)).isEmpty)
    }

    @Test fun branch_mode_leads_with_the_git_branch_not_the_project() {
        val parts = sessionSublineParts(s("claude-sonnet-5", repo = "/u/proj", branch = "codex/router"), lead = SublineLead.BRANCH)
        assertEquals("codex/router", parts.lead)
        assertEquals("claude-sonnet-5", parts.model)
        assertNull(sessionSublineParts(s("m", repo = "/u/proj"), lead = SublineLead.BRANCH).lead)
    }

    @Test fun profile_never_appears_in_the_subline() {
        // docs/DESIGN.md §1: identity is carried by the avatar only; list rows carry no profile text.
        val parts = sessionSublineParts(s("claude-opus-5", repo = "/u/proj"))
        assertTrue(listOfNotNull(parts.lead, parts.model).none { it.contains("work") || it.contains("身份") })
    }
}
