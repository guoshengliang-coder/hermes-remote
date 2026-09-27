package com.hermes.client.ui.chat

import android.app.Application
import android.os.SystemClock
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
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.clear()
        DebugLog.detachStore()
        testScope.cancel()
    }

    private fun messages(): List<String> = DebugLog.entries.value.map { it.message }

    /** Pump Robolectric's main looper so `withContext(Dispatchers.Main)` callbacks can land. */
    private fun awaitEvent(collected: () -> VoiceEvent?, timeoutMs: Long = 10_000): VoiceEvent? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var event = collected()
        while (event == null && SystemClock.elapsedRealtime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            event = collected()
            if (event == null) Thread.sleep(50)
        }
        return event
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
}
