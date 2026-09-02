package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.ui.chat.ApprovalRequest
import com.hermes.client.ui.chat.ClarifyQuestion
import com.hermes.client.ui.chat.ClarifyRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fields SessionRuntime carries purely for the one-card-per-session notification projection. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeNotificationStateTest {
    private fun event(type: String, sessionId: String, vararg fields: Pair<String, Any?>) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            if (type == "message.start") put("message_id", "agent")
            fields.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> Unit
                }
            }
        },
    )

    private fun todoComplete(sessionId: String, vararg statuses: String) = ServerEvent(
        type = "tool.complete",
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            put("name", "todo")
            put("tool_id", "t1")
            putJsonArray("todos") {
                statuses.forEach { status -> addJsonObject { put("content", "x"); put("status", status) } }
            }
        },
    )

    private fun lifecycle(kind: String, sessionId: String, occurredAt: String, title: String? = "Observed title") = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "event-$kind-$occurredAt",
        deviceId = "mac-mini",
        profile = "personal",
        runtimeSessionId = "runtime-$sessionId",
        storedSessionId = sessionId,
        event = kind,
        state = if (kind == "run.completed") "idle" else "working",
        occurredAt = occurredAt,
        title = title,
    )

    private class Fixture(val store: SessionRuntimeStore, val events: MutableSharedFlow<ServerEvent>)

    private fun TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(SessionRuntimeStore(chatRepository = chat, appScope = scope, profiles = profiles), events)
    }

    @Test fun a_run_start_stamps_its_start_time_and_resets_todo_counts() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        val before = System.currentTimeMillis()
        f.events.emit(event("message.start", "s1"))
        f.events.emit(todoComplete("s1", "completed", "pending", "cancelled"))
        runCurrent()

        val runtime = f.store.runtimes.value.getValue(key)
        assertNotNull(runtime.runStartedAt)
        assertTrue(runtime.runStartedAt!! >= before)
        assertEquals(1, runtime.todoDone)
        assertEquals(2, runtime.todoTotal)
        assertEquals(0L, runtime.lastTerminalAt)

        f.events.emit(event("message.complete", "s1"))
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        val next = f.store.runtimes.value.getValue(key)
        assertEquals(0, next.todoDone)
        assertEquals(0, next.todoTotal)
    }

    @Test fun terminal_events_stamp_the_terminal_and_occurred_times() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        val started = f.store.runtimes.value.getValue(key).occurredAt
        assertTrue(started > 0L)

        f.events.emit(event("message.complete", "s1"))
        runCurrent()
        val done = f.store.runtimes.value.getValue(key)
        assertTrue(done.lastTerminalAt > 0L)
        assertTrue(done.occurredAt >= started)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, done.phase)
    }

    @Test fun session_info_idle_counts_as_a_terminal_transition() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("session.info", "s1", "running" to false))
        runCurrent()
        assertTrue(f.store.runtimes.value.getValue(key).lastTerminalAt > 0L)
    }

    @Test fun an_inbox_completion_replaying_a_delivered_socket_completion_is_ignored() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.complete", "s1"))
        runCurrent()
        f.store.markRead(key)
        assertEquals(SessionRunPhase.IDLE, f.store.runtimes.value.getValue(key).phase)

        // The Connector observed the same idle transition a second later.
        f.store.applyObservedLifecycle(lifecycle("run.completed", "s1", java.time.Instant.now().toString()))

        val runtime = f.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.IDLE, runtime.phase)
        assertFalse("personal/s1" in f.store.unreadTokens.value)
        assertEquals("Observed title", runtime.title)
    }

    @Test fun an_inbox_completion_of_a_later_run_still_applies() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.complete", "s1"))
        runCurrent()
        f.store.markRead(key)

        // The Mac started another turn; the observer reports start then completion.
        f.store.applyObservedLifecycle(lifecycle("run.started", "s1", java.time.Instant.now().toString()))
        f.store.applyObservedLifecycle(lifecycle("run.completed", "s1", java.time.Instant.now().toString()))

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
        assertTrue("personal/s1" in f.store.unreadTokens.value)
    }

    @Test fun an_inbox_completion_overrides_a_local_failure_verdict() = runTest {
        // The send RPC timed out locally (markFailed) but Hermes ran the prompt: the observer's
        // completion is the truth and must not be treated as a replay of the failure.
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "run it")
        f.store.markFailed(key, f.store.runtimes.value.getValue(key).chat)
        assertEquals(SessionRunPhase.FAILED, f.store.runtimes.value.getValue(key).phase)

        f.store.applyObservedLifecycle(lifecycle("run.completed", "s1", java.time.Instant.now().toString()))

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun an_inbox_interruption_replaying_a_delivered_completion_is_ignored() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.complete", "s1"))
        runCurrent()

        f.store.applyObservedLifecycle(lifecycle("run.unknown", "s1", java.time.Instant.now().toString()))

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun a_visible_chat_only_counts_as_read_while_the_app_is_in_the_foreground() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.setVisible(key, true)
        f.store.setAppInForeground(false)
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.complete", "s1"))
        runCurrent()
        // Phone locked with the chat still composed: the completion is unread and gets a card.
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
        assertTrue("personal/s1" in f.store.unreadTokens.value)

        // Unlocking with that chat in front means the user is now looking at the result.
        f.store.setAppInForeground(true)
        assertEquals(SessionRunPhase.IDLE, f.store.runtimes.value.getValue(key).phase)
        assertFalse("personal/s1" in f.store.unreadTokens.value)

        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.complete", "s1"))
        runCurrent()
        assertEquals(SessionRunPhase.IDLE, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun an_inbox_completion_while_the_socket_still_shows_the_run_active_applies() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.events.emit(event("message.start", "s1"))
        runCurrent()

        f.store.applyObservedLifecycle(lifecycle("run.completed", "s1", java.time.Instant.now().toString()))

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun observed_run_start_uses_the_event_time_as_the_run_start() = runTest {
        val f = fixture()
        f.store.applyObservedLifecycle(lifecycle("run.started", "ext", "2026-08-31T08:30:00.000Z"))
        val runtime = f.store.runtimes.value.getValue(SessionRuntimeKey("personal", "ext"))
        assertEquals(java.time.Instant.parse("2026-08-31T08:30:00.000Z").toEpochMilli(), runtime.runStartedAt)
        assertEquals(runtime.runStartedAt, runtime.occurredAt)
        assertEquals("Observed title", runtime.title)
    }

    @Test fun setTitle_ignores_blank_titles_and_keeps_the_last_real_one() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.setTitle(key, "  ")
        assertNull(f.store.runtimes.value.getValue(key).title)
        f.store.setTitle(key, "Fix build")
        f.store.setTitle(key, null)
        assertEquals("Fix build", f.store.runtimes.value.getValue(key).title)
    }

    @Test fun visibleSessions_mirrors_setVisible() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        assertTrue(f.store.visibleSessions.value.isEmpty())
        f.store.setVisible(key, true)
        assertEquals(setOf(key), f.store.visibleSessions.value)
        f.store.setVisible(key, false)
        assertTrue(f.store.visibleSessions.value.isEmpty())
    }

    @Test fun clearing_a_pending_approval_from_the_shade_updates_the_chat_state() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.updateChat(key) { it.copy(pendingApproval = ApprovalRequest("ls", "", emptyList(), true)) }
        f.store.clearPendingApproval(key)
        assertNull(f.store.runtimes.value.getValue(key).chat.pendingApproval)
    }

    @Test fun locking_a_clarify_answer_advances_a_batch_and_clears_a_single_question() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        val batch = ClarifyRequest("req", listOf(ClarifyQuestion("q1", "A?"), ClarifyQuestion("q2", "B?")))
        f.store.updateChat(key) { it.copy(pendingClarify = batch) }

        f.store.lockClarifyAnswer(key, "q1", "yes")
        val advanced = f.store.runtimes.value.getValue(key).chat.pendingClarify!!
        assertEquals("q2", advanced.currentQuestion!!.qid)

        f.store.lockClarifyAnswer(key, "q2", "no")
        assertNull(f.store.runtimes.value.getValue(key).chat.pendingClarify)

        f.store.updateChat(key) { it.copy(pendingClarify = ClarifyRequest("req2", listOf(ClarifyQuestion("", "C?")))) }
        f.store.lockClarifyAnswer(key, null, "sure")
        assertNull(f.store.runtimes.value.getValue(key).chat.pendingClarify)
    }
}
