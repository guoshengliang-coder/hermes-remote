package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HG-27 and HG-42 were both noticed by a user long before anyone thought to turn diagnostics on,
 * so the app's own detection of the fault went nowhere. This record is always on for exactly the
 * events the client acted on.
 */
class ConnectionIncidentsTest {
    @Before fun setUp() = ConnectionIncidents.clear()

    @After fun tearDown() = ConnectionIncidents.clear()

    @Test fun a_healthy_device_contributes_no_context_at_all() {
        assertTrue(ConnectionIncidents.feedbackContext().isEmpty())
    }

    @Test fun a_repaired_stall_is_remembered_with_diagnostics_off() {
        DebugLog.setEnabled(false)

        ConnectionIncidents.record("stalled-Connecting", "state=Connecting gen=25 socket=none")

        val context = ConnectionIncidents.feedbackContext()
        assertEquals("1", context["selfHealCount"])
        assertTrue(context.getValue("selfHeal1").contains("stalled-Connecting"))
        assertTrue(context.getValue("selfHeal1").contains("socket=none"))
    }

    /**
     * The count is the interesting number — "has this been happening" — while the detail is bounded
     * so a device that has been stalling for a week cannot push the rest of the report out.
     */
    @Test fun the_count_keeps_growing_while_the_detail_stays_bounded() {
        repeat(12) { ConnectionIncidents.record("stalled-Connecting", "attempt $it") }

        val context = ConnectionIncidents.feedbackContext()
        assertEquals("12", context["selfHealCount"])
        assertEquals(ConnectionIncidents.CAPACITY, ConnectionIncidents.snapshot().size)
        // Oldest dropped, newest kept.
        assertTrue(context.getValue("selfHeal${ConnectionIncidents.CAPACITY}").contains("attempt 11"))
        assertFalse(context.values.any { it.contains("attempt 0") })
    }

    /**
     * A connection snapshot is not supposed to carry the WS ticket, but "not supposed to" is how
     * credentials leak. Redaction happens on the way in, so a future reader of the record cannot
     * reintroduce the leak by forgetting to redact on the way out.
     */
    @Test fun a_detail_that_carried_a_credential_is_redacted_before_it_is_stored() {
        ConnectionIncidents.record("stalled-Connecting", "url=wss://relay/api/ws?ticket=SUPERSECRET state=Connecting")

        val stored = ConnectionIncidents.snapshot().single().detail
        assertFalse(stored, stored.contains("SUPERSECRET"))
        assertTrue(stored, stored.contains("state=Connecting"))
    }
}
