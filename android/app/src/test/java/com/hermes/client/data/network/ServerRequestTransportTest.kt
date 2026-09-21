package com.hermes.client.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The socket half of server→client requests, against a real WebSocket: the capability handshake,
 * a request arriving, the answer going back, and a method this client has no card for.
 *
 * Real clocks: OkHttp runs on its own threads and `runTest`'s virtual clock cannot see them.
 */
class ServerRequestTransportTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A far end standing in for Hermes; every frame the phone sends lands in [received]. */
    private class FarEnd(
        private val capabilityReply: (String) -> String,
        private val afterCapability: (WebSocket) -> Unit = {},
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(READY)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val obj = Json.parseToJsonElement(text).jsonObject
            val method = obj["method"]?.jsonPrimitive?.content
            val id = obj["id"]?.toString()
            when (method) {
                "client.capabilities" -> {
                    webSocket.send(capabilityReply(id!!))
                    afterCapability(webSocket)
                }
                null -> Unit // a response to one of our requests
                else -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
            }
        }
        fun next(): JsonObject =
            Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS) ?: error("no frame from the client")).jsonObject
    }

    private fun connect(farEnd: FarEnd): Pair<HermesGatewayClient, OkHttpClient> {
        serverRule.server.enqueue(MockResponse.Builder().webSocketUpgrade(farEnd).build())
        val url = serverRule.server.url("/api/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(okHttp, json, testScope, wsEndpointProvider = { GatewayWebSocketEndpoint(url, "t") })
        return client to okHttp
    }

    private fun tearDown(client: HermesGatewayClient, okHttp: OkHttpClient) {
        client.cancelNow()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        okHttp.connectionPool.evictAll()
    }

    private val advertised = { id: String ->
        """{"jsonrpc":"2.0","id":$id,"result":{"server_requests":["approval","clarify","sudo"]}}"""
    }

    @Test fun the_first_frame_after_ready_advertises_server_requests() = runTest {
        val farEnd = FarEnd(advertised)
        val (client, okHttp) = connect(farEnd)
        client.connect()
        try {
            val first = withContext(Dispatchers.IO) { farEnd.next() }
            assertEquals("client.capabilities", first["method"]!!.jsonPrimitive.content)
            assertEquals(buildJsonObject { put("server_requests", true) }, first["params"])
        } finally {
            tearDown(client, okHttp)
        }
    }

    @Test fun an_old_hermes_that_does_not_know_the_capability_still_works() = runTest {
        val farEnd = FarEnd({ id ->
            """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method: client.capabilities"}}"""
        })
        val (client, okHttp) = connect(farEnd)
        client.connect()
        try {
            val result = withContext(Dispatchers.Default) {
                withTimeout(5_000) { client.call("ping", buildJsonObject {}) }
            }
            assertEquals("true", result.jsonObject["ok"]!!.jsonPrimitive.content)
            assertTrue(client.connectionState.value is ConnectionState.Connected)
        } finally {
            tearDown(client, okHttp)
        }
    }

    @Test fun an_approval_request_is_emitted_as_a_marked_event() = runTest {
        val farEnd = FarEnd(advertised) { ws ->
            ws.send("""{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"approval","params":{"session_id":"live-1","request_id":"q-7","command":"ls","choices":["once","deny"]}}""")
        }
        val (client, okHttp) = connect(farEnd)
        // Subscribed before the socket opens: the request follows the handshake immediately.
        val arriving = testScope.async(start = CoroutineStart.UNDISPATCHED) {
            client.events.first { it.type == "approval.request" }
        }
        client.connect()
        try {
            val event = withContext(Dispatchers.Default) { withTimeout(5_000) { arriving.await() } }
            assertEquals("live-1", event.sessionId)
            assertEquals("srq-0123456789ab", ServerRequests.idOf(event.payload))
        } finally {
            tearDown(client, okHttp)
        }
    }

    /**
     * Upstream waits up to the request's whole deadline for an answer that a client without a
     * handler would never send; -32601 is how the client says so, and upstream reads any error
     * as "no answer" and moves on.
     */
    @Test fun a_request_this_client_cannot_answer_gets_method_not_found_at_once() = runTest {
        val farEnd = FarEnd(advertised) { ws ->
            ws.send("""{"jsonrpc":"2.0","id":"srq-cccccccccccc","method":"sudo","params":{"session_id":"live-1","command":"apt"}}""")
        }
        val (client, okHttp) = connect(farEnd)
        client.connect()
        try {
            val reply = withContext(Dispatchers.IO) {
                generateSequence { farEnd.next() }.first { "method" !in it }
            }
            assertEquals(JsonPrimitive("srq-cccccccccccc"), reply["id"])
            assertEquals("-32601", reply["error"]!!.jsonObject["code"].toString())
            assertFalse("result" in reply)
        } finally {
            tearDown(client, okHttp)
        }
    }

    /** A far end whose `session.resume` answers with [resumeResult] (after the capability reply). */
    private fun resumingFarEnd(capability: (String) -> String, resumeResult: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(READY) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val id = obj["id"]?.toString()
            when (obj["method"]?.jsonPrimitive?.content) {
                "client.capabilities" -> webSocket.send(capability(id!!))
                "session.resume" -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":$resumeResult}""")
            }
        }
    }

    private suspend fun resumeAndCollect(far: WebSocketListener): List<ServerEvent> {
        serverRule.server.enqueue(MockResponse.Builder().webSocketUpgrade(far).build())
        val url = serverRule.server.url("/api/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(okHttp, json, testScope, wsEndpointProvider = { GatewayWebSocketEndpoint(url, "t") })
        val seen = java.util.Collections.synchronizedList(mutableListOf<ServerEvent>())
        val collecting = testScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { seen += it } }
        client.connect()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { client.call("session.resume", buildJsonObject { put("session_id", "stored-1") }) }
                kotlinx.coroutines.delay(200)
            }
            return seen.filter { it.type != "gateway.ready" }
        } finally {
            collecting.cancel()
            tearDown(client, okHttp)
        }
    }

    @Test fun a_resume_re_delivers_open_requests_and_then_says_which_are_open() = runTest {
        val events = resumeAndCollect(
            resumingFarEnd(advertised, """{"session_id":"live-1","open_requests":[{"id":"srq-aa","method":"clarify","params":{"session_id":"live-1","question":"Q?"}}]}"""),
        )
        assertEquals(listOf("clarify.request", ServerRequests.OPEN_SNAPSHOT_EVENT), events.map { it.type })
        assertEquals("srq-aa", ServerRequests.idOf(events[0].payload))
        assertEquals(listOf("srq-aa"), events[1].strList("ids"))
        assertEquals("live-1", events[1].sessionId)
    }

    /** Upstream omits the field when nothing is open, and the phone must still learn that. */
    @Test fun a_resume_without_open_requests_still_reports_an_empty_snapshot() = runTest {
        val events = resumeAndCollect(resumingFarEnd(advertised, """{"session_id":"live-1"}"""))
        assertEquals(listOf(ServerRequests.OPEN_SNAPSHOT_EVENT), events.map { it.type })
        assertTrue(events.single().strList("ids").isEmpty())
    }

    /** An older Hermes asks through events; its cards have no ids and nothing may prune them. */
    @Test fun a_socket_that_did_not_advertise_gets_no_snapshot() = runTest {
        val events = resumeAndCollect(
            resumingFarEnd({ id -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method"}}""" }, """{"session_id":"live-1"}"""),
        )
        assertTrue(events.isEmpty())
    }

    private companion object {
        const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}
