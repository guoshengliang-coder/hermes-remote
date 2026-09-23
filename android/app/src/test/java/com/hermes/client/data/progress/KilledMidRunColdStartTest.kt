package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionPhaseRecord
import com.hermes.client.data.repository.SessionPhaseSnapshot
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * HG-100: killed mid-run, the turn finished while the process was dead, and the app came back
 * saying 「思考中」 forever.
 *
 * Reported 2026-09-22 on a HONOR MBH-AN10 (0.1.140). From the diagnostic log: the prompt went out
 * at 20:22:17, the process died at 20:22:19, the cold start at 20:23:10 restored the phase from
 * disk, `session.access` answered `-32601` (it is absent upstream and always will be —
 * docs/HERMES_CONTRACT.md), the reconnect re-asserted THINKING on the strength of the disk
 * snapshot alone, and the 28-row transcript that arrived one second later — already carrying the
 * finished answer — was accepted without touching the phase. What follows in the log is a bare
 * row of `probing 1 active run(s)` with no verdict between them.
 *
 * Three things had to be true at once, and each one has a test here:
 *  - `reconnect` was being counted as live confirmation, which cleared the unconfirmed marker and
 *    with it the notification suppression — so an ongoing, non-dismissable 「运行中」 card appeared
 *    for a run that had already ended;
 *  - every event refreshed `lastEventAt`, including a `session.info` carrying no `running` bit,
 *    so the self-heal's own probes kept pushing its silence threshold out of reach;
 *  - the self-heal's transcript check cannot discriminate after a cold start, because the local
 *    message list was itself loaded from REST — hence the tail-shape guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KilledMidRunColdStartTest {

    private class FakePhaseStore(private val stored: List<SessionPhaseRecord>) : SessionPhaseSnapshot {
        override suspend fun read(): List<SessionPhaseRecord> = stored
        override suspend fun write(records: List<SessionPhaseRecord>) = Unit
    }

    private class Clock(var now: Long) : () -> Long {
        override fun invoke() = now
    }

    // Real wall-clock, because `applyEvent` stamps `lastEventAt` from System.currentTimeMillis()
    // rather than the injected clock: a fixture dated in the future makes every arriving event look
    // older than the restored record.
    private val start = System.currentTimeMillis()

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
        val chat: ChatRepository,
        val sessions: SessionRepository,
        val clock: Clock,
    )

    /**
     * A cold start with the run still claimed on disk, [silentFor] milliseconds after the last
     * event the dead process saw. `chat.resume` is left answering null on purpose: a non-blank
     * handle starts the 5s process poll, which never lets `advanceUntilIdle` drain, and nothing
     * here needs one.
     */
    private fun TestScope.coldStart(silentFor: Long, phase: SessionRunPhase = SessionRunPhase.THINKING): Fixture {
        val clock = Clock(start)
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        every { chat.connectionState } returns connection
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val sessions = mockk<SessionRepository>(relaxed = true)
        val phases = FakePhaseStore(
            listOf(
                SessionPhaseRecord(
                    sessionId = "s1",
                    profile = "personal",
                    phase = phase.name,
                    occurredAt = start - silentFor,
                    lastEventAt = start - silentFor,
                    runStartedAt = start - silentFor,
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(
            chatRepository = chat,
            appScope = scope,
            profiles = profiles,
            phaseStore = phases,
            sessionRepository = sessions,
            clock = clock,
        )
        return Fixture(store, events, connection, chat, sessions, clock)
    }

    /** Restore, then reconnect — which is where the disk claim gets re-asserted as THINKING. */
    private fun TestScope.restoredAndReconnected(silentFor: Long): Fixture {
        val f = coldStart(silentFor)
        advanceUntilIdle()
        f.connection.value = ConnectionState.Connected
        advanceUntilIdle()
        return f
    }

    private fun history(vararg turns: Pair<Role, String>): List<ChatMessage> =
        turns.mapIndexed { i, (role, text) -> ChatMessage("h-$i", role, text) }

    @Test fun aRunThatFinishedWhileTheProcessWasDeadSettlesFromTheTranscript() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 90_000L)
        val key = f.store.key("s1", "personal")
        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)

        // What the Mac actually holds: the prompt and the answer it produced while the app was gone.
        coEvery { f.sessions.history(any(), any(), any()) } returns
            history(Role.USER to "查一下王志飞的入职时间", Role.ASSISTANT to "入职日期：2020 年 4 月 13 日。")

        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
        assertEquals(false, f.store.runtimes.value.getValue(key).chat.isGenerating)
    }

    /**
     * The other half, and the reason the tail shape is what decides. After a cold start the local
     * message list came from REST too, so "the transcript holds an assistant body at least as long
     * as ours" is true whatever the run is doing — here it is the PREVIOUS turn's answer. Upstream
     * persists the user turn at submit and the assistant turn at completion, so a transcript
     * ending on a user turn means the run is still going, and this must not touch it.
     *
     * Silent for four minutes on purpose: past the ordinary three-minute stale threshold too, so
     * the pre-fix path would have settled this as well and the tail guard is the only thing being
     * asserted here.
     */
    @Test fun aRunThatIsStillGoingIsLeftAloneEvenThoughAnOlderAnswerIsPersisted() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 4 * 60_000L)
        val key = f.store.key("s1", "personal")

        coEvery { f.sessions.history(any(), any(), any()) } returns history(
            Role.USER to "第一个问题",
            Role.ASSISTANT to "上一轮的完整答案，比本地持有的任何东西都长。",
            Role.USER to "第二个问题",
        )

        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
    }

    /** Below the leash, the claim is still given the benefit of the doubt. */
    @Test fun aClaimThatHasOnlyJustGoneQuietIsNotSettled() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 5_000L)
        val key = f.store.key("s1", "personal")

        coEvery { f.sessions.history(any(), any(), any()) } returns
            history(Role.USER to "问题", Role.ASSISTANT to "答案")

        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
    }

    /**
     * `reconnect` repeats the disk snapshot because `session.access` could not be read; it adds no
     * evidence. Counting it as confirmation is what let the notification coordinator publish an
     * ongoing 「运行中」 card the user could not swipe away, and held the keep-alive tier up behind it.
     */
    @Test fun reconnectDoesNotConfirmADiskClaim() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 90_000L)
        val key = f.store.key("s1", "personal")

        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
        assertTrue(
            "a reconnect that re-asserted the snapshot must leave the runtime unconfirmed",
            key in f.store.restoredKeys.value,
        )
    }

    /** And a real event does confirm it, so the suppression is not permanent. */
    @Test fun aRealEventConfirmsADiskClaim() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 90_000L)
        val key = f.store.key("s1", "personal")

        f.events.emit(
            ServerEvent(
                type = "message.delta",
                sessionId = "s1",
                payload = buildJsonObject { put("session_id", "s1"); put("text", "继" ) },
            ),
        )
        runCurrent()

        assertTrue(key !in f.store.restoredKeys.value)
    }

    /**
     * A `session.info` with no `running` bit changes nothing — `logTransition` does not even print
     * for it — so it must not postpone the watchdog. It used to, and since a probe's own
     * `session.resume` draws one, the self-heal kept resetting the very clock it was waiting on.
     */
    @Test fun anEventThatSaysNothingDoesNotPostponeTheWatchdog() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 90_000L)
        val key = f.store.key("s1", "personal")
        val before = f.store.runtimes.value.getValue(key).lastEventAt

        f.events.emit(
            ServerEvent(
                type = "session.info",
                sessionId = "s1",
                payload = buildJsonObject { put("session_id", "s1") },
            ),
        )
        runCurrent()

        assertEquals(before, f.store.runtimes.value.getValue(key).lastEventAt)
        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
    }

    /** …while a `session.info` that does carry the bit is still credited. */
    @Test fun anEventThatCarriesTheRunningBitDoesPostponeTheWatchdog() = runTest(timeout = 20.seconds) {
        val f = restoredAndReconnected(silentFor = 90_000L)
        val key = f.store.key("s1", "personal")
        val before = f.store.runtimes.value.getValue(key).lastEventAt

        f.events.emit(
            ServerEvent(
                type = "session.info",
                sessionId = "s1",
                payload = buildJsonObject { put("session_id", "s1"); put("running", true) },
            ),
        )
        runCurrent()

        assertTrue(f.store.runtimes.value.getValue(key).lastEventAt > before)
    }
}
