package com.hermes.client.ui.sessions

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Terminal verdicts keep their text but drop the dot (docs/DESIGN.md §5.2, decision 2026-09-02). */
class SessionRowTrailingTest {
    private fun runtime(phase: SessionRunPhase) = SessionRuntime(
        key = SessionRuntimeKey("personal", "s1"),
        phase = phase,
    )

    @Test fun active_work_always_shows_the_runtime_indicator() {
        assertEquals(SessionRowTrailing.RUNTIME, sessionRowTrailing(runtime(SessionRunPhase.STREAMING), unread = true))
        assertEquals(SessionRowTrailing.RUNTIME, sessionRowTrailing(runtime(SessionRunPhase.WAITING_APPROVAL), unread = false))
    }

    @Test fun completed_keeps_its_green_dot() {
        assertEquals(SessionRowTrailing.RUNTIME, sessionRowTrailing(runtime(SessionRunPhase.COMPLETED_UNREAD), unread = false))
    }

    @Test fun interrupted_and_failed_show_text_only() {
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(runtime(SessionRunPhase.INTERRUPTED), unread = false))
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(runtime(SessionRunPhase.FAILED), unread = false))
    }

    @Test fun unread_beats_a_terminal_verdict_and_idle() {
        assertEquals(SessionRowTrailing.UNREAD, sessionRowTrailing(runtime(SessionRunPhase.FAILED), unread = true))
        assertEquals(SessionRowTrailing.UNREAD, sessionRowTrailing(null, unread = true))
    }

    @Test fun idle_without_unread_shows_nothing() {
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(runtime(SessionRunPhase.IDLE), unread = false))
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(null, unread = false))
    }
}
