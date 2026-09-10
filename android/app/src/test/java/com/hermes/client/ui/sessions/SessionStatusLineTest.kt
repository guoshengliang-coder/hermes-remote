package com.hermes.client.ui.sessions

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.localization.AppLanguage
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
}
