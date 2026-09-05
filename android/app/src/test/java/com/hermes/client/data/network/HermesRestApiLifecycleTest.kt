package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
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
}
