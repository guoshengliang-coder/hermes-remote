package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountSessionStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.PendingEmailChallenge
import kotlinx.coroutines.test.runTest
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiLifecycleTest {
    @get:Rule val serverRule = MockWebServerRule()

    private fun api(server: MockWebServer) = HermesRestApi(
        testHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) { GatewayConfig(server.url("/").toString().trimEnd('/'), "app-token") }

    /**
     * The inbox poll runs every two seconds in the foreground. Logging a request and a response
     * line each time filled a 500-entry buffer in about eight minutes with "nothing happened",
     * which is why a shared log rarely still contained the incident. A quick, successful poll is
     * now silent; anything else still speaks.
     */
    @Test fun a_quick_successful_inbox_poll_writes_no_diagnostic_line() = runTest {
        DebugLog.setEnabled(true)
        DebugLog.clear()
        serverRule.server.enqueue(
            MockResponse.Builder().code(200).body("""{"events":[],"nextCursor":0,"hasMore":false}""").build(),
        )

        api(serverRule.server).lifecycleEvents(after = 0)

        assertTrue(
            "a quiet poll must not be logged, got ${DebugLog.entries.value}",
            DebugLog.entries.value.none { it.category == "rest" },
        )
    }

    /**
     * The session-list and platform refreshes were 70 of the 500 buffered entries in the HG-27
     * report, saying nothing a failure would not say louder. They now follow the rule the inbox
     * poll already followed (DESIGN.md §5.15).
     */
    @Test fun a_quick_successful_session_list_refresh_writes_no_diagnostic_line() = runTest {
        DebugLog.setEnabled(true)
        DebugLog.clear()
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[]}""").build())

        runCatching { api(serverRule.server).profileSessions() }

        assertTrue(
            "a quiet refresh must not be logged, got ${DebugLog.entries.value}",
            DebugLog.entries.value.none { it.category == "rest" },
        )
    }

    /**
     * `/api/status` deliberately stays loud. "REST kept answering 200 while the socket was
     * wedged" is the contrast that made HG-27 readable, and once the health monitor stops
     * re-reporting an unchanged tier it is the only line still carrying it.
     */
    @Test fun the_status_probe_is_never_quieted() = runTest {
        DebugLog.setEnabled(true)
        DebugLog.clear()
        serverRule.server.enqueue(
            MockResponse.Builder().code(200).body("""{"version":"1","gateway_running":true}""").build(),
        )

        runCatching { api(serverRule.server).gatewayStatus() }

        assertTrue(
            "the status probe must stay in the log, got ${DebugLog.entries.value}",
            DebugLog.entries.value.any { it.category == "rest" && it.message.contains("/api/status") },
        )
    }

    @Test fun a_failing_inbox_poll_is_still_logged() = runTest {
        DebugLog.setEnabled(true)
        DebugLog.clear()
        serverRule.server.enqueue(MockResponse.Builder().code(503).body("down").build())

        runCatching { api(serverRule.server).lifecycleEvents(after = 0) }

        val line = DebugLog.entries.value.single { it.category == "rest" }
        assertTrue(line.message, line.message.contains("503"))
    }

    @Test fun a_non_polling_request_is_logged_once_with_its_duration() = runTest {
        DebugLog.setEnabled(true)
        DebugLog.clear()
        serverRule.server.enqueue(
            MockResponse.Builder().code(200).body("""{"version":"1","gateway_running":true}""").build(),
        )

        runCatching { api(serverRule.server).gatewayStatus() }

        val lines = DebugLog.entries.value.filter { it.category == "rest" }
        assertEquals(1, lines.size)
        assertTrue(lines.single().message, lines.single().message.contains("ms)"))
    }

    @Test fun lifecycleEvents_parsesRelayEnvelopeAndUsesCursor() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"events":[{"sequence":8,"event":{"type":"session.lifecycle","version":1,"eventId":"e8","deviceId":"mac-mini","runtimeSessionId":"r1","storedSessionId":"s1","event":"run.completed","state":"idle","occurredAt":"2026-08-31T08:30:00.000Z"},"receivedAt":"2026-08-31T08:30:01.000Z"}],"nextCursor":8,"hasMore":false}""",
        ).build())

        val page = api(serverRule.server).lifecycleEvents(after = 7, limit = 20)

        assertEquals("e8", page.events.single().event.eventId)
        assertEquals(8L, page.nextCursor)
        val request = serverRule.server.takeRequest()
        assertEquals("/api/mobile/events?after=7&limit=20", request.target)
        assertEquals("app-token", request.headers["X-Hermes-Session-Token"])
    }

    @Test fun lifecycleDeliveryAck_postsBoundedJsonIds() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true,"changed":2}""").build())

        api(serverRule.server).markLifecycleEventsDelivered(listOf("e1", "e2", "e1"))

        val request = serverRule.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/events/ack", request.target)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"event_ids\":[\"e1\",\"e2\"]"))
    }

    @Test fun accountLifecycleInboxUsesTheInstallationBearerWithoutAMacRouteOrLegacyToken() = runTest {
        val server = serverRule.server
        val store = MemoryAccountStore(
            AccountSession(
                baseUrl = server.url("/").toString().trimEnd('/'),
                accountId = "account-1",
                installationId = "installation-1",
                installationDisplayName = "Pixel",
                accessToken = "hga_account",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr_account",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "office/mac 1",
            ),
        )
        val manager = AccountSessionManager(store, AccountApi(testHttpClient(), Json { ignoreUnknownKeys = true }))
        val api = HermesRestApi(
            testHttpClient(),
            Json { ignoreUnknownKeys = true },
            manager,
            testHttpClient(),
        ) { GatewayConfig(server.url("/").toString().trimEnd('/'), "legacy-secret") }
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"events":[],"nextCursor":7,"hasMore":false}""",
        ).build())

        api.lifecycleEvents(after = 7, limit = 20)

        val pageRequest = server.takeRequest()
        assertEquals("/api/mobile/events?after=7&limit=20", pageRequest.target)
        assertEquals("Bearer hga_account", pageRequest.headers["Authorization"])
        assertEquals(null, pageRequest.headers["X-Hermes-Session-Token"])

        manager.clearDeviceSelection()
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true,"changed":1}""").build())
        api.markLifecycleEventsDelivered(listOf("event-1"))

        val ackRequest = server.takeRequest()
        assertEquals("/api/mobile/events/ack", ackRequest.target)
        assertEquals("Bearer hga_account", ackRequest.headers["Authorization"])
        assertEquals(null, ackRequest.headers["X-Hermes-Session-Token"])
    }

    private class MemoryAccountStore(
        private var account: AccountSession?,
    ) : AccountSessionStore {
        override fun clientInstallationId() = "00000000-0000-0000-0000-000000000099"
        override fun loadAccountSession() = account
        override fun saveAccountSession(session: AccountSession) { account = session }
        override fun clearAccountSession() { account = null }
        override fun loadPendingEmailChallenge(): PendingEmailChallenge? = null
        override fun savePendingEmailChallenge(challenge: PendingEmailChallenge) = Unit
        override fun clearPendingEmailChallenge() = Unit
    }
}
