package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What the connection path has to say about itself.
 *
 * There was no test of this kind before HG-27, and HG-27 is what the absence cost: a report with
 * 5,864 log entries over 31 hours that still could not say why a socket sat in `Connecting` for a
 * minute — because every silent early-return on the recovery path stayed silent. These tests pin
 * the lines that make the next one answerable. They are about the log, not about behaviour; the
 * behavioural fix for HG-27 is separate.
 *
 * Real clocks throughout: OkHttp runs on real threads and `runTest`'s virtual clock expires
 * timeouts while the socket has not moved.
 */
class GatewayClientLoggingTest {
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
    private fun has(fragment: String) = messages().any { it.contains(fragment) }

    /** Real-clock wait: the virtual clock cannot see OkHttp's threads. */
    private suspend fun awaitLine(fragment: String, timeoutMs: Long = 10_000): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!has(fragment) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            has(fragment)
        }

    private fun client(
        url: String,
        handshakeTimeoutMs: Long = 20_000L,
    ): Pair<HermesGatewayClient, OkHttpClient> {
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        return HermesGatewayClient(
            okHttp, json, testScope,
            handshakeTimeoutMs = handshakeTimeoutMs,
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

    private companion object {
        const val READY = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
        /** A port nothing listens on, so the dial fails without reaching an upgrade. */
        const val DEAD_URL = "ws://127.0.0.1:1/api/ws"
    }

    /**
     * The line HG-27 most needed. Without it the stretch between `opening socket` and either
     * `gateway.ready` or a close is completely dark, and the runbook's rule for spotting a stall
     * — the generation with neither ready nor close after it — cannot tell a dial that never
     * completed from a gateway that accepted the upgrade and then went mute. Different failures,
     * different owners.
     */
    @Test fun a_socket_that_upgrades_and_then_says_nothing_is_still_recorded_as_upgraded() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            assertTrue("the upgrade must be recorded", awaitLine("socket upgraded (gen=1)"))
            assertFalse("the gateway said nothing, so nothing may claim it did", has("gateway.ready"))
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /** The other half of the same distinction: a dial that never reaches an upgrade. */
    @Test fun a_socket_that_never_dials_records_no_upgrade() = runTest {
        val (client, okHttp) = client(DEAD_URL)
        try {
            client.connect()
            assertTrue("the failure must be recorded", awaitLine("socket closed (gen=1)"))
            assertFalse(
                "nothing upgraded, so no line may say it did: ${messages()}",
                has("socket upgraded"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * `_state` is assigned at seven sites and only the drop into Reconnecting was ever logged, so
     * "when did it come back" had to be inferred from `gateway.ready` plus the chat banner.
     */
    @Test fun the_return_to_connected_is_recorded_not_only_the_drop() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(READY)
                    }
                },
            ).build(),
        )
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            assertTrue("→ Connected must be recorded", awaitLine("→ Connected"))
            assertTrue("the entry into Connecting too", has("→ Connecting"))
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * The direct cost of HG-27: the watchdog produced no line at all, so the log could not say
     * which of its three guards had returned early — "superseded", "the app closed it" and "the
     * body never ran" are three different faults and the analysis had to stop at "cannot tell".
     */
    @Test fun a_watchdog_that_stands_down_says_which_guard_sent_it_home() = runTest {
        // The watchdog has to still be armed when its guard becomes true, so the socket must not
        // exist yet: a live socket's close callback cancels the watchdog before it can report.
        // A slow endpoint provider holds the attempt in exactly that state.
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(
            okHttp, json, testScope,
            handshakeTimeoutMs = 300L,
            wsEndpointProvider = {
                kotlinx.coroutines.delay(3_000)
                GatewayWebSocketEndpoint(DEAD_URL, "t")
            },
        )
        try {
            client.connect()
            client.close("test asked for it")
            assertTrue(
                "the watchdog must name the guard that sent it home: ${messages()}",
                awaitLine("handshake watchdog skipped (gen=1): closed by the app"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * The same silence on the other side of the ticket fetch: an attempt abandoned while the
     * endpoint was still resolving used to return with no line, so a hung ticket POST and a
     * socket that never dialled looked identical.
     */
    @Test fun an_endpoint_resolved_too_late_says_it_was_discarded() = runTest {
        val okHttp = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(
            okHttp, json, testScope,
            handshakeTimeoutMs = 20_000L,
            wsEndpointProvider = {
                kotlinx.coroutines.delay(300)
                GatewayWebSocketEndpoint(DEAD_URL, "t")
            },
        )
        try {
            client.connect()
            client.close("test asked for it")
            assertTrue(
                "a discarded endpoint must say why: ${messages()}",
                awaitLine("ws endpoint discarded (gen=1): closed by the app"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * `manuallyClosed` gates the reconnect and used to be invisible: the only trace of a
     * deliberate shutdown was the socket's own `client closing`, with nothing saying the app had
     * asked for it, or which of the three callers had.
     */
    @Test fun a_deliberate_close_names_its_caller() = runTest {
        val (client, okHttp) = client(DEAD_URL)
        try {
            client.close("app idle in the background")
            assertTrue(
                "close() must name its reason: ${messages()}",
                has("close() requested: app idle in the background"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * The reconnect that does not happen is the shape of an unexplained permanent stall: before
     * this line the log simply stopped, and nothing distinguished "the app closed it on purpose"
     * from "a newer generation took over". Only one of those is a bug.
     */
    @Test fun a_reconnect_is_recorded_when_it_is_scheduled_and_when_it_is_dropped() = runTest {
        val (client, okHttp) = client(DEAD_URL)
        try {
            client.connect()
            assertTrue(
                "the backoff must announce itself: ${messages()}",
                awaitLine("reconnect scheduled in"),
            )
            client.close("test asked for it")
            assertTrue(
                "a dropped reconnect must say why: ${messages()}",
                awaitLine("reconnect dropped (gen=1): closed by the app"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * One line that answers the questions HG-27 could not. It must never carry the ticket: the WS
     * URL embeds a single-use credential as a query parameter.
     */
    @Test fun the_snapshot_reports_the_internals_and_never_the_credential() = runTest {
        val (client, okHttp) = client("ws://127.0.0.1:1/api/ws?ticket=SUPERSECRETTICKET")
        try {
            val line = client.connectionSnapshot()
            listOf(
                "state=", "gen=", "attempt=", "manuallyClosed=",
                "readyGate=", "watchdog=", "socket=", "connectingFor=", "sinceReady=",
            ).forEach { field ->
                assertTrue("the snapshot must carry $field, got: $line", line.contains(field))
            }
            assertFalse("the ticket must never reach the log: $line", line.contains("SUPERSECRET"))
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * A five-second poll wrote 55 of the 500 buffered entries in the HG-27 report. Suppressing its
     * request line is only safe because the error reply now names the method itself — otherwise
     * `rpc#7 ← error 4001` would have nothing left to pair with.
     */
    @Test fun a_quiet_poll_writes_no_request_line_but_its_failure_still_names_the_method() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(READY)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = kotlinx.serialization.json.Json.parseToJsonElement(text)
                            .let { kotlinx.serialization.json.Json.encodeToString(it) }
                            .substringAfter("\"id\":").substringBefore(",")
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"session not found"}}""",
                        )
                    }
                },
            ).build(),
        )
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            runCatching {
                withContext(Dispatchers.Default) {
                    withTimeout(8_000) { client.call("process.list", buildJsonObject {}) }
                }
            }
            assertTrue(
                "the error reply must name its method: ${messages()}",
                awaitLine("process.list ← error 4001"),
            )
            assertFalse(
                "a polling request line must stay quiet: ${messages()}",
                has("→ process.list"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    /**
     * `thinking.delta` was missing from the streaming-delta filter and made up 25 of the 500
     * buffered entries in the HG-27 report — a quarter of a category that was meant to be
     * excluded, in a log whose real limit is bytes.
     */
    @Test fun thinking_deltas_are_filtered_like_the_other_streaming_increments() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(READY)
                        webSocket.send(
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"thinking.delta","session_id":"s1","payload":{}}}""",
                        )
                        webSocket.send(
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"s1","payload":{}}}""",
                        )
                    }
                },
            ).build(),
        )
        val (client, okHttp) = client(wsUrl(serverRule.server))
        try {
            client.connect()
            assertTrue("a non-delta event must still be recorded", awaitLine("event tool.start"))
            assertFalse(
                "streaming increments must not reach the log: ${messages()}",
                has("thinking.delta"),
            )
        } finally {
            tearDownClient(client, okHttp)
        }
    }
}
