package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run the store believes is active is asked about — on waking up, on a foreground watchdog
 * tick once it has been silent for a while, and on demand — instead of spinning until an event
 * that may never come. Only silence past the hard cap plus an unreachable Mac ends a run
 * without an answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivePhaseBackstopTest {
    private fun event(type: String, sessionId: String, text: String? = null, running: Boolean? = null) = ServerEvent(
        type = type, sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            running?.let { put("running", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private class Clock(var now: Long = System.currentTimeMillis()) : () -> Long {
        override fun invoke() = now
    }

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val chat: ChatRepository,
        val clock: Clock,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(watchdog: Boolean = false): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val clock = Clock()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(chat, scope, profiles, clock = clock, watchdogEnabled = watchdog)
        runCurrent()
        return Fixture(store, events, chat, clock)
    }

    private fun startRun(f: Fixture, id: String = "s1"): SessionRuntimeKey {
        val key = f.store.register(id, "personal")
        f.store.beginPrompt(key, "跑起来")
        return key
    }

    @Test fun wakingUpAsksAboutEveryActiveRun() = runTest {
        val f = fixture()
        startRun(f)
        // A null handle keeps the test free of the live process poll that a bound handle starts.
        coEvery { f.chat.resume("s1", "personal") } returns null

        f.store.setAppInForeground(true)
        runCurrent()

        coVerify(exactly = 1) { f.chat.resume("s1", "personal") }
    }

    @Test fun theAnswerSettlesTheRunThroughTheNormalFold() = runTest {
        val f = fixture()
        val key = startRun(f)
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.delta", "s1", "部分"))
        runCurrent()
        coEvery { f.chat.resume("s1", "personal") } returns "s1"

        f.store.setAppInForeground(true)
        runCurrent()
        // What Hermes sends back after session.resume for a run that has already finished.
        f.events.emit(event("session.info", "s1", running = false))
        runCurrent()

        val runtime = f.store.runtimes.value.getValue(key)
        assertFalse(runtime.phase.isActive)
        assertFalse(runtime.chat.isGenerating)
        assertFalse(runtime.chat.messages.last { it.role == Role.ASSISTANT }.isStreaming)
    }

    @Test fun probesAreRateLimitedPerRun() = runTest {
        val f = fixture()
        startRun(f)
        // A null handle keeps the test free of the live process poll that a bound handle starts.
        coEvery { f.chat.resume("s1", "personal") } returns null

        f.store.setAppInForeground(true); runCurrent()
        f.store.setAppInForeground(false); runCurrent()
        f.store.setAppInForeground(true); runCurrent()

        coVerify(exactly = 1) { f.chat.resume("s1", "personal") }
    }

    @Test fun theWatchdogAsksOnlyAboutRunsThatHaveGoneSilent() = runTest {
        val f = fixture(watchdog = true)
        startRun(f)
        // A null handle keeps the test free of the live process poll that a bound handle starts.
        coEvery { f.chat.resume("s1", "personal") } returns null
        f.store.setAppInForeground(true)
        runCurrent()
        coVerify(exactly = 1) { f.chat.resume("s1", "personal") } // the wake-up probe

        // One tick later the run is fresh: nothing to ask.
        f.clock.now += 60_000L
        advanceTimeBy(60_001L); runCurrent()
        coVerify(exactly = 1) { f.chat.resume("s1", "personal") }

        // Past the stale threshold the next tick asks again.
        f.clock.now += 3 * 60_000L
        advanceTimeBy(60_001L); runCurrent()
        coVerify(exactly = 2) { f.chat.resume("s1", "personal") }
        f.store.setAppInForeground(false)
    }

    @Test fun aTransportErrorNeverInventsATerminalState() = runTest {
        val f = fixture()
        val key = startRun(f)
        coEvery { f.chat.resume("s1", "personal") } throws IllegalStateException("socket reset")

        f.store.setAppInForeground(true)
        runCurrent()

        assertTrue(f.store.runtimes.value.getValue(key).phase.isActive)
    }

    @Test fun silentPastTheHardCapAndUnreachableTwiceEndsAsInterrupted() = runTest {
        val f = fixture()
        val key = startRun(f)
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        coEvery { f.chat.resume("s1", "personal") } throws IllegalStateException("no route to host")

        f.clock.now += 31 * 60_000L
        assertEquals(SessionRuntimeStore.ProbeResult.FAILED, f.store.probe(key, force = true))
        assertTrue("第一次失败不下结论", f.store.runtimes.value.getValue(key).phase.isActive)
        assertEquals(SessionRuntimeStore.ProbeResult.GAVE_UP, f.store.probe(key, force = true))

        val runtime = f.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.INTERRUPTED, runtime.phase)
        assertFalse(runtime.chat.isGenerating)
        val answer = runtime.chat.messages.last { it.role == Role.ASSISTANT }
        assertFalse(answer.isStreaming)
        assertTrue("带「已中断」注记", answer.interrupted)
    }
}
