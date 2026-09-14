package com.hermes.client.ui.sessions

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.isActive
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session row's status line must be either real text or absent — never a blank string.
 *
 * `Text("")` still occupies a line box, so a blank label pushes the row from Material's two-line
 * height (72dp) to its three-line height (88dp). Three-line list items are top-aligned rather than
 * centred, so the row then shows two lines of content with 40dp of dead space beneath them. On a
 * device that reads as an unexplained gap appearing every few rows, which is exactly how this was
 * reported: rows that carried no visible status at all were the tall ones.
 *
 * The old guard asked the PHASE whether to draw the line; this asks the TEXT.
 */
class SessionStatusLineTest {

    private fun runtime(phase: SessionRunPhase) = SessionRuntime(
        key = SessionRuntimeKey(profile = "personal", sessionId = "s1"),
        phase = phase,
    )

    @Test fun idle_with_no_work_has_no_status_line() {
        assertNull(sessionStatusLine(runtime(SessionRunPhase.IDLE), AppLanguage.ZH))
        assertNull(sessionStatusLine(runtime(SessionRunPhase.IDLE), AppLanguage.EN))
    }

    @Test fun absent_runtime_has_no_status_line() {
        assertNull(sessionStatusLine(null, AppLanguage.ZH))
    }

    /** The regression: no phase may produce a blank line rather than no line. */
    @Test fun no_phase_ever_yields_a_blank_line() {
        for (phase in SessionRunPhase.entries) {
            for (language in AppLanguage.entries) {
                val line = sessionStatusLine(runtime(phase), language)
                assertTrue(
                    "phase=$phase language=$language produced a blank status line",
                    line == null || line.isNotBlank(),
                )
            }
        }
    }

    /** Everything that is not idle does have something to say. */
    @Test fun every_non_idle_phase_says_something() {
        for (phase in SessionRunPhase.entries.filter { it != SessionRunPhase.IDLE }) {
            assertNotNull("phase=$phase produced no status line", sessionStatusLine(runtime(phase), AppLanguage.ZH))
        }
    }

    // ── HG-49: a message that was submitted and refused speaks on the row too. Before this, the
    // chat screen showed a red 未发送 bubble and the list row said nothing at all — the failure
    // path parks the runtime at IDLE (`finishLocal`), and IDLE is exactly the phase this file's
    // first test pins as silent.

    @Test fun an_unsent_message_speaks_where_an_idle_row_says_nothing() {
        assertNull(sessionStatusLine(runtime(SessionRunPhase.IDLE), AppLanguage.ZH))
        assertEquals("未发送", sessionStatusLine(runtime(SessionRunPhase.IDLE), AppLanguage.ZH, hasUnsent = true))
        assertEquals("Not sent", sessionStatusLine(runtime(SessionRunPhase.IDLE), AppLanguage.EN, hasUnsent = true))
    }

    /**
     * The runtime is evictable (`pruneIdleRuntimes` keeps 20 idle ones) and does not survive a
     * restart; the record on disk does. A row whose conversation has been pruned out of the store
     * must still say 未发送.
     */
    @Test fun an_unsent_message_speaks_with_no_runtime_at_all() {
        assertEquals("未发送", sessionStatusLine(null, AppLanguage.ZH, hasUnsent = true))
    }

    /** Every SETTLED phase loses to it: none of them is waiting on the user, and this one is. */
    @Test fun an_unsent_message_outranks_every_settled_phase() {
        for (phase in SessionRunPhase.entries.filterNot { it.isActive }) {
            assertEquals(
                "$phase must yield to 未发送",
                "未发送",
                sessionStatusLine(runtime(phase), AppLanguage.ZH, hasUnsent = true),
            )
            assertTrue(sessionStatusIsUnsent(runtime(phase), hasUnsent = true))
        }
    }

    /**
     * A run in flight wins: what is happening now is the more useful sentence, and unlike a run the
     * unsent message will still be there when it ends. Only one line fits.
     */
    @Test fun a_run_in_flight_outranks_an_unsent_message() {
        for (phase in SessionRunPhase.entries.filter { it.isActive }) {
            val line = sessionStatusLine(runtime(phase), AppLanguage.ZH, hasUnsent = true)
            assertNotNull("$phase must still report itself", line)
            assertNotEquals("$phase must outrank 未发送", "未发送", line)
            assertFalse(sessionStatusIsUnsent(runtime(phase), hasUnsent = true))
        }
    }

    /** The blank-line rule from this file's header applies to the new branch as well. */
    @Test fun an_unsent_line_is_never_blank_in_either_language() {
        for (phase in SessionRunPhase.entries) {
            for (language in AppLanguage.entries) {
                sessionStatusLine(runtime(phase), language, hasUnsent = true)?.let {
                    assertTrue("$phase/$language produced a blank line", it.isNotBlank())
                }
            }
        }
    }
}
