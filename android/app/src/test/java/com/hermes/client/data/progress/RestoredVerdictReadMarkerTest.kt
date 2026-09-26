package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionPhaseRecord
import com.hermes.client.data.repository.SessionPhaseSnapshot
import com.hermes.client.data.repository.SessionReadMarkers
import com.hermes.client.data.repository.SessionReadStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A terminal verdict restored from disk must only come back while it is still unread (HG-138).
 *
 * Reported 2026-09-26: four conversations showed 「已完成」 on every cold start although they had
 * been opened long before — one of them had been opened four times across two days. The verdict is
 * durable ([com.hermes.client.data.repository.SessionPhaseStore], HG-31) and terminal verdicts never
 * expire, so it is restored every time. Before HG-137 `markRead` retired the runtime under a key
 * that did not match the one the verdict was filed under, so the *unread marker* was cleared while
 * the verdict, written straight back, survived — a 「已完成」 with the dot it explains already gone.
 * HG-137 stops that happening again, but the leftovers it already produced (and any other alias that
 * separates the verdict from its marker) keep resurrecting through the restore. The marker is the
 * authoritative "not yet seen" flag and `docs/DESIGN.md` §5.2 says 已完成 shows only while unread, so
 * a stored verdict whose token is no longer in the read store is retired, not restored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredVerdictReadMarkerTest {

    private class FakePhaseStore(
        var stored: List<SessionPhaseRecord> = emptyList(),
    ) : SessionPhaseSnapshot {
        val writes = mutableListOf<List<SessionPhaseRecord>>()
        override suspend fun read(): List<SessionPhaseRecord> = stored
        override suspend fun write(records: List<SessionPhaseRecord>) { writes += records }
    }

    private class FakeReadMarkers(initial: Set<String> = emptySet()) : SessionReadMarkers {
        private val state = MutableStateFlow(initial)
        override val unread: Flow<Set<String>> = state
        override suspend fun markUnread(token: String) { state.value = state.value + token }
        override suspend fun markRead(token: String) { state.value = state.value - token }
    }

    private data class Fixture(
        val store: SessionRuntimeStore,
        val phases: FakePhaseStore,
    )

    private val now = 1_800_000_000_000L

    private fun TestScope.fixture(
        unread: Set<String> = emptySet(),
        records: List<SessionPhaseRecord>,
    ): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val phases = FakePhaseStore(records)
        val store = SessionRuntimeStore(
            chatRepository = chat,
            appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            profiles = profiles,
            readStore = FakeReadMarkers(unread),
            phaseStore = phases,
            clock = { now },
        )
        return Fixture(store, phases)
    }

    private fun record(
        id: String,
        phase: SessionRunPhase,
        profile: String? = "personal",
        deviceId: String? = null,
    ) = SessionPhaseRecord(
        sessionId = id,
        profile = profile,
        deviceId = deviceId,
        phase = phase.name,
        lastEventAt = now,
        occurredAt = now,
    )

    @Test fun a_verdict_whose_unread_marker_survives_is_restored() = runTest(timeout = 20.seconds) {
        val token = SessionReadStore.token("personal", "s1")
        val (store, phases) = fixture(unread = setOf(token), records = listOf(record("s1", SessionRunPhase.COMPLETED_UNREAD)))
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.COMPLETED_UNREAD,
            store.runtimes.value.getValue(store.key("s1", "personal")).phase,
        )
        assertTrue(store.restoredKeys.value.contains(store.key("s1", "personal")))
        assertTrue(phases.writes.last().any { it.sessionId == "s1" })
    }

    @Test fun a_verdict_whose_unread_marker_is_gone_is_retired_not_restored() = runTest(timeout = 20.seconds) {
        val (store, phases) = fixture(
            unread = emptySet(),
            records = listOf(record("s1", SessionRunPhase.COMPLETED_UNREAD)),
        )
        advanceUntilIdle()

        assertNull(
            "a verdict the dot already dropped must not come back on cold start",
            store.runtimes.value[store.key("s1", "personal")],
        )
        assertFalse(store.restoredKeys.value.contains(store.key("s1", "personal")))
        assertTrue(
            "the retired record must not be written back",
            phases.writes.last().none { it.sessionId == "s1" },
        )
    }

    @Test fun a_waiting_record_is_never_treated_as_already_seen() = runTest(timeout = 20.seconds) {
        val (store, _) = fixture(
            unread = emptySet(),
            records = listOf(record("s2", SessionRunPhase.WAITING_ATTENTION)),
        )
        advanceUntilIdle()

        assertEquals(
            "a waiting run carries no unread marker and must still restore",
            SessionRunPhase.WAITING_ATTENTION,
            store.runtimes.value.getValue(store.key("s2", "personal")).phase,
        )
    }

    @Test fun a_marker_for_another_mac_is_not_this_conversations_marker() = runTest(timeout = 20.seconds) {
        // The read token encodes the Mac route, so a verdict for macB is not cleared by macA's
        // marker. Same stored session id, different conversations (docs/ACCOUNT_MODE_TEST_PLAN.md).
        val macAToken = SessionReadStore.token("personal", "s3", "macA")
        val (store, _) = fixture(
            unread = setOf(macAToken),
            records = listOf(record("s3", SessionRunPhase.COMPLETED_UNREAD, deviceId = "macB")),
        )
        advanceUntilIdle()

        assertNull(
            "a same-id conversation on another Mac keeps its own unread state",
            store.runtimes.value[store.key("s3", "personal", "macB")],
        )
    }

    @Test fun a_null_profile_verdict_is_checked_against_the_canonical_token() = runTest(timeout = 20.seconds) {
        // Legacy records filed Hermes' default identity as a null profile; the read token normalizes
        // that to "default", so the marker still decides.
        val token = SessionReadStore.token("default", "s4")
        val (store, _) = fixture(
            unread = setOf(token),
            records = listOf(record("s4", SessionRunPhase.COMPLETED_UNREAD, profile = null)),
        )
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.COMPLETED_UNREAD,
            store.runtimes.value.getValue(store.key("s4", "default")).phase,
        )
    }
}
