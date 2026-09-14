package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── The Bots list's reading of the same session (HG-54) ──────────────────────────────────

    /**
     * A bot conversation with a known model reads exactly like an ordinary one, minus the lead:
     * the cwd of a conversation that happened on DingTalk belongs to Hermes, not to anything the
     * reader chose, so showing it would be noise.
     */
    @Test fun a_bot_row_shows_the_model_and_no_project() {
        val parts = sessionSublineParts(s("claude-sonnet-5", repo = "/u/proj"), isBot = true)
        assertNull(parts.lead)
        assertEquals("claude-sonnet-5", parts.model)
        assertFalse(parts.modelUnknown)
    }

    /**
     * The third state. A blank model on an ordinary session means "the profile default applies";
     * on a bot session it means we do not know what answered on the other side. Dropping the
     * segment silently (what the shared rule does) would hide the difference, and saying 默认模型
     * would be a claim about someone else's turn (docs/DESIGN.md §5.16).
     */
    @Test fun a_bot_row_with_no_model_is_unknown_not_absent_and_not_default() {
        for (model in listOf(null, "", "   ")) {
            val parts = sessionSublineParts(s(model), isBot = true)
            assertNull(parts.model)
            assertTrue(parts.modelUnknown)
            // Unknown is something to render, so the subline is NOT empty.
            assertFalse(parts.isEmpty)
        }
    }

    /** The flag is the bot list's alone — an ordinary blank-model row keeps dropping the segment. */
    @Test fun an_ordinary_row_never_reports_an_unknown_model() {
        assertFalse(sessionSublineParts(s(null)).modelUnknown)
        assertFalse(sessionSublineParts(s("  ", cwd = "/u/proj")).modelUnknown)
    }

    @Test fun profile_never_appears_in_the_subline() {
        // docs/DESIGN.md §1: identity is carried by the avatar only; list rows carry no profile text.
        val parts = sessionSublineParts(s("claude-opus-5", repo = "/u/proj"))
        assertTrue(listOfNotNull(parts.lead, parts.model).none { it.contains("work") || it.contains("身份") })
    }
}
