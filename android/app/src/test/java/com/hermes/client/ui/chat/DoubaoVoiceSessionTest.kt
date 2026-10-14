package com.hermes.client.ui.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * HG-144: endpoint resolution runs before the session's first `withTimeout`, and it is the only
 * unbounded segment — an endpoint that never returns (account token refresh waiting on a mutex or
 * a dead VPN tunnel) leaves the "Finishing recognition…" banner with no exit, because neither the
 * Final nor the Failed callback can ever fire. These tests pin the bound.
 *
 * Real clocks throughout: the session launches on Dispatchers.IO with real OkHttp underneath, so
 * `runTest`'s virtual clock would expire timeouts that have not really elapsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoubaoVoiceSessionTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        DebugLog.detachStore()
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
        // The default shadow produces unbounded PCM immediately, unlike a blocking microphone.
        org.robolectric.shadows.ShadowAudioRecord.setSource(object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                Thread.sleep(20L)
                return size
            }
        })
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.clear()
        DebugLog.detachStore()
        testScope.cancel()
        org.robolectric.shadows.ShadowAudioRecord.clearSource()
    }

    private fun messages(): List<String> = DebugLog.entries.value.map { it.message }

    /** Pump Robolectric's main looper so `withContext(Dispatchers.Main)` callbacks can land. */
    private fun awaitEvent(collected: () -> VoiceEvent?, timeoutMs: Long = 10_000): VoiceEvent? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var event = collected()
        while (event == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            event = collected()
            if (event == null) Thread.sleep(50)
        }
        return event
    }

    @Test fun successfulCapture_announcesStartedOnceBeforeEndpointResolution() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(
            context = context, scope = testScope,
            endpoint = { awaitCancellation() }, endpointTimeoutMs = 5_000,
        ) { events.add(it) }
        session.start()
        assertTrue(awaitEvent({ events.firstOrNull { it is VoiceEvent.Started } }) is VoiceEvent.Started)
        Thread.sleep(50L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.count { it is VoiceEvent.Started })
        session.cancel()
    }

    @Test fun deniedMicrophone_neverAnnouncesCaptureStarted() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(
            context = context, scope = testScope,
            endpoint = { awaitCancellation() }, endpointTimeoutMs = 200,
        ) { events.add(it) }
        session.start()
        assertTrue(awaitEvent({ events.lastOrNull() }) is VoiceEvent.Failed)
        assertTrue(events.none { it is VoiceEvent.Started })
        session.cancel()
    }

    @Test fun endpointHang_failsWithTimeoutInsteadOfHangingForever() {
        var event: VoiceEvent? = null
        val session = DoubaoVoiceSession(
            context = ApplicationProvider.getApplicationContext<Application>(),
            scope = testScope,
            endpoint = { awaitCancellation() },
            endpointTimeoutMs = 200,
        ) { event = it }

        session.start()
        val received = awaitEvent({ event })

        assertTrue("no event arrived before the deadline — the session hung", received != null)
        assertTrue(received is VoiceEvent.Failed)
        assertEquals(VoiceFailure.TIMEOUT, (received as VoiceEvent.Failed).reason)
        assertTrue(messages().any { it.startsWith("failed reason=TIMEOUT") })
    }

    @Test fun endpointHang_logsStartAndFailureSoTheHangIsDiagnosable() {
        // HG-144's report carried 5,094 diagnostic lines and not one about voice: the whole path
        // was silent, so a hang could not be told apart from "never started". Pin the bookends.
        var event: VoiceEvent? = null
        val session = DoubaoVoiceSession(
            context = ApplicationProvider.getApplicationContext<Application>(),
            scope = testScope,
            endpoint = { awaitCancellation() },
            endpointTimeoutMs = 200,
        ) { event = it }

        session.start()
        awaitEvent({ event })

        val log = messages()
        assertTrue(log.any { it == "start" })
        assertTrue(log.any { it.startsWith("failed reason=TIMEOUT partialChars=") })
        // The transcript itself never enters the log — only lengths, per the class contract.
        assertTrue(log.none { it.contains("Bearer") })
    }
    private fun response(text: String, final: Boolean = false): okio.ByteString {
        val payload = """{"result":{"text":"$text"}}""".toByteArray()
        return java.nio.ByteBuffer.allocate((if (final) 12 else 8) + payload.size)
            .put(byteArrayOf(0x11, if (final) 0x93.toByte() else 0x90.toByte(), 0x10, 0))
            .apply { if (final) putInt(-2) }.putInt(payload.size).put(payload).array()
            .let { okio.ByteString.of(*it) }
    }

    @Test fun automaticLimit_flushesLastAudioThenReturnsFinalAsDraft() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        server.enqueue(mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(ws: okhttp3.WebSocket, bytes: okio.ByteString) {
                val frame = bytes.toByteArray()
                if (frame[1].toInt() == 0x10) ws.send(response("临时文字"))
                else if (frame[1].toInt() == 0x23) ws.send(response("完整文字", true))
            }
        }).build())
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(context, testScope,
            endpoint = { com.hermes.client.data.network.GatewayWebSocketEndpoint(server.url("/").toString().replace("http", "ws")) },
            recordingLimitMs = 1000L,
        ) { events.add(it) }
        try {
            session.start()
            val final = awaitEvent({ events.firstOrNull { it is VoiceEvent.Final || it is VoiceEvent.Failed } })
            assertEquals(VoiceEvent.Final("完整文字", limited = true), final)
            assertEquals(1, events.count { it is VoiceEvent.LimitReached })
            session.finish(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, events.count { it is VoiceEvent.Final })
        } finally { session.cancel(); server.close() }
    }

    @Test fun disconnectWhileHolding_reportsImmediatelyAndKeepsPartialText() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        server.enqueue(mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(ws: okhttp3.WebSocket, bytes: okio.ByteString) {
                if (bytes[1].toInt() == 0x10) { ws.send(response("断线前文字")); ws.close(1000, "done") }
            }
        }).build())
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(context, testScope,
            endpoint = { com.hermes.client.data.network.GatewayWebSocketEndpoint(server.url("/").toString().replace("http", "ws")) },
        ) { events.add(it) }
        try {
            session.start()
            assertEquals(VoiceEvent.Failed("断线前文字", VoiceFailure.DISCONNECTED),
                awaitEvent({ events.firstOrNull { it is VoiceEvent.Failed } }))
            assertTrue(events.none { it is VoiceEvent.Final })
        } finally { session.cancel(); server.close() }
    }

    @Test fun prematureProviderFinal_neverSendsWhileHolding() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        server.enqueue(mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                if (bytes[1].toInt() == 0x10) webSocket.send(response("提前定稿", true))
            }
        }).build())
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(context, testScope,
            endpoint = { com.hermes.client.data.network.GatewayWebSocketEndpoint(server.url("/").toString().replace("http", "ws")) },
        ) { events.add(it) }
        try {
            session.start()
            assertEquals(VoiceEvent.Failed("提前定稿", VoiceFailure.RECOGNITION),
                awaitEvent({ events.firstOrNull { it is VoiceEvent.Failed } }))
            assertTrue(events.none { it is VoiceEvent.Final })
        } finally { session.cancel(); server.close() }
    }

    @Test fun limitWithoutProviderFinal_hasIndependentFinalTimeoutAndKeepsText() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        server.enqueue(mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                if (bytes[1].toInt() == 0x10) webSocket.send(response("等待定稿"))
            }
        }).build())
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val events = java.util.concurrent.CopyOnWriteArrayList<VoiceEvent>()
        val session = DoubaoVoiceSession(context, testScope,
            endpoint = { com.hermes.client.data.network.GatewayWebSocketEndpoint(server.url("/").toString().replace("http", "ws")) },
            recordingLimitMs = 1000L,
        ) { events.add(it) }
        try {
            session.start()
            assertTrue(awaitEvent({ events.firstOrNull { it is VoiceEvent.LimitReached } }) is VoiceEvent.LimitReached)
            assertTrue(events.none { it is VoiceEvent.Failed || it is VoiceEvent.Final })
            session.finish() // A late release must not remove the independent final deadline.
            assertEquals(VoiceEvent.Failed("等待定稿", VoiceFailure.FINAL_TIMEOUT),
                awaitEvent({ events.firstOrNull { it is VoiceEvent.Failed } }, timeoutMs = 12_000))
            assertTrue(messages().any { it.startsWith("failed reason=FINAL_TIMEOUT") })
            assertTrue(events.none { it is VoiceEvent.Final })
        } finally { session.cancel(); server.close() }
    }

}
