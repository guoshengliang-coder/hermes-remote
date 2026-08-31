package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiLifecycleTest {
    @get:Rule val serverRule = MockWebServerRule()

    private fun api(server: MockWebServer) = HermesRestApi(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    ) { GatewayConfig(server.url("/").toString().trimEnd('/'), "app-token") }

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
