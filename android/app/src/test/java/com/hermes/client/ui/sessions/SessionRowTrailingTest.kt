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

    /**
     * HG-49 gave the row a red 未发送 status LINE and deliberately no dot, for the same reason
     * 已中断 and 运行失败 have none: the terminal dot sits 1dp from the unread dot and reads as
     * unread (docs/DESIGN.md §5.2, decision 2026-09-02). The trailing slot takes no argument for it
     * at all — this test pins that the signature stayed that way on purpose.
     */
    @Test fun an_unsent_message_adds_no_dot() {
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(runtime(SessionRunPhase.IDLE), unread = false))
        assertEquals(SessionRowTrailing.NONE, sessionRowTrailing(null, unread = false))
        // …and it does not steal the unread dot either, when the row is also unread.
        assertEquals(SessionRowTrailing.UNREAD, sessionRowTrailing(runtime(SessionRunPhase.IDLE), unread = true))
    }
}
