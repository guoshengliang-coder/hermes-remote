package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionReadStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unread badge may only be created by a turn that actually produced an answer.
 *
 * Hermes answers every `session.resume` with a `session.info{running}`, and the client resumes every
 * session on every reconnect, so an idle conversation receives that event constantly. Folding it as
 * a completion put an unread dot on conversations that had gained nothing (HG-62):
 *
 *   23:23:54.648 s=20260914_094040_ce65ba IDLE→COMPLETED_UNREAD cause=event:session.info
 *   23:23:55.032 reconcile 20260914_094040_ce65ba: 5 messages, accepted=true
 *
 * The badge is persistent, so every cold start restored it — which is why the report describes it as
 * a restart problem when restarting is only when it becomes visible. The phone in that log was
 * reconnecting every five seconds (HG-65), which is why it happened constantly rather than rarely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleSessionUnreadRegressionTest {
    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        return Fixture(
            SessionRuntimeStore(
                chatRepository = chat,
                appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
                profiles = profiles,
            ),
            events,
        )
    }

    private fun sessionInfo(sessionId: String, running: Boolean) = ServerEvent(
        "session.info",
        sessionId,
        buildJsonObject { put("session_id", sessionId); put("running", running) },
    )

    @Test fun an_idle_conversation_is_not_marked_unread_by_a_resume_snapshot() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("s-old", "personal")

        // What every reconnect does to every session, including ones nobody has touched in days.
        fixture.events.emit(sessionInfo("s-old", running = false))
        advanceUntilIdle()

        val runtime = fixture.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.IDLE, runtime.phase)
        assertFalse(
            "a conversation that gained nothing must not gain a badge",
            SessionReadStore.token(key.profile, key.sessionId, key.deviceId) in fixture.store.unreadTokens.value,
        )
    }

    @Test fun repeated_resume_snapshots_never_accumulate_a_badge() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("s-old", "personal")

        repeat(20) { fixture.events.emit(sessionInfo("s-old", running = false)) }
        advanceUntilIdle()

        assertEquals(SessionRunPhase.IDLE, fixture.store.runtimes.value.getValue(key).phase)
        assertTrue(fixture.store.unreadTokens.value.isEmpty())
    }

    /**
     * The other half of the rule, and the behaviour that must not regress: when a turn really was in
     * flight, `session.info{running:false}` is still the authoritative "it finished" and still earns
     * the badge. This is the only signal that retires a run whose `message.complete` was lost with
     * the socket, which is exactly what HG-61's fourteen-minute stall came down to.
     */
    @Test fun a_run_that_was_in_flight_still_completes_and_still_earns_the_badge() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("s-live", "personal")
        fixture.store.beginPrompt(key, "跑一个任务")
        fixture.events.emit(
            ServerEvent(
                "message.start",
                "s-live",
                buildJsonObject { put("session_id", "s-live"); put("message_id", "agent") },
            ),
        )
        advanceUntilIdle()
        assertTrue(fixture.store.runtimes.value.getValue(key).phase.isActive)

        fixture.events.emit(sessionInfo("s-live", running = false))
        advanceUntilIdle()

        val runtime = fixture.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, runtime.phase)
        assertTrue(
            SessionReadStore.token(key.profile, key.sessionId, key.deviceId) in fixture.store.unreadTokens.value,
        )
    }

    /**
     * The sequence the user actually lives through: read it, then reconnect. Before the fix the
     * reconnect handed the badge straight back.
     */
    @Test fun reading_a_conversation_then_reconnecting_leaves_it_read() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("s-read", "personal")
        fixture.store.beginPrompt(key, "问一句")
        fixture.events.emit(
            ServerEvent(
                "message.start",
                "s-read",
                buildJsonObject { put("session_id", "s-read"); put("message_id", "agent") },
            ),
        )
        fixture.events.emit(sessionInfo("s-read", running = false))
        advanceUntilIdle()
        fixture.store.markRead(key)
        assertTrue(fixture.store.unreadTokens.value.isEmpty())

        // Reconnect: every session gets resumed, every resume answers with this.
        repeat(3) { fixture.events.emit(sessionInfo("s-read", running = false)) }
        advanceUntilIdle()

        assertTrue("it stays read", fixture.store.unreadTokens.value.isEmpty())
        assertEquals(SessionRunPhase.IDLE, fixture.store.runtimes.value.getValue(key).phase)
    }
}
