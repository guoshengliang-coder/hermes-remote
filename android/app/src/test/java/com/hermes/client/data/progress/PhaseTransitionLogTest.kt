package com.hermes.client.data.progress

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * With diagnostics on, every visible state change writes one `[phase]` line with its cause, a
 * refused reconcile says why, and an observed lifecycle event says how late it was. With
 * diagnostics off none of it runs — not even the string.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhaseTransitionLogTest {
    @Before fun setUp() { DebugLog.detachStore(); DebugLog.setEnabled(true); DebugLog.clear() }
    @After fun tearDown() { DebugLog.setEnabled(false); DebugLog.clear() }

    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type, sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId); text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private fun kotlinx.coroutines.test.TestScope.store(sessions: SessionRepository? = null): Pair<SessionRuntimeStore, MutableSharedFlow<ServerEvent>> {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        return SessionRuntimeStore(chat, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), profiles, sessionRepository = sessions) to events
    }

    private fun lines(category: String) = DebugLog.entries.value.filter { it.category == category }.map { it.message }

    @Test fun everyVisibleStateChangeIsOneLineWithItsCause() = runTest {
        val (store, events) = store()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "跑起来")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "一"))
        events.emit(event("message.delta", "s1", "二"))
        events.emit(event("message.complete", "s1", "一二"))
        runCurrent()

        val phase = lines("phase")
        assertTrue(phase.toString(), phase.any { it.contains("IDLE→SUBMITTING") && it.contains("cause=prompt") })
        assertTrue(phase.toString(), phase.any { it.contains("SUBMITTING→THINKING") && it.contains("streaming=1") && it.contains("cause=event:message.start") })
        assertTrue(phase.toString(), phase.any { it.contains("THINKING→STREAMING") && it.contains("cause=event:message.delta") })
        assertTrue(phase.toString(), phase.any { it.contains("→COMPLETED_UNREAD") && it.contains("gen=false") && it.contains("streaming=0") && it.contains("cause=event:message.complete") })
        // The second delta changed only text: no line for it.
        assertEquals(1, phase.count { it.contains("cause=event:message.delta") })
    }

    @Test fun aRefusedReconcileSaysWhy() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(ChatMessage("h-0", Role.USER, "第一问"))
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "第一问")
        events.emit(event("message.complete", "s1", "第一答"))
        advanceUntilIdle()

        assertTrue(lines("history").toString(), lines("history").any { it.contains("rejected: assistantTurns 0<1") })
    }

    @Test fun anObservedLifecycleEventSaysHowLateItWas() = runTest {
        val (store, _) = store()
        store.register("s1", "personal")
        store.applyObservedLifecycle(LifecycleEventDto(
            type = "session.lifecycle", version = 1, eventId = "e1", deviceId = "mac-mini", profile = "personal",
            runtimeSessionId = "r1", storedSessionId = "s1", event = "run.completed", state = "idle",
            occurredAt = "2026-09-05T02:31:09.000Z",
        ))
        val line = lines("lifecycle").single { it.startsWith("run.completed") }
        assertTrue(line, Regex("late=\\d+s").containsMatchIn(line))
    }

    @Test fun nothingIsBuiltWhenDiagnosticsAreOff() {
        DebugLog.setEnabled(false)
        var built = 0
        DebugLog.log("phase") { built++; "never" }
        assertEquals(0, built)
        assertTrue(DebugLog.entries.value.isEmpty())
        DebugLog.setEnabled(true)
        // Enabling now writes a session header (build, pid, device); this test is about the lazy
        // overload, so drop it rather than assert around it.
        DebugLog.clear()
        DebugLog.log("phase") { built++; "once" }
        assertEquals(1, built)
        assertEquals("once", DebugLog.entries.value.single().message)
    }
}
