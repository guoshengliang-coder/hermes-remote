package com.hermes.client.data.network

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private fun breakingReport(version: String = "1.2.3") = HermesContractReportDto(
        schema = 1,
        status = "breaking",
        code = "HR-COMPAT-001",
        hermesVersion = version,
        missing = listOf(HermesContractMissingDto("GET", "/api/sessions/{id}/messages", "required", "history")),
    )

    private val compatible = HermesContractReportDto(schema = 1, status = "compatible")

    private fun kotlinx.coroutines.test.TestScope.monitorAt(clock: () -> Long) = GatewayHealthMonitor(
        api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope, clock = clock,
    )

    @Test fun a_healthy_probe_reads_the_connectors_contract_report() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { 0L }

        m.probe()

        val notice = m.contract.value
        assertEquals(com.hermes.client.data.error.AppErrorCode.HERMES_INCOMPATIBLE, notice?.error?.code)
        assertEquals(listOf("history"), notice?.features)
    }

    @Test fun the_report_is_re_read_when_the_version_moves_or_it_goes_stale_not_on_every_probe() = runTest {
        var now = 0L
        var version = "1.2.3"
        coEvery { api.gatewayStatus() } answers { GatewayStatusDto(version = version, gatewayRunning = true) }
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } returns compatible
        val m = monitorAt { now }

        m.probe(); m.probe(); m.probe()
        io.mockk.coVerify(exactly = 1) { api.hermesContract(any()) }

        version = "1.3.0"
        m.probe()
        io.mockk.coVerify(exactly = 2) { api.hermesContract(any()) }

        now += GatewayHealthMonitor.CONTRACT_REFRESH_MS
        m.probe()
        io.mockk.coVerify(exactly = 3) { api.hermesContract(any()) }
        assertEquals(null, m.contract.value)
    }

    /**
     * Review D1. The verdict belongs to one Mac. Switching to another Mac with the same Hermes
     * version used to keep the first Mac's red strip for up to five minutes, because the cache was
     * keyed on the version alone.
     */
    @Test fun switching_macs_clears_the_verdict_and_asks_the_new_mac_at_once() = runTest {
        var target: String? = "account:https://relay#acct#mac-a"
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } answers { target }
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { 0L }
        m.probe()
        assertTrue(m.contract.value != null)

        target = "account:https://relay#acct#mac-b"
        coEvery { api.hermesContract(any()) } returns compatible
        m.probe()

        assertEquals(null, m.contract.value)
        io.mockk.coVerify(exactly = 2) { api.hermesContract(any()) }
    }

    @Test fun signing_out_clears_the_verdict_even_while_the_relay_is_down() = runTest {
        var target: String? = "legacy:a"
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } answers { target }
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { 0L }
        m.probe()
        assertTrue(m.contract.value != null)

        target = null
        coEvery { api.gatewayStatus() } throws HermesApiException(0, "no gateway configured")
        m.probe()

        assertEquals(null, m.contract.value)
    }

    @Test fun a_report_that_arrives_after_a_switch_is_dropped() = runTest {
        var target: String? = "legacy:a"
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } answers { target }
        coEvery { api.hermesContract(any()) } coAnswers {
            target = "legacy:b" // the user switched while the request was in flight
            breakingReport()
        }
        val m = monitorAt { 0L }

        m.probe()

        assertEquals(null, m.contract.value)
    }

    /** Review D2. 「重新检查」 right after `hermes update` (same version) must ask again. */
    @Test fun recheck_re_reads_the_report_inside_the_cache_window() = runTest(UnconfinedTestDispatcher()) {
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { 0L }
        m.probe()
        assertTrue(m.contract.value != null)

        coEvery { api.hermesContract(any()) } returns compatible
        m.recheck()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 2) { api.hermesContract(any()) }
        assertEquals(null, m.contract.value)
    }

    /** An older Connector forwards the path to Hermes, which answers 401/404/405: no report. */
    @Test fun an_older_connector_without_the_route_clears_the_notice() = runTest {
        for (status in listOf(401, 404, 405)) {
            var now = 0L
            coEvery { api.gatewayStatus() } returns ok()
            coEvery { api.contractTargetKey() } returns "legacy:a"
            coEvery { api.hermesContract(any()) } returns breakingReport()
            val m = monitorAt { now }
            m.probe()
            assertTrue(m.contract.value != null)

            coEvery { api.hermesContract(any()) } throws HermesApiException(status, "no report")
            now += GatewayHealthMonitor.CONTRACT_REFRESH_MS
            m.probe()

            assertEquals("status $status", null, m.contract.value)
        }
    }

    /**
     * Review D3. A 503 `device_offline` or 504 from the Relay says nothing about Hermes. It used to
     * clear a real breaking verdict and then pin "no report" for five minutes.
     */
    @Test fun a_relay_5xx_keeps_the_last_verdict_and_asks_again_on_the_next_probe() = runTest {
        var now = 0L
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { now }
        m.probe()
        now += GatewayHealthMonitor.CONTRACT_REFRESH_MS

        for (status in listOf(503, 504)) {
            coEvery { api.hermesContract(any()) } throws HermesApiException(status, "device_offline")
            m.probe()
            assertEquals("status $status", com.hermes.client.data.error.AppErrorCode.HERMES_INCOMPATIBLE, m.contract.value?.error?.code)
        }

        // No time passes: the failure must not have been cached as an answer.
        coEvery { api.hermesContract(any()) } returns compatible
        m.probe()
        assertEquals(null, m.contract.value)
    }

    @Test fun a_transport_failure_keeps_the_last_report_and_asks_again_next_probe() = runTest {
        var now = 0L
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } returns breakingReport()
        val m = monitorAt { now }
        m.probe()
        now += GatewayHealthMonitor.CONTRACT_REFRESH_MS

        coEvery { api.hermesContract(any()) } throws java.io.IOException("reset")
        m.probe()
        assertTrue(m.contract.value != null)

        coEvery { api.hermesContract(any()) } returns compatible
        m.probe()
        assertEquals(null, m.contract.value)
    }

    /**
     * Review D4. The report used to be read inside the probe lock, and OkHttp's blocking call is
     * not interrupted by `withTimeout`, so a stalled report swallowed every probe a dropped or
     * restored socket asked for.
     */
    @Test fun a_stalled_report_does_not_block_the_next_probe() = runTest(UnconfinedTestDispatcher()) {
        val stalled = kotlinx.coroutines.CompletableDeferred<HermesContractReportDto>()
        coEvery { api.gatewayStatus() } returns ok()
        coEvery { api.contractTargetKey() } returns "legacy:a"
        coEvery { api.hermesContract(any()) } coAnswers { stalled.await() }
        val m = monitorAt { 0L }

        val first = backgroundScope.launch { m.probe() }
        m.probe()

        io.mockk.coVerify(exactly = 2) { api.gatewayStatus() }
        io.mockk.coVerify(exactly = 1) { api.hermesContract(any()) }
        stalled.complete(compatible)
        first.join()
    }

    @Test fun an_unhealthy_relay_is_not_asked_for_a_report() = runTest {
        coEvery { api.gatewayStatus() } throws java.io.IOException("connection refused")
        coEvery { api.contractTargetKey() } returns "legacy:a"
        val m = monitorAt { 0L }

        m.probe()

        io.mockk.coVerify(exactly = 0) { api.hermesContract(any()) }
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

    /** HG-90: WS can recover before the Connector REST queue does; the later 2xx is the next hint. */
    @Test fun routed_rest_success_reprobes_a_stale_unreachable_even_when_ws_never_changes() =
        runTest(UnconfinedTestDispatcher()) {
            var reachable = false
            coEvery { api.gatewayStatus() } answers {
                if (reachable) ok() else throw java.io.IOException("control queue stalled")
            }
            val signal = RoutedRestRecoverySignal()
            val m = GatewayHealthMonitor(
                api,
                FakeConnectivity(true),
                MutableStateFlow(ConnectionState.Connected),
                backgroundScope,
                routedRestRecoverySignal = signal,
            )
            m.probe()
            assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)

            reachable = true
            signal.reportSuccess()
            advanceUntilIdle()

            assertTrue(m.health.value.toString(), m.health.value is GatewayHealth.Healthy)
        }

    @Test fun routed_rest_success_does_not_probe_an_already_healthy_gateway() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.gatewayStatus() } returns ok()
            val signal = RoutedRestRecoverySignal()
            val m = GatewayHealthMonitor(
                api,
                FakeConnectivity(true),
                MutableStateFlow(ConnectionState.Connected),
                backgroundScope,
                routedRestRecoverySignal = signal,
            )
            m.probe()
            io.mockk.clearMocks(api, answers = false)

            signal.reportSuccess()
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { api.gatewayStatus() }
        }
}
