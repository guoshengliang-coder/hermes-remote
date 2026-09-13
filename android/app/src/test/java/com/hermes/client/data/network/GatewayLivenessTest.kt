package com.hermes.client.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * HG-19, HG-27 and HG-42 were one bug three times: the client came to rest in a state it could not
 * leave. Each was fixed by adding another guard — a handshake watchdog, then diagnostics, then an
 * entry lock — and after each fix nothing in the codebase said which resting states are legal, so
 * the next way in was found by a user rather than by CI.
 *
 * These tests assert the rules instead of the paths. The fuzz below was not told what HG-42 was; it
 * looks for any interleaving that leaves an invariant broken, which is the only kind of test that
 * can catch the fourth one.
 */
class GatewayLivenessTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @After fun tearDown() {
        job.cancel()
    }

    /** Refused instantly, so a socket dies the moment it is dialled and transitions come fast. */
    private val deadUrl = "ws://127.0.0.1:1/api/ws"

    private fun client(
        okHttp: OkHttpClient,
        stallDeadlineMs: Long = 60_000L,
        endpoint: suspend () -> GatewayWebSocketEndpoint = { GatewayWebSocketEndpoint(deadUrl, "t") },
    ) = HermesGatewayClient(
        okHttp, json, scope,
        backoff = BackoffPolicy(baseMs = 1, factor = 1.0, maxMs = 2),
        handshakeTimeoutMs = 200L,
        stallDeadlineMs = stallDeadlineMs,
        wsEndpointProvider = endpoint,
    )

    /**
     * The core rule, hunted for rather than reproduced. Four threads issue the lifecycle calls the
     * real app issues — the foreground coordinator connects, the background one closes, a retry
     * forces a socket — in whatever order the scheduler happens to pick. HG-42 was one specific
     * interleaving of exactly these; this asserts that *no* interleaving leaves the client broken.
     */
    @Test fun no_interleaving_of_connect_close_and_reconnect_breaks_an_invariant() = runTest {
        val okHttp = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        // The endpoint resolves slowly and unevenly, which is what makes this fuzz able to find
        // anything. Resolving instantly means a socket object always exists, so every bad state
        // self-corrects the moment that socket dies and the fuzz proves nothing. A slow resolve
        // reproduces the shape that actually stuck: an attempt abandoned before it ever dialled,
        // leaving no socket whose death could rescue the state machine.
        val c = client(okHttp, endpoint = {
            kotlinx.coroutines.delay(Random.nextLong(0, 25))
            GatewayWebSocketEndpoint(deadUrl, "t")
        })
        val violations = mutableListOf<String>()
        // Land a close() on the seams themselves. Without this the fuzz measured zero entries into
        // the vulnerable window across 1,600 concurrent calls — the state is almost never
        // Disconnected while four threads are hammering it, so connect() no-ops and never reaches
        // the seam at all. With it, every interleaving the seams admit gets exercised.
        val seamRandom = Random(99)
        c.lifecycleSeam = { seam ->
            if (synchronized(seamRandom) { seamRandom.nextInt(3) } == 0) {
                c.close("fuzz: close landing on $seam")
            }
        }
        try {
            withContext(Dispatchers.Default) {
                val threads = 4
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val seed = AtomicInteger(0)
                repeat(threads) {
                    Thread {
                        val random = Random(seed.getAndIncrement())
                        start.await()
                        repeat(250) {
                            when (random.nextInt(4)) {
                                0 -> c.connect()
                                1 -> c.close("fuzz: app idle in the background")
                                2 -> c.reconnectNow()
                                else -> synchronized(violations) { violations += c.invariantViolations() }
                            }
                        }
                        done.countDown()
                    }.apply { isDaemon = true }.start()
                }
                start.countDown()
                done.await(60, TimeUnit.SECONDS)

                // Quiesce, then judge. Mid-flight readings are allowed to be in transition; what
                // must never survive is a violation once everything has settled. The supervisor's
                // deadline is far longer than this wait, so it cannot paper over a violation here —
                // this is testing the state machine, not its backstop.
                Thread.sleep(2_000)
                val settled = c.invariantViolations()
                assertTrue("after the fuzz settled: $settled — ${c.connectionSnapshot()}", settled.isEmpty())

                // The samples taken *during* the fuzz matter more than the final reading, because
                // the bad state HG-42 produced was repaired by the next close() — so a client that
                // spent the run flickering through illegal states could still settle looking fine.
                // Each sample is taken under the lifecycle lock, so any violation seen is one no
                // legal interleaving could have produced.
                val seen = synchronized(violations) { violations.toList() }
                assertTrue("illegal states observed during the fuzz: ${seen.distinct()}", seen.isEmpty())

                // And the consequence the user would feel, stated separately: whatever the fuzz
                // did, a close() still wins and a connect() still reopens. HG-42 lost the second —
                // every later connect() was answered "already Connecting" until a force-stop.
                c.close("test asked for it")
                Thread.sleep(300)
                assertEquals(
                    "close() must still win: ${c.connectionSnapshot()}",
                    ConnectionState.Disconnected,
                    c.connectionState.value,
                )
                c.connect()
                withTimeout(5_000) {
                    while (c.connectionState.value == ConnectionState.Disconnected) kotlinx.coroutines.delay(10)
                }
            }
        } finally {
            tearDownClient(c, okHttp)
        }
    }

    /**
     * The two things a user needs from this object, stated as one property: it can always be shut,
     * and it can always be reopened. HG-42 broke the second — after the race, every connect() was
     * answered "already Connecting" forever, and only force-stopping the app helped.
     */
    @Test fun a_client_that_has_been_hammered_can_still_be_closed_and_reopened() = runTest {
        val okHttp = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val c = client(okHttp, endpoint = {
            kotlinx.coroutines.delay(Random.nextLong(0, 25))
            GatewayWebSocketEndpoint(deadUrl, "t")
        })
        try {
            withContext(Dispatchers.Default) {
                // Two threads, because the failure needs connect() and close() to overlap: run
                // sequentially they simply take turns and nothing can wedge.
                val done = CountDownLatch(2)
                Thread {
                    repeat(400) { c.connect() }
                    done.countDown()
                }.apply { isDaemon = true }.start()
                Thread {
                    repeat(400) { c.close("fuzz: app idle in the background") }
                    done.countDown()
                }.apply { isDaemon = true }.start()
                done.await(60, TimeUnit.SECONDS)

                c.close("test asked for it")
                Thread.sleep(300)
                assertEquals(
                    "close() must always win: ${c.connectionSnapshot()}",
                    ConnectionState.Disconnected,
                    c.connectionState.value,
                )

                c.connect()
                withTimeout(5_000) {
                    while (c.connectionState.value == ConnectionState.Disconnected) kotlinx.coroutines.delay(10)
                }
                assertTrue(
                    "connect() must be able to reopen it: ${c.connectionSnapshot()}",
                    c.connectionState.value != ConnectionState.Disconnected,
                )
            }
        } finally {
            tearDownClient(c, okHttp)
        }
    }

    /**
     * And the backstop for an interleaving nobody has thought of yet. A dial that never resolves
     * leaves the client Connecting with the watchdog's teardown swallowed — whatever the cause, the
     * supervisor must notice that nothing is moving and force a fresh attempt.
     */
    @Test fun a_state_that_stops_moving_is_repaired_without_anyone_calling_connect() = runTest {
        com.hermes.client.data.diagnostics.ConnectionIncidents.clear()
        val okHttp = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val dials = AtomicInteger(0)
        val c = object : HermesGatewayClient(
            okHttp, json, scope,
            handshakeTimeoutMs = 100L,
            stallDeadlineMs = 400L,
            wsEndpointProvider = {
                dials.incrementAndGet()
                kotlinx.coroutines.delay(Long.MAX_VALUE) // never dials
                GatewayWebSocketEndpoint(deadUrl, "t")
            },
        ) {
            // Stands in for the machinery excusing itself, which is what left HG-42's client in
            // Connecting with watchdog=finished and socket=none.
            override fun onSocketClosed(gen: Int, reason: String, retry: Boolean) = Unit
        }
        try {
            withContext(Dispatchers.Default) {
                c.connect()
                withTimeout(5_000) { while (dials.get() < 1) kotlinx.coroutines.delay(5) }

                // Nobody calls connect() again. The supervisor is the only thing that can act.
                withTimeout(5_000) { while (dials.get() < 2) kotlinx.coroutines.delay(10) }

                assertTrue(
                    "the repair must be remembered even with diagnostics off",
                    com.hermes.client.data.diagnostics.ConnectionIncidents.snapshot()
                        .any { it.kind.startsWith("stalled-") },
                )
            }
        } finally {
            tearDownClient(c, okHttp)
            com.hermes.client.data.diagnostics.ConnectionIncidents.clear()
        }
    }

    private fun tearDownClient(client: HermesGatewayClient, okHttp: OkHttpClient) {
        client.cancelNow()
        okHttp.dispatcher.executorService.shutdownNow()
        okHttp.connectionPool.evictAll()
    }
}
