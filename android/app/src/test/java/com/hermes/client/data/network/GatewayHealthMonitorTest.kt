package com.hermes.client.data.network

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayHealthMonitorTest {
    private val api = mockk<HermesRestApi>()
    private class FakeConnectivity(var online: Boolean = true) : ConnectivityChecker {
        override fun isOnline() = online
    }

    private fun ok() = GatewayStatusDto(version = "1.2.3", gatewayRunning = true, gatewayState = "running")

    /**
     * The "only transitions" guard compared the health values themselves, and Healthy carries
     * latencyMs, which differs on every probe — so it never suppressed a single line. 18 of the
     * 500 buffered entries in the HG-27 report were "healthy(201ms) → healthy(236ms)": a state
     * change that was not one, in a log whose real limit is bytes.
     */
    @Test fun an_unchanged_health_tier_is_not_re_reported_when_only_the_latency_moved() = runTest {
        com.hermes.client.data.diagnostics.DebugLog.detachStore()
        com.hermes.client.data.diagnostics.DebugLog.setEnabled(true)
        com.hermes.client.data.diagnostics.DebugLog.clear()
        try {
            coEvery { api.gatewayStatus() } returns ok()
            val m = GatewayHealthMonitor(
                api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope,
            )
            m.probe()
            m.probe()
            m.probe()
            val health = com.hermes.client.data.diagnostics.DebugLog.entries.value
                .filter { it.category == "health" }
            assertEquals("only the first probe changed the tier, got $health", 1, health.size)
        } finally {
            com.hermes.client.data.diagnostics.DebugLog.setEnabled(false)
            com.hermes.client.data.diagnostics.DebugLog.clear()
        }
    }

    @Test fun probe_reports_healthy_on_2xx() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.Healthy)
        assertEquals("1.2.3", (h as GatewayHealth.Healthy).version)
        assertTrue(h.running)
    }

    /**
     * Replaces `probe_reports_device_offline_without_calling_api`, which pinned the short-circuit
     * this fixes: the old code trusted one capability read and never asked the network. It still
     * reports DeviceOffline — the connectivity read chooses the wording — but only once the probe
     * has agreed.
     */
    @Test fun device_offline_is_reported_only_after_the_probe_also_fails() = runTest {
        coEvery { api.gatewayStatus() } throws java.io.IOException("no route to host")
        val conn = FakeConnectivity(online = false)
        val m = GatewayHealthMonitor(api, conn, MutableStateFlow(ConnectionState.Connected), backgroundScope)

        m.probe()

        assertEquals(GatewayHealth.DeviceOffline, m.health.value)
        io.mockk.coVerify(atLeast = 1) { api.gatewayStatus() }
    }

    /**
     * Regression for HG-10. NET_CAPABILITY_VALIDATED reports whether Android's captive-portal
     * probe reached its endpoint, not whether the network carries traffic, and it goes missing on
     * working connections — a VPN in the path, a dual-SIM handover, an unreachable validation
     * endpoint. Reporting "your device has no network" while the gateway answers normally is the
     * user-visible bug.
     */
    @Test fun a_reachable_gateway_outranks_a_connectivity_check_that_says_offline() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        val conn = FakeConnectivity(online = false)
        val m = GatewayHealthMonitor(api, conn, MutableStateFlow(ConnectionState.Connected), backgroundScope)

        m.probe()

        assertTrue(m.health.value.toString(), m.health.value is GatewayHealth.Healthy)
    }

    @Test fun an_unreachable_gateway_on_a_healthy_network_is_not_blamed_on_the_device() = runTest {
        coEvery { api.gatewayStatus() } throws java.io.IOException("connection refused")
        val conn = FakeConnectivity(online = true)
        val m = GatewayHealthMonitor(api, conn, MutableStateFlow(ConnectionState.Connected), backgroundScope)

        m.probe()

        assertTrue(m.health.value.toString(), m.health.value is GatewayHealth.GatewayUnreachable)
    }

    @Test fun probe_reports_gateway_unreachable_when_both_attempts_fail() = runTest {
        coEvery { api.gatewayStatus() } throws RuntimeException("timeout")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
    }

    @Test fun transient_first_failure_then_success_stays_healthy() = runTest {
        (coEvery { api.gatewayStatus() } throws RuntimeException("blip")).andThen(ok())
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    @Test fun unauthorized_is_reported_without_retry() = runTest {
        coEvery { api.gatewayStatus() } throws HermesApiException(401, "unauthorized")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.GatewayUnreachable)
        assertEquals("unauthorized", (h as GatewayHealth.GatewayUnreachable).detail)
        io.mockk.coVerify(exactly = 1) { api.gatewayStatus() }
    }

    @Test fun no_gateway_configured_maps_to_unknown_not_unreachable() = runTest {
        coEvery { api.gatewayStatus() } throws HermesApiException(0, "no gateway configured")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertEquals(GatewayHealth.Unknown, m.health.value)
        io.mockk.coVerify(exactly = 1) { api.gatewayStatus() }
    }

    @Test fun genuine_cancellation_propagates_and_does_not_mark_unreachable() = runTest {
        coEvery { api.gatewayStatus() } throws kotlinx.coroutines.CancellationException("cancelled")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        var threw = false
        try {
            m.probe()
        } catch (e: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(m.health.value is GatewayHealth.Unknown) // never set to a down state
    }

    @Test fun recovery_from_unreachable_to_healthy() = runTest {
        (coEvery { api.gatewayStatus() } throws RuntimeException("down")).andThenThrows(RuntimeException("down")).andThen(ok())
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe() // both attempts fail -> unreachable
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
        m.probe() // next probe succeeds
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    // Uses UnconfinedTestDispatcher: with this project's kotlinx-coroutines-test 1.11.0 /
    // Kotlin 2.3.10 pairing, StandardTestDispatcher (the runTest default) never dispatches a
    // backgroundScope.launch child via advanceUntilIdle() — reproduced with a minimal
    // backgroundScope.launch { flow.collect {} } case outside this class. Unconfined avoids it;
    // the assertions below are unchanged from the brief.
    /**
     * Regression for HG-42. The probe that ran while the network was stalling wrote
     * GatewayUnreachable; a second later the socket reconnected and every REST call went back to
     * 200 — and the red 「Relay 暂时无法连接」 strip stayed up anyway, because the collector only
     * listened for the socket going away, never for it coming back, and the periodic probe is 30
     * seconds apart. The strip has to describe the backend, not the last bad moment.
     */
    @Test fun ws_reconnect_clears_a_stale_unreachable() = runTest(UnconfinedTestDispatcher()) {
        // Not a fixed answer sequence: constructing the monitor with a Disconnected socket makes
        // its own collector fire a probe straight away, which would eat the failures meant for the
        // precondition. A flag says "the network is stalling" for as long as the test needs.
        var stalling = true
        coEvery { api.gatewayStatus() } answers {
            if (stalling) throw java.io.IOException("stalling") else ok()
        }
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), conn, backgroundScope)
        m.probe()
        advanceUntilIdle()
        assertTrue(
            "precondition: the strip is up, got ${m.health.value}",
            m.health.value is GatewayHealth.GatewayUnreachable,
        )

        stalling = false
        conn.value = ConnectionState.Connected
        advanceUntilIdle()

        assertTrue(
            "a recovered socket must re-probe and clear the strip, got ${m.health.value}",
            m.health.value is GatewayHealth.Healthy,
        )
    }

    /**
     * The other half of that rule: a reconnect on an already-healthy client must not spend a
     * request confirming what it already knows. The periodic probe owns the steady state.
     */
    @Test fun ws_reconnect_on_a_healthy_client_does_not_probe_again() = runTest(UnconfinedTestDispatcher()) {
        coEvery { api.gatewayStatus() } returns ok()
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), conn, backgroundScope)
        m.probe()
        advanceUntilIdle()
        io.mockk.clearMocks(api, answers = false)

        conn.value = ConnectionState.Connected
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { api.gatewayStatus() }
    }

    @Test fun ws_disconnect_triggers_a_probe() = runTest(UnconfinedTestDispatcher()) {
        coEvery { api.gatewayStatus() } returns ok()
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), conn, backgroundScope)
        advanceUntilIdle()
        conn.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertTrue(m.health.value is GatewayHealth.Healthy)
        io.mockk.coVerify(atLeast = 1) { api.gatewayStatus() }
    }
}
