package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A far end that keeps accepting sockets and dropping them must not be mistaken for a healthy one.
 *
 * Reaching `Connected` used to reset the backoff, and reaching `Connected` only means the handshake
 * succeeded. In HG-65 it succeeded every single time: `socket upgraded`, `gateway.ready`, then
 * silence and a close 4.2–4.6 seconds later, without one RPC ever being answered — 197 rounds of it.
 * Every round reset `attempt` to 0, so the log reads
 *
 *   reconnect scheduled in 500ms (gen=13, attempt=0)
 *   reconnect scheduled in 500ms (gen=14, attempt=0)
 *   reconnect scheduled in 500ms (gen=15, attempt=0)
 *
 * for twenty-four minutes: twelve dials a minute, the exponential backoff never engaging, and the
 * user watching a spinner because the `session.create` on each of those sockets died with it.
 *
 * Real clocks: OkHttp runs on its own threads and `runTest`'s virtual clock cannot see them.
 */
class FlappingConnectionBackoffTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        DebugLog.detachStore()
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.setStateSnapshot(null)
        DebugLog.clear()
        DebugLog.detachStore()
    }

    private fun messages(): List<String> = DebugLog.entries.value.map { it.message }

    private suspend fun awaitLine(fragment: String, timeoutMs: Long = 20_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (messages().none { it.contains(fragment) } && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            messages().any { it.contains(fragment) }
        }

    /** Accepts the socket, completes the handshake, then hangs up — HG-65's far end, in miniature. */
    private fun enqueueReadyThenHangUp(server: MockWebServer, times: Int) {
        repeat(times) {
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(READY)
                        webSocket.close(1000, "")
                    }
                }).build(),
            )
        }
    }

    private fun client(url: String): Pair<HermesGatewayClient, OkHttpClient> {
        val okHttp = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        return HermesGatewayClient(
            okHttp, json, testScope,
            wsEndpointProvider = { GatewayWebSocketEndpoint(url, "t") },
        ) to okHttp
    }

    private fun wsUrl(server: MockWebServer) =
        server.url("/api/ws").toString().replace("http", "ws")

    private fun tearDownClient(client: HermesGatewayClient, okHttp: OkHttpClient) {
        client.cancelNow()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        okHttp.connectionPool.evictAll()
    }

    @Test fun a_handshake_that_answers_nothing_does_not_reset_the_backoff() = runTest {
        enqueueReadyThenHangUp(serverRule.server, times = 6)
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()

            // attempt=0 is the first death; the escalation is what was missing.
            assertTrue("the first retry must happen", awaitLine("attempt=0"))
            assertTrue(
                "a socket that answered nothing must not reset the backoff: ${messages()}",
                awaitLine("attempt=1"),
            )
            assertTrue(
                "and it must keep escalating rather than sit at the floor: ${messages()}",
                awaitLine("attempt=2"),
            )
            assertTrue(
                "the wait must actually grow, not just the counter",
                messages().any { it.contains("reconnect scheduled in 2000ms") },
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * Every Hermes answers the capability handshake the instant a socket opens — a newer one with
     * its method list, an older one with -32601 — so that answer proves nothing about the socket
     * staying useful. If it counted as "answered an RPC", HG-65's far end would reset the backoff
     * on every round again.
     */
    @Test fun the_capability_answer_alone_does_not_reset_the_backoff() = runTest {
        repeat(6) {
            serverRule.server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(READY)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }["id"]
                        webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"server_requests":["approval"]}}""")
                        webSocket.close(1000, "")
                    }
                }).build(),
            )
        }
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            assertTrue("the first retry must happen", awaitLine("attempt=0"))
            assertTrue(
                "a socket that only answered the capability handshake must not reset the backoff: ${messages()}",
                // Pinned to the generation: once the enqueued sockets run out, read timeouts
                // escalate the backoff anyway, and would hide a reset during the rounds that count.
                awaitLine("(gen=3, attempt=2)"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * The diagnostic half. A far end that closes without a reason leaves the log saying only
     * `closed`, which cannot tell a normal 1000 from a 1006, a 1013 (Mac offline) or a 4403
     * (authorization revoked) — and in HG-65 that was the entire record of 197 closes.
     */
    @Test fun the_close_records_its_code_and_that_the_socket_answered_nothing() = runTest {
        enqueueReadyThenHangUp(serverRule.server, times = 2)
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            assertTrue("the close code must be recorded", awaitLine("code=1000"))
            assertTrue(
                "and that this connection carried nothing: ${messages()}",
                messages().any { it.contains("answered nothing") },
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    private companion object {
        const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}
