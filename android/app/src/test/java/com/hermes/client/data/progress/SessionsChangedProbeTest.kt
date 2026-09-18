package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * HG-57: `sessions.changed` used to be a dead letter.
 *
 * Upstream broadcasts it whenever the session list moves, and it carries no session id because it is
 * about the list rather than about one conversation. The session-scoped event path therefore either
 * dropped it (`unmatched` / `ambiguous`) or, with exactly one active run, attributed it to that run
 * and quietly refreshed its `lastEventAt` — postponing the watchdog on the strength of an event that
 * said nothing about it. Meanwhile a conversation ran on the Mac for minutes with nothing on screen,
 * and hundreds of these went past saying "something moved, go and look".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsChangedProbeTest {
    private class Fixture(val store: SessionRuntimeStore, val events: MutableSharedFlow<ServerEvent>, val chat: ChatRepository)

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val store = SessionRuntimeStore(
            chat,
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            profiles,
        )
        return Fixture(store, events, chat)
    }

    private fun sessionsChanged() = ServerEvent(
        type = SESSIONS_CHANGED_EVENT,
        sessionId = null,
        payload = buildJsonObject { },
    )

    @Test fun aVisibleIdleConversationIsAskedAboutWhenTheListMoves() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } returns "live-1"
        val key = f.store.register("s1", "personal")
        f.store.setVisible(key, true)
        // Idle is precisely the belief under suspicion: nothing on this phone knows the Mac picked
        // the conversation up. Before this change no probe would ever have been sent for it.
        assertEquals(SessionRunPhase.IDLE, f.store.runtimes.value.getValue(key).phase)

        f.events.emit(sessionsChanged())
        runCurrent()

        coVerify(exactly = 1) { f.chat.resume("s1", "personal") }
    }

    @Test fun aListMoveWithNothingOnScreenCostsNothing() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } returns "live-1"
        f.store.register("s1", "personal")

        f.events.emit(sessionsChanged())
        runCurrent()

        // No conversation is being looked at, so there is no one to correct and nothing to spend.
        coVerify(exactly = 0) { f.chat.resume(any(), any()) }
    }

    @Test fun aBurstOfListMovesIsOneRoundOfProbes() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } returns "live-1"
        val key = f.store.register("s1", "personal")
        f.store.setVisible(key, true)

        repeat(20) { f.events.emit(sessionsChanged()) }
        runCurrent()

        // Upstream sends these in bursts — one per list mutation. Fanning a resume out per event
        // would turn a title update into twenty round trips.
        coVerify(exactly = 1) { f.chat.resume("s1", "personal") }
    }

    @Test fun theListMoveIsNotCreditedToWhicheverRunHappensToBeActive() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } returns "live-1"
        val running = f.store.register("s1", "personal")
        f.store.beginPrompt(running, "昨天工作室数据如何")
        runCurrent()
        val before = f.store.runtimes.value.getValue(running).lastEventAt

        f.events.emit(sessionsChanged())
        runCurrent()

        // With exactly one active run the old resolve() handed the event to it, refreshing the
        // silence clock the watchdog reads. An event that names no session may not do that.
        assertEquals(before, f.store.runtimes.value.getValue(running).lastEventAt)
    }

    @Test fun aCuriosityProbeThatFailsNeverInventsAVerdict() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } throws IllegalStateException("not connected")
        val key = f.store.register("s1", "personal")

        // Two failures and a long silence is what lets markUnconfirmed write "interrupted" — but
        // only for a run we believed was running. An idle conversation has no verdict to lose.
        repeat(3) { f.store.probe(key, force = true, includeIdle = true) }
        runCurrent()

        val phase = f.store.runtimes.value.getValue(key).phase
        assertEquals(SessionRunPhase.IDLE, phase)
        assertNotEquals(SessionRunPhase.INTERRUPTED, phase)
    }

    @Test fun theOrdinaryProbeStillAsksOnlyAboutRunsItBelievesAreRunning() = runTest {
        val f = fixture()
        coEvery { f.chat.resume(any(), any()) } returns "live-1"
        val key = f.store.register("s1", "personal")

        val result = f.store.probe(key, force = true)
        runCurrent()

        // Every automatic caller — the watchdog, foreground wake, cold-start restore — goes through
        // this default. Opening it for them would put a resume behind every idle row.
        assertEquals(SessionRuntimeStore.ProbeResult.IDLE, result)
        coVerify(exactly = 0) { f.chat.resume(any(), any()) }
    }
}
