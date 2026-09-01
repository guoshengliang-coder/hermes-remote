package com.hermes.client.ui.sessions

import com.hermes.client.data.progress.SessionRunPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the split that the blue brand made necessary: COMPLETED_UNREAD used to resolve to
 * MaterialTheme.colorScheme.primary, which under a blue brand is the same colour as the section
 * headers and FAB around it. It now draws from the status palette instead.
 */
class SessionStatusPaintTest {

    @Test fun completed_does_not_draw_from_the_brand() {
        assertEquals(SessionStatusPaint.COMPLETED, sessionStatusPaint(SessionRunPhase.COMPLETED_UNREAD))
    }

    @Test fun waiting_phases_share_one_paint() {
        for (phase in listOf(
            SessionRunPhase.WAITING_APPROVAL,
            SessionRunPhase.WAITING_CLARIFICATION,
            SessionRunPhase.WAITING_ATTENTION,
        )) {
            assertEquals(phase.name, SessionStatusPaint.WAITING, sessionStatusPaint(phase))
        }
    }

    @Test fun failed_is_its_own_paint() {
        assertEquals(SessionStatusPaint.FAILED, sessionStatusPaint(SessionRunPhase.FAILED))
    }

    // Everything else is deliberately neutral — no phase may quietly fall back onto the brand.
    @Test fun every_other_phase_is_neutral() {
        val claimed = setOf(
            SessionRunPhase.WAITING_APPROVAL,
            SessionRunPhase.WAITING_CLARIFICATION,
            SessionRunPhase.WAITING_ATTENTION,
            SessionRunPhase.FAILED,
            SessionRunPhase.COMPLETED_UNREAD,
        )
        for (phase in SessionRunPhase.entries.filterNot { it in claimed }) {
            assertEquals(phase.name, SessionStatusPaint.NEUTRAL, sessionStatusPaint(phase))
        }
    }
}
