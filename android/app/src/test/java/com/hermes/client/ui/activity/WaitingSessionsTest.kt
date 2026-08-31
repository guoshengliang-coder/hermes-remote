package com.hermes.client.ui.activity

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingSessionsTest {
    private fun runtime(id: String, phase: SessionRunPhase, profile: String? = null, at: Long = 0L) =
        SessionRuntime(key = SessionRuntimeKey(profile, id), phase = phase, lastEventAt = at)

    @Test fun onlyWaitingPhasesSurface() {
        val runtimes = listOf(
            runtime("a", SessionRunPhase.STREAMING),
            runtime("b", SessionRunPhase.WAITING_APPROVAL, at = 10),
            runtime("c", SessionRunPhase.WAITING_CLARIFICATION, at = 20),
            runtime("d", SessionRunPhase.IDLE),
        ).associateBy { it.key }
        val alerts = waitingSessionAlerts(runtimes, profile = null) { "T-$it" }
        assertEquals(listOf("c", "b"), alerts.map { it.sessionId }) // newest first
        assertEquals(SessionAlertReason.WAITING_CLARIFICATION, alerts[0].reason)
        assertEquals(SessionAlertReason.WAITING_APPROVAL, alerts[1].reason)
        assertEquals("T-b", alerts[1].title)
        assertEquals("chat/b", alerts[1].route)
    }

    @Test fun profileScopingKeepsOwnAndUnscoped() {
        val runtimes = listOf(
            runtime("mine", SessionRunPhase.WAITING_APPROVAL, profile = "work"),
            runtime("other", SessionRunPhase.WAITING_APPROVAL, profile = "home"),
            runtime("unscoped", SessionRunPhase.WAITING_APPROVAL, profile = null),
        ).associateBy { it.key }
        val alerts = waitingSessionAlerts(runtimes, profile = "work") { null }
        assertEquals(setOf("mine", "unscoped"), alerts.map { it.sessionId }.toSet())
        assertTrue(alerts.all { it.title.isEmpty() })
    }
}
