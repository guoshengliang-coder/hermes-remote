package com.hermes.client.data.diagnostics

import com.hermes.client.data.network.NetworkTransports
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * HG-27 and HG-42 were both noticed by a user long before anyone thought to turn diagnostics on,
 * so the app's own detection of the fault went nowhere. This record is always on for exactly the
 * events the client acted on.
 */
class ConnectionIncidentsTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setUp() {
        ConnectionIncidents.clear()
        // Deterministic transport note: tests that do not care still get a stable "unknown".
        NetworkTransports.install { "unknown" }
    }

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

    @Test fun timeout_evidence_survives_a_process_restart_without_verbose_logging() {
        val dir = temp.newFolder("incidents")
        val now = System.currentTimeMillis()
        ConnectionIncidents.init(dir, now)
        ConnectionIncidents.record("rpc-timeout", "rpcId=14 method=session.create conn=abc token=SECRET", now)

        ConnectionIncidents.init(dir, now + 1_000)

        val restored = ConnectionIncidents.snapshot().single()
        assertEquals("rpc-timeout", restored.kind)
        assertTrue(restored.detail.contains("rpcId=14"))
        assertFalse(restored.detail.contains("SECRET"))
        assertEquals("1", ConnectionIncidents.feedbackContext()["selfHealCount"])
    }

    @Test fun expired_incidents_are_discarded_on_launch() {
        val dir = temp.newFolder("expired")
        val now = System.currentTimeMillis()
        ConnectionIncidents.init(dir, now)
        ConnectionIncidents.record("rpc-timeout", "old", now)

        ConnectionIncidents.init(dir, now + 8L * 24 * 60 * 60 * 1_000)

        assertTrue(ConnectionIncidents.snapshot().isEmpty())
        assertTrue(ConnectionIncidents.feedbackContext().isEmpty())
    }

    @Test fun clearing_diagnostics_removes_incidents_but_future_failures_still_persist() {
        val dir = temp.newFolder("cleared")
        val now = System.currentTimeMillis()
        ConnectionIncidents.init(dir, now)
        ConnectionIncidents.record("rpc-timeout", "before", now)
        DebugLog.clear()
        assertTrue(ConnectionIncidents.snapshot().isEmpty())

        ConnectionIncidents.record("rpc-timeout", "after", now + 1_000)
        ConnectionIncidents.init(dir, now + 2_000)
        // Since HG-140 every record carries the network transports it failed on.
        assertEquals("after · net=unknown", ConnectionIncidents.snapshot().single().detail)
    }

    /**
     * HG-140: the 2026-09-26 incident could not verify that a "DIRECT" VPN rule meant the failing
     * traffic really went direct. The record now always says which transports were active.
     */
    @Test fun every_record_carries_the_active_network_transports() {
        NetworkTransports.install { "cellular+vpn" }

        ConnectionIncidents.record("ws-close", "gen=3 code=4403 ready=true")

        val stored = ConnectionIncidents.snapshot().single().detail
        assertTrue(stored, stored.endsWith("· net=cellular+vpn"))
    }
}
