package com.hermes.client.data.network

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import java.util.concurrent.TimeUnit

class HermesGatewayClientTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }
    // A long-lived scope shared by all tests in this class; real Dispatchers.IO threads
    // are fine here because OkHttp itself runs on real threads.
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Creates an OkHttpClient and HermesGatewayClient for one test.
     * Call [drainOkHttp] on the returned OkHttpClient before the test ends
     * so MockWebServer's taskRunner queue is empty when the rule's @after fires.
     */
    private fun makeClientAndHttp(server: MockWebServer): Pair<HermesGatewayClient, OkHttpClient> {
        val base = server.url("/api/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return HermesGatewayClient(okHttp, json, testScope) {
            GatewayWebSocketEndpoint(base, "t")
        } to okHttp
    }

    /**
     * Forcibly terminate the OkHttp client so MockWebServer's internal task queues drain.
     * We use cancel() on the WebSocket (immediate TCP close) rather than close() (graceful
     * close handshake) because the MockWebServer rule's after() fires before we can wait for
     * the handshake to complete.
     */
    private fun tearDownClient(client: HermesGatewayClient, okHttp: OkHttpClient) {
        // Cancel (force-close) rather than graceful close so TCP sockets shut immediately.
        client.cancelNow()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        okHttp.connectionPool.evictAll()
    }

    private companion object {
        const val GATEWAY_READY_FRAME =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }

    @Test fun localAccountRepairGateStopsEndpointBackoffUntilExplicitReconnect() = runTest {
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        var resolutions = 0
        val firstResolution = CompletableDeferred<Unit>()
        val client = HermesGatewayClient(
            okHttp = okHttp,
            json = json,
            scope = testScope,
            backoff = BackoffPolicy(baseMs = 10, maxMs = 10),
        ) {
            resolutions += 1
            firstResolution.complete(Unit)
            throw GatewayEndpointException("account device selection required", retryable = false)
        }
        try {
            client.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { firstResolution.await() }
                kotlinx.coroutines.delay(100)
            }

            assertEquals(1, resolutions)
            assertTrue(client.connectionState.value is ConnectionState.Disconnected)

            client.reconnectNow()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (resolutions < 2) kotlinx.coroutines.delay(10)
                }
            }
            assertEquals(2, resolutions)
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    @Test fun call_resolves_with_matching_reply() = runTest {
        // Server sends gateway.ready on open, then echoes a result for whatever id the client sent.
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Send gateway.ready so the client's readyGate is lifted before call().
                        webSocket.send(GATEWAY_READY_FRAME)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
                        webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"pong":true}}""")
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            client.connect()
            // OkHttp WebSocket runs on real threads — use real time via Dispatchers.Default
            val result = withContext(Dispatchers.Default) {
                withTimeout(5_000) { client.call("ping", buildJsonObject {}) }
            }
            assertEquals("true", result.jsonObject["pong"]!!.jsonPrimitive.content)
            val upgrade = serverRule.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/ws", upgrade.target)
            assertEquals("t", upgrade.headers["X-Hermes-Session-Token"])
            assertFalse(upgrade.target.contains("token="))
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    @Test fun account_websocket_sends_bearer_without_legacy_header_or_query_token() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(GATEWAY_READY_FRAME)
                    }
                },
            ).build(),
        )
        val base = serverRule.server.url("/v2/devices/mac-1/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Cookie", "legacy-dashboard=session")
                        .header("X-Test-Client", "legacy")
                        .build(),
                )
            }
            .build()
        val accountOkHttp = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Test-Client", "account")
                        .build(),
                )
            }
            .build()
        val client = HermesGatewayClient(okHttp, json, testScope, accountOkHttp = accountOkHttp) {
            GatewayWebSocketEndpoint(base, bearerToken = "hga_secret")
        }
        try {
            client.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (client.connectionState.value !is ConnectionState.Connected) {
                        kotlinx.coroutines.delay(10)
                    }
                }
            }
            val upgrade = serverRule.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer hga_secret", upgrade.headers["Authorization"])
            assertEquals(null, upgrade.headers["X-Hermes-Session-Token"])
            assertEquals(null, upgrade.headers["Cookie"])
            assertEquals("account", upgrade.headers["X-Test-Client"])
            assertFalse(upgrade.target.contains("token="))
        } finally {
            tearDownClient(client, okHttp)
            accountOkHttp.dispatcher.executorService.shutdown()
            accountOkHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            accountOkHttp.connectionPool.evictAll()
        }
    }

    @Test fun terminalAccountHandshakeRejectionStopsBackoffAndReportsTheStatus() = runTest {
        assertTrue(isTerminalAccountHandshakeStatus(401))
        assertTrue(isTerminalAccountHandshakeStatus(404))
        assertFalse(isTerminalAccountHandshakeStatus(403))
        assertFalse(isTerminalAccountHandshakeStatus(null))

        serverRule.server.enqueue(MockResponse.Builder().code(401).build())
        val base = serverRule.server.url("/v2/devices/mac-1/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val accountOkHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val rejected = CompletableDeferred<Pair<Int, String?>>()
        val client = HermesGatewayClient(
            okHttp = okHttp,
            json = json,
            scope = testScope,
            backoff = BackoffPolicy(baseMs = 10, maxMs = 10),
            accountOkHttp = accountOkHttp,
            onAccountHandshakeRejected = { status, deviceId -> rejected.complete(status to deviceId) },
        ) {
            GatewayWebSocketEndpoint(
                base,
                bearerToken = "hga_revoked",
                accountDeviceId = "mac-1",
            )
        }
        try {
            client.connect()
            val status = withContext(Dispatchers.Default) {
                withTimeout(5_000) { rejected.await() }
            }
            assertEquals(401 to "mac-1", status)
            withContext(Dispatchers.Default) { kotlinx.coroutines.delay(100) }
            assertEquals(ConnectionState.Disconnected, client.connectionState.value)
            assertEquals(1, serverRule.server.requestCount)
        } finally {
            tearDownClient(client, okHttp)
            accountOkHttp.dispatcher.executorService.shutdown()
            accountOkHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            accountOkHttp.connectionPool.evictAll()
        }
    }

    @Test fun accountAuthorizationCloseGetsOnlyOneClassificationReconnect() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(GATEWAY_READY_FRAME)
                        webSocket.close(4403, "account authorization changed")
                    }
                },
            ).build(),
        )
        // A transiently failed classification handshake must stop here rather than starting an
        // unbounded authorization-retry loop. Foreground/manual recovery can initiate a new cycle.
        serverRule.server.enqueue(MockResponse.Builder().code(503).build())
        val base = serverRule.server.url("/v2/devices/mac-1/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val accountOkHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val rejected = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String?>>()
        val client = HermesGatewayClient(
            okHttp = okHttp,
            json = json,
            scope = testScope,
            backoff = BackoffPolicy(baseMs = 10, maxMs = 10),
            accountOkHttp = accountOkHttp,
            onAccountHandshakeRejected = { status, deviceId -> rejected += status to deviceId },
        ) {
            GatewayWebSocketEndpoint(
                base,
                bearerToken = "hga_access",
                accountDeviceId = "mac-1",
            )
        }
        try {
            client.connect()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (serverRule.server.requestCount < 2 ||
                        client.connectionState.value != ConnectionState.Disconnected
                    ) {
                        kotlinx.coroutines.delay(10)
                    }
                }
                kotlinx.coroutines.delay(100)
            }

            assertEquals(2, serverRule.server.requestCount)
            assertTrue(rejected.isEmpty())
        } finally {
            tearDownClient(client, okHttp)
            accountOkHttp.dispatcher.executorService.shutdown()
            accountOkHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            accountOkHttp.connectionPool.evictAll()
        }
    }

    @Test fun call_times_out_when_gateway_never_replies() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(GATEWAY_READY_FRAME)
                    }
                },
            ).build(),
        )
        val base = serverRule.server.url("/api/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(
            okHttp = okHttp,
            json = json,
            scope = testScope,
            rpcTimeoutMs = 100,
        ) { GatewayWebSocketEndpoint(base, "t") }
        try {
            client.connect()
            val error = withContext(Dispatchers.Default) {
                runCatching { withTimeout(5_000) { client.call("never-replies", buildJsonObject {}) } }
                    .exceptionOrNull()
            }
            assertTrue(error is GatewayRpcException)
            assertEquals("gateway response timeout", error?.message)
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    @Test fun emits_gateway_ready_and_flips_connected() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(GATEWAY_READY_FRAME)
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            withContext(Dispatchers.Default) {
                client.events.test {
                    client.connect()
                    val event = withTimeout(5_000) { awaitItem() }
                    assertEquals("gateway.ready", event.type)
                    assertEquals(ConnectionState.Connected, client.connectionState.value)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * Asserts that connectionState is Connecting after the socket opens but BEFORE
     * gateway.ready is delivered, and Connected only after gateway.ready arrives.
     */
    @Test fun state_is_connecting_until_gateway_ready_received() = runTest {
        // Gate that signals the test once the socket has opened server-side.
        val socketOpened = CompletableDeferred<WebSocket>()

        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socketOpened.complete(webSocket)
                        // Do NOT send gateway.ready yet — wait for the test to release.
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            withContext(Dispatchers.Default) {
                client.connect()

                // Wait until the server-side socket is open.
                val serverWs = withTimeout(5_000) { socketOpened.await() }

                // State must be Connecting (not Connected) before gateway.ready.
                assertEquals(ConnectionState.Connecting, client.connectionState.value)

                // Now deliver gateway.ready from the server.
                serverWs.send(GATEWAY_READY_FRAME)

                // Poll until Connected (the message is dispatched on OkHttp's IO thread).
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(ConnectionState.Connected, client.connectionState.value)
            }
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    @Test fun connect_after_an_idle_close_opens_a_fresh_socket_without_manual_retry() = runTest {
        repeat(2) {
            serverRule.server.enqueue(
                MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(GATEWAY_READY_FRAME)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                ).build(),
            )
        }
        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            withContext(Dispatchers.Default) {
                client.connect()
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.Connected) kotlinx.coroutines.delay(10)
                }
                client.close()
                assertEquals(ConnectionState.Disconnected, client.connectionState.value)

                // This is the foreground coordinator path. connect(), not reconnectNow(), must
                // clear the intentional-close latch and establish a new socket.
                client.connect()
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.Connected) kotlinx.coroutines.delay(10)
                }
                assertEquals(2, serverRule.server.requestCount)
            }
        } finally {
            tearDownClient(client, okHttp)
        }
    }
}
