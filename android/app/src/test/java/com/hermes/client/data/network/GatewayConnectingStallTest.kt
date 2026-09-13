package com.hermes.client.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HG-42. The client came to rest in [ConnectionState.Connecting] with `manuallyClosed=true` and no
 * socket, and stayed there for 13 minutes: `onSocketClosed()` needs a socket, the handshake
 * watchdog had already stood down as "closed by the app", and every later `connect()` was turned
 * away by the already-Connecting guard. Every RPC in that window failed on the readiness gate
 * ("no gateway.ready in 15000ms") and the user simply could not open a conversation.
 *
 * These drive the two seams directly rather than trying to win a thread race: the defect is not
 * that some interleaving is likely, it is that the state machine had no rule forbidding the
 * result. A rule can be asserted; a race cannot.
 */
class GatewayConnectingStallTest {
    private val json = Json { ignoreUnknownKeys = true }
    // Per-test scope: the endpoint provider below never returns, so its coroutine has to be
    // cancellable at the end of the test that started it rather than outliving the class.
    private val job = SupervisorJob()
    private val testScope = CoroutineScope(job + Dispatchers.IO)

    @org.junit.After fun tearDown() {
        job.cancel()
    }

    /** Never resolves, so the attempt stays in Connecting with no socket for as long as we like. */
    private class Probe(
        okHttp: OkHttpClient,
        json: Json,
        scope: CoroutineScope,
        handshakeTimeoutMs: Long,
        val endpointCalls: AtomicInteger = AtomicInteger(0),
        /** True once the watchdog's teardown has been swallowed — see [swallowSocketDeath]. */
        var swallowSocketDeath: Boolean = false,
        wsEndpointProvider: suspend () -> GatewayWebSocketEndpoint,
    ) : HermesGatewayClient(
        okHttp, json, scope,
        handshakeTimeoutMs = handshakeTimeoutMs,
        wsEndpointProvider = wsEndpointProvider,
    ) {
        /** Reaches the shared entry point every caller funnels through. */
        fun openSocketForTest() = openSocket()

        override fun onSocketClosed(gen: Int, reason: String, retry: Boolean) {
            // Stands in for the real incident, where the machinery that should have torn the
            // attempt down had already excused itself: the watchdog logged "skipped (closed by
            // the app)" instead of reconnecting. Whatever the cause, the client is left claiming
            // Connecting with nothing behind it, and that is the state under test.
            if (swallowSocketDeath) return
            super.onSocketClosed(gen, reason, retry)
        }
    }

    private fun probe(handshakeTimeoutMs: Long = 20_000L): Pair<Probe, OkHttpClient> {
        val okHttp = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val calls = AtomicInteger(0)
        val client = Probe(
            okHttp, json, testScope,
            handshakeTimeoutMs = handshakeTimeoutMs,
            endpointCalls = calls,
            wsEndpointProvider = {
                calls.incrementAndGet()
                kotlinx.coroutines.delay(Long.MAX_VALUE) // never dials
                GatewayWebSocketEndpoint("ws://127.0.0.1:1/api/ws", "t")
            },
        )
        return client to okHttp
    }

    private suspend fun awaitState(client: HermesGatewayClient, want: ConnectionState) =
        withTimeout(5_000) {
            while (client.connectionState.value != want) kotlinx.coroutines.delay(5)
        }

    /**
     * The invariant the client did not have: once the app has closed it, nothing may put it back
     * into Connecting. Before the fix `openSocket()` entered Connecting unconditionally — and
     * installed a fresh readiness gate that nothing would ever complete, because `close()` had
     * already failed the previous one.
     */
    @Test fun a_close_that_beats_open_socket_does_not_strand_the_client_in_connecting() = runTest {
        val (client, okHttp) = probe()
        try {
            withContext(Dispatchers.Default) {
                client.close("app idle in the background")
                assertEquals(ConnectionState.Disconnected, client.connectionState.value)

                // The caller had already passed its own manuallyClosed check when close() landed.
                client.openSocketForTest()

                assertEquals(
                    "a closed client must stay Disconnected: ${client.connectionSnapshot()}",
                    ConnectionState.Disconnected,
                    client.connectionState.value,
                )
                assertEquals("and must not dial", 0, client.endpointCalls.get())
            }
        } finally {
            okHttp.dispatcher.executorService.shutdownNow()
        }
    }

    /**
     * And the escape hatch for a Connecting that outlived its attempt anyway. The log shows
     * `connect() no-op — already Connecting` printed over and over for 13 minutes, beside a
     * snapshot reading `socket=none` — an idempotence guard protecting a corpse. A connect() that
     * arrives long after the handshake timeout must force a fresh socket instead.
     */
    @Test fun a_connect_on_a_stalled_connecting_forces_a_fresh_socket() = runTest {
        val (client, okHttp) = probe(handshakeTimeoutMs = 120L)
        try {
            withContext(Dispatchers.Default) {
                client.swallowSocketDeath = true
                client.connect()
                awaitState(client, ConnectionState.Connecting)
                withTimeout(5_000) {
                    while (client.endpointCalls.get() < 1) kotlinx.coroutines.delay(5)
                }
                // Outlive the watchdog, whose teardown this client swallows.
                kotlinx.coroutines.delay(400)
                assertEquals(
                    "precondition: still Connecting with nothing behind it — ${client.connectionSnapshot()}",
                    ConnectionState.Connecting,
                    client.connectionState.value,
                )

                client.connect()

                withTimeout(5_000) {
                    while (client.endpointCalls.get() < 2) kotlinx.coroutines.delay(5)
                }
                assertTrue(
                    "connect() must open a second attempt, not no-op: ${client.connectionSnapshot()}",
                    client.endpointCalls.get() >= 2,
                )
            }
        } finally {
            okHttp.dispatcher.executorService.shutdownNow()
        }
    }

    /**
     * And the failure an RPC sees while the gate is shut has to name the connection. It used to
     * throw the same anonymous GatewayRpcException(0) as every transport failure, so the send path
     * reported 「消息发送失败」 for a prompt that had never left the phone.
     */
    @Test fun an_rpc_blocked_on_the_readiness_gate_reports_a_handshake_timeout() = runTest {
        val (client, okHttp) = probe()
        try {
            val thrown = runCatching { client.call("session.resume", buildJsonObject { }) }.exceptionOrNull()
            assertTrue(
                "the readiness gate must throw its own type, got $thrown",
                thrown is GatewayReadinessTimeoutException,
            )
        } finally {
            okHttp.dispatcher.executorService.shutdownNow()
        }
    }

    /**
     * A live attempt is still protected. Without this the fix above would turn connect() into a
     * socket leak: two live WebSockets that the generation check only shadows and never closes.
     */
    @Test fun a_connect_on_a_young_connecting_is_still_a_no_op() = runTest {
        val (client, okHttp) = probe(handshakeTimeoutMs = 20_000L)
        try {
            withContext(Dispatchers.Default) {
                client.connect()
                awaitState(client, ConnectionState.Connecting)
                withTimeout(5_000) {
                    while (client.endpointCalls.get() < 1) kotlinx.coroutines.delay(5)
                }

                client.connect()
                kotlinx.coroutines.delay(200)

                assertEquals("the in-flight attempt must be left alone", 1, client.endpointCalls.get())
            }
        } finally {
            okHttp.dispatcher.executorService.shutdownNow()
        }
    }
}
