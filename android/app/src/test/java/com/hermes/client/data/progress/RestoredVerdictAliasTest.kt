package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionPhaseRecord
import com.hermes.client.data.repository.SessionPhaseSnapshot
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * A restored terminal verdict must be retired by opening the conversation, even when it was filed
 * under a profile alias of that conversation (HG-137).
 *
 * Reported 2026-09-25: three conversations showed the green "已完成" dot on every cold start even
 * after being opened. The verdict is durable (HG-31, [com.hermes.client.data.repository.SessionPhaseStore])
 * and terminal verdicts never expire, so it is restored every time. It was written under a null
 * profile — a lifecycle completion folded while the process held no runtime for the session files
 * Hermes' default identity that way — while opening the chat registers the canonical "default"
 * label. `markRead` was strict-key, so it retired a different runtime: the alias stayed protected
 * from `pruneIdleRuntimes` as a terminal verdict, was written straight back, and returned on the
 * next cold start while the row (which looks a conversation up by id and device, never by profile)
 * kept rendering it.
 *
 * These tests pin the three halves of the fix: the restore key is canonical, opening retires the
 * verdict and its disk record, and retiring one conversation never touches a same-id conversation
 * in another profile or on another Mac.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredVerdictAliasTest {

    private class FakePhaseStore(
        private val stored: List<SessionPhaseRecord> = emptyList(),
    ) : SessionPhaseSnapshot {
        val writes = mutableListOf<List<SessionPhaseRecord>>()
        override suspend fun read(): List<SessionPhaseRecord> = stored
        override suspend fun write(records: List<SessionPhaseRecord>) { writes += records }
    }

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val phases: FakePhaseStore,
    )

    private val now = 1_800_000_000_000L

    private fun TestScope.fixture(phases: FakePhaseStore): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("default")
        return Fixture(
            SessionRuntimeStore(
                chatRepository = chat,
                appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
                profiles = profiles,
                phaseStore = phases,
            ),
            events, phases,
        )
    }

    private fun record(
        id: String,
        profile: String?,
        phase: SessionRunPhase,
        deviceId: String? = null,
    ) = SessionPhaseRecord(
        sessionId = id,
        profile = profile,
        deviceId = deviceId,
        phase = phase.name,
        lastEventAt = now,
        occurredAt = now,
    )

    private fun lifecycle(sessionId: String, profile: String?, deviceId: String) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "life-$sessionId-${profile ?: "null"}-$deviceId",
        deviceId = deviceId,
        profile = profile,
        runtimeSessionId = "runtime-$sessionId",
        storedSessionId = sessionId,
        event = "run.completed",
        state = "idle",
        occurredAt = Instant.ofEpochMilli(now).toString(),
    )

    private fun agentEvent(type: String, sessionId: String) = ServerEvent(
        type,
        sessionId,
        buildJsonObject { put("session_id", sessionId); put("message_id", "agent") },
    )

    @Test fun a_null_profile_record_is_restored_under_the_canonical_default_key() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s1", null, SessionRunPhase.COMPLETED_UNREAD)))
        val (store, _, _) = fixture(phases)
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.COMPLETED_UNREAD,
            store.runtimes.value.getValue(store.key("s1", "default")).phase,
        )
        assertNull(
            "the default identity must not be filed under a null-profile key",
            store.runtimes.value[store.key("s1", null)],
        )
    }

    @Test fun opening_clears_a_restored_verdict_and_its_disk_record() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore(listOf(record("s2", null, SessionRunPhase.COMPLETED_UNREAD)))
        val (store, _, _) = fixture(phases)
        advanceUntilIdle()

        store.markRead(store.key("s2", "default"))
        advanceUntilIdle()

        assertEquals(
            SessionRunPhase.IDLE,
            store.runtimes.value.getValue(store.key("s2", "default")).phase,
        )
        assertTrue(
            "opening the conversation retires the verdict on disk too",
            phases.writes.last().none { it.sessionId == "s2" },
        )
    }

    @Test fun a_verdict_filed_under_a_profile_alias_is_retired_by_opening_the_canonical_key() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore()
        val (store, events, _) = fixture(phases)
        advanceUntilIdle()

        // The runtime that carries the verdict is keyed with a null profile; the chat opens under
        // the canonical "default" label. Both name the same conversation (same read token).
        val aliasKey = store.register("s3", null)
        val canonicalKey = store.key("s3", "default")
        events.emit(agentEvent("message.start", "s3"))
        events.emit(ServerEvent("message.complete", "s3", buildJsonObject { put("session_id", "s3") }))
        advanceUntilIdle()
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(aliasKey).phase)

        store.markRead(canonicalKey)
        advanceUntilIdle()

        assertEquals(
            "opening the conversation must retire the alias as well as the canonical key",
            SessionRunPhase.IDLE,
            store.runtimes.value.getValue(aliasKey).phase,
        )
        assertTrue(
            "no terminal verdict may survive in this process for that conversation",
            store.runtimes.value.values.none { it.phase == SessionRunPhase.COMPLETED_UNREAD },
        )
    }

    @Test fun opening_one_conversation_leaves_another_profile_or_macs_same_id_alone() = runTest(timeout = 20.seconds) {
        val phases = FakePhaseStore()
        val (store, _, _) = fixture(phases)
        advanceUntilIdle()

        // Equal profile/session ids on two Macs are different conversations, and the same id in a
        // different profile is a different tenant. Neither may be retired by opening the other.
        store.register("s5", "personal", "macB")
        store.register("s5", "default", "macA")
        store.applyObservedLifecycle(lifecycle("s5", profile = "personal", deviceId = "macB"))
        store.applyObservedLifecycle(lifecycle("s5", profile = "default", deviceId = "macA"))
        advanceUntilIdle()

        val macA = store.key("s5", "default", "macA")
        val macB = store.key("s5", "personal", "macB")
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(macA).phase)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(macB).phase)

        store.markRead(macA)
        advanceUntilIdle()

        assertEquals(SessionRunPhase.IDLE, store.runtimes.value.getValue(macA).phase)
        assertEquals(
            "a same-id conversation in another profile or on another Mac must stay independent",
            SessionRunPhase.COMPLETED_UNREAD,
            store.runtimes.value.getValue(macB).phase,
        )
    }
}
