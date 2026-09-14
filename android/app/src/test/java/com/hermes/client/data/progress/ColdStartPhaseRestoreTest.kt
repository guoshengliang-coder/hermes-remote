package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.PersistedClarify
import com.hermes.client.data.repository.PersistedQuestion
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionPhaseRecord
import com.hermes.client.data.repository.SessionPhaseSnapshot
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.sessions.sessionStatusLine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A cold start must not erase what the user was last told (HG-31).
 *
 * Reported 2026-09-12: with one conversation showing 「已完成」 and another showing
 * 「等待你的回答」, killing the app and starting it again left both rows with no third line at all,
 * dropped the 「需要你处理」 group, and left the waiting conversation with no options to answer.
 * The unread dot on the very same row survived, because [com.hermes.client.data.repository.SessionReadStore]
 * persists it — one field of a row outliving the process while the field explaining it did not.
 *
 * These tests pin the restore AND its limits: what comes back verbatim, what comes back downgraded,
 * what is refused, and what may never be resurrected from disk at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartPhaseRestoreTest {

    private class FakePhaseStore(
        private val stored: List<SessionPhaseRecord> = emptyList(),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : SessionPhaseSnapshot {
        val writes = mutableListOf<List<SessionPhaseRecord>>()
        override suspend fun read(): List<SessionPhaseRecord> {
            gate?.await()
            return stored
        }
        override suspend fun write(records: List<SessionPhaseRecord>) { writes += records }
    }

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
        val chat: ChatRepository,
        val phases: FakePhaseStore,
    )

    private val now = 1_800_000_000_000L

    private fun TestScope.fixture(
        phases: FakePhaseStore,
        connected: Boolean = true,
        clock: () -> Long = { now },
    ): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        val connection = MutableStateFlow<ConnectionState>(
            if (connected) ConnectionState.Connected else ConnectionState.Disconnected,
        )
        every { chat.connectionState } returns connection
        // Deliberately left to the relaxed mock's null: a non-blank handle would start
        // scheduleProcessPolling, whose 5s loop runs for as long as the runtime has active work
        // and therefore never lets advanceUntilIdle() drain. Nothing here needs a live handle —
        // the assertions are about whether resume was ASKED for, not what it answered.
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            SessionRuntimeStore(
                chatRepository = chat,
                appScope = scope,
                profiles = profiles,
                phaseStore = phases,
                clock = clock,
            ),
            events, connection, chat, phases,
        )
    }

    private fun record(
        id: String,
        phase: SessionRunPhase,
        lastEventAt: Long = 0L,
        deviceId: String? = null,
        clarify: PersistedClarify? = null,
    ) = SessionPhaseRecord(
        sessionId = id,
        profile = "personal",
        deviceId = deviceId,
        phase = phase.name,
        lastEventAt = if (lastEventAt == 0L) now else lastEventAt,
        occurredAt = if (lastEventAt == 0L) now else lastEventAt,
        clarify = clarify,
    )

    private fun clarifyRecord() = PersistedClarify(
        requestId = "clr-1",
        questions = listOf(
            PersistedQuestion("q0", "要用哪种发布方式？", listOf("滚动发布 (Recommended)", "蓝绿切换"), false),
        ),
        lockedAnswers = emptyMap(),
    )

    // ---- what must come back ------------------------------------------------------------------

    @Test fun aCompletedVerdictSurvivesProcessDeath() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s1", SessionRunPhase.COMPLETED_UNREAD)))
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(store.key("s1", "personal"))
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, runtime.phase)
        assertEquals("已完成", sessionStatusLine(runtime, AppLanguage.ZH))
    }

    @Test fun aPendingClarifyComesBackWithItsWaitingPhaseAndCard() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(
            listOf(record("s2", SessionRunPhase.WAITING_CLARIFICATION, clarify = clarifyRecord())),
        )
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(store.key("s2", "personal"))
        assertEquals(SessionRunPhase.WAITING_CLARIFICATION, runtime.phase)
        val card = runtime.chat.pendingClarify
        assertNotNull("the options the user was asked to choose from must come back", card)
        assertEquals("clr-1", card!!.requestId)
        assertEquals(listOf("滚动发布 (Recommended)", "蓝绿切换"), card.currentQuestion?.choices)
        assertEquals("等待你的回答", sessionStatusLine(runtime, AppLanguage.ZH))
    }

    @Test fun aWaitingAttentionSurvivesBecauseNothingElseCanReportItAgain() = runTest(timeout = 20.seconds) {
        // The Relay inbox cursor only moves forward, so an already-delivered run.waiting never
        // replays. Not restoring it means losing it for good.
        val phases = FakePhaseStore(listOf(record("s3", SessionRunPhase.WAITING_ATTENTION)))
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.WAITING_ATTENTION,
            store.runtimes.value.getValue(store.key("s3", "personal")).phase,
        )
    }

    // ---- what must NOT come back verbatim -----------------------------------------------------

    @Test fun aPendingApprovalComesBackWithoutItsCard() = runTest(timeout = 20.seconds) {
        // approval.respond carries no request id and returns nothing, so a card rebuilt from disk
        // could approve a command the user never saw. The row may say "waiting"; the card may not
        // come back. The record never stores one, and nothing may invent one.
        val phases = FakePhaseStore(listOf(record("s4", SessionRunPhase.WAITING_APPROVAL)))
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(store.key("s4", "personal"))
        assertEquals(SessionRunPhase.WAITING_APPROVAL, runtime.phase)
        assertNull("an approval must never be resurrected from disk", runtime.chat.pendingApproval)
        assertEquals("等待你的确认", sessionStatusLine(runtime, AppLanguage.ZH))
    }

    @Test fun aRunningPhaseComesBackAsReconnectingAndResumesOnConnect() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s5", SessionRunPhase.STREAMING)))
        val (store, _, connection, chat, _) = fixture(phases, connected = false)
        advanceUntilIdle()

        val key = store.key("s5", "personal")
        // Without a socket there is nothing arriving; saying 「正在输出…」 would be a lie, and the
        // bubble it describes was never persisted.
        assertEquals(SessionRunPhase.RECONNECTING, store.runtimes.value.getValue(key).phase)

        connection.value = ConnectionState.Connected
        advanceUntilIdle()
        coVerify { chat.resume("s5", "personal") }
        assertEquals(SessionRunPhase.STREAMING, store.runtimes.value.getValue(key).phase)
    }

    @Test fun aLiveEventThatBeatsTheSeedWins() = runTest(timeout = 20.seconds) {
        val gate = CompletableDeferred<Unit>()
        val phases = FakePhaseStore(listOf(record("s6", SessionRunPhase.COMPLETED_UNREAD)), gate)
        val (store, events, _, _, _) = fixture(phases)
        val key = store.register("s6", "personal")

        events.emit(ServerEvent("message.start", "s6", buildJsonObject {
            put("session_id", "s6"); put("message_id", "agent")
        }))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "a stale snapshot must never overwrite the run this process is watching",
            SessionRunPhase.THINKING,
            store.runtimes.value.getValue(key).phase,
        )
    }

    @Test fun aRecordOlderThanTheHardCapIsNotRestored() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(
            listOf(record("s7", SessionRunPhase.WAITING_CLARIFICATION, lastEventAt = now - 31 * 60_000L, clarify = clarifyRecord())),
        )
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        val runtime = store.runtimes.value[store.key("s7", "personal")]
        // Dropped, not marked interrupted: a cold start holds no evidence of how the run ended.
        assertNull("a long-dead wait must not be resurrected", runtime)
    }

    @Test fun aTerminalVerdictHasNoExpiry() = runTest(timeout = 20.seconds) {
        // It is a conclusion waiting to be seen, and markRead — the thing that clears it — is
        // itself persistent. Expiring the words while the unread dot lives on is the HG-31 defect.
        val phases = FakePhaseStore(
            listOf(record("s8", SessionRunPhase.COMPLETED_UNREAD, lastEventAt = now - 5L * 24 * 3_600_000L)),
        )
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.COMPLETED_UNREAD,
            store.runtimes.value.getValue(store.key("s8", "personal")).phase,
        )
    }

    @Test fun restoringATerminalRecordWithACardStripsTheCard() = runTest(timeout = 20.seconds) {
        // Seeding goes through updateRuntime precisely so normalized() still gets the last word.
        val phases = FakePhaseStore(
            listOf(record("s9", SessionRunPhase.COMPLETED_UNREAD, clarify = clarifyRecord())),
        )
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(store.key("s9", "personal"))
        assertNull("a card may not outlive the run it belongs to", runtime.chat.pendingClarify)
        assertFalse(runtime.chat.isGenerating)
    }

    // ---- writing ------------------------------------------------------------------------------

    @Test fun submittingIsNeverPersisted() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore()
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()
        val key = store.register("s10", "personal")
        store.beginPrompt(key, "重启一下网关")
        advanceUntilIdle()

        // A process killed mid-submit almost certainly never got the prompt out; "正在发送…"
        // restored from disk would spin forever because no event can resolve it.
        assertTrue(
            phases.writes.flatten().none { it.sessionId == "s10" && it.phase == SessionRunPhase.SUBMITTING.name },
        )
    }

    @Test fun aStreamOfDeltasDoesNotWriteOncePerDelta() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore()
        val (store, events, _, _, _) = fixture(phases)
        advanceUntilIdle()
        store.register("s11", "personal")
        events.emit(ServerEvent("message.start", "s11", buildJsonObject {
            put("session_id", "s11"); put("message_id", "agent")
        }))
        advanceUntilIdle()
        val before = phases.writes.size
        repeat(50) {
            events.emit(ServerEvent("message.delta", "s11", buildJsonObject {
                put("session_id", "s11"); put("text", "chunk $it")
            }))
        }
        advanceUntilIdle()

        // The projection holds no text, so a stream leaves it unchanged and it is conflated away.
        assertTrue("50 deltas caused ${phases.writes.size - before} writes", phases.writes.size - before <= 2)
    }

    @Test fun markReadRemovesThePersistedVerdict() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s12", SessionRunPhase.COMPLETED_UNREAD)))
        val (store, _, _, _, _) = fixture(phases)
        advanceUntilIdle()
        val key = store.key("s12", "personal")
        store.markRead(key)
        advanceUntilIdle()

        assertTrue(
            "opening the conversation retires the verdict on disk too",
            phases.writes.last().none { it.sessionId == "s12" },
        )
    }

    // ---- reconciliation -----------------------------------------------------------------------

    @Test fun aRestoredRunIsProbedSoTheClaimGetsChecked() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(
            listOf(record("s13", SessionRunPhase.WAITING_CLARIFICATION, clarify = clarifyRecord())),
        )
        val (_, _, _, chat, _) = fixture(phases)
        advanceUntilIdle()

        // Until now probeActiveRuntimes ran against an empty map on every cold start.
        coVerify { chat.resume("s13", "personal") }
    }

    @Test fun aRestoredRuntimeIsUnconfirmedUntilLiveEvidenceArrives() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s14", SessionRunPhase.WAITING_APPROVAL)))
        val (store, events, _, _, _) = fixture(phases)
        advanceUntilIdle()
        val key = store.key("s14", "personal")
        // The row may say "waiting", but nothing has verified that claim in this process, so the
        // high-importance shade card stays suppressed.
        assertTrue(key in store.restoredKeys.value)

        events.emit(ServerEvent("message.delta", "s14", buildJsonObject {
            put("session_id", "s14"); put("text", "继续")
        }))
        advanceUntilIdle()
        assertFalse(key in store.restoredKeys.value)
    }
}
