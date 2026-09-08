package com.hermes.client.data.progress

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Events for a session id nothing is aliased to yet used to be dropped (`unmatched … awaiting
 * history reconciliation`, observed on the emulator 2026-09-05). A dropped message.complete left
 * the turn open until the lifecycle inbox caught up minutes later. They are now held for a
 * bounded time and replayed, in order, as soon as an alias for that id appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnmatchedEventBufferTest {
    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private fun lifecycle(kind: String, stored: String, runtime: String) = LifecycleEventDto(
        type = "session.lifecycle", version = 1, eventId = "e-$kind-$stored", deviceId = "mac-mini",
        profile = "personal", runtimeSessionId = runtime, storedSessionId = stored, event = kind,
        state = if (kind == "run.completed") "idle" else "working", occurredAt = "2026-09-05T02:31:09.000Z",
    )

    private class Clock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke() = now
    }

    private data class Fixture(val store: SessionRuntimeStore, val events: MutableSharedFlow<ServerEvent>, val clock: Clock)

    private fun kotlinx.coroutines.test.TestScope.fixture(sessions: SessionRepository? = null): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val clock = Clock()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(SessionRuntimeStore(chat, scope, profiles, sessionRepository = sessions, clock = clock), events, clock)
    }

    @Test fun deltasThatArriveBeforeTheHandleIsKnownAppearOnceItIsBound() = runTest {
        val (store, events, _) = fixture()
        // The phone has the stored conversation open but has not resumed its live handle yet.
        val key = store.register("stored-1", "personal")

        events.emit(event("message.start", "handle-9"))
        events.emit(event("reasoning.delta", "handle-9", "正在分析"))
        events.emit(event("message.delta", "handle-9", "部分结果"))
        advanceUntilIdle()
        // message.start on an unknown id still registers a runtime (unchanged); the deltas that
        // followed it under the same id go to that runtime — nothing is lost either way.

        store.bindLiveHandle(key, "handle-9")
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        val visible = store.runtimes.value.values.flatMap { it.chat.messages }.filter { it.role == Role.ASSISTANT }
        assertTrue("思考内容不得丢失", visible.any { it.thinking == "正在分析" })
        assertTrue("正文不得丢失", visible.any { it.text == "部分结果" })
    }

    @Test fun aCompletionHeldForAnUnknownIdClosesTheTurnAsSoonAsTheObserverNamesIt() = runTest {
        val (store, events, _) = fixture()
        val key = store.register("stored-1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        advanceUntilIdle()

        // Completion arrives under a runtime handle the phone never bound (socket bounced).
        events.emit(event("message.complete", "runtime-7", "最终回答"))
        advanceUntilIdle()
        assertTrue("别名未知时先缓冲，回合仍开着", store.runtimes.value.getValue(key).chat.isGenerating)

        // The inbox names the handle: the buffered completion lands first, then the observed
        // run.completed is folded as the replay it is.
        store.applyObservedLifecycle(lifecycle("run.completed", stored = "stored-1", runtime = "runtime-7"))
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        assertFalse(runtime.phase.isActive)
        assertFalse(runtime.chat.isGenerating)
        val answer = runtime.chat.messages.last { it.role == Role.ASSISTANT }
        assertEquals("最终回答", answer.text)
        assertFalse(answer.isStreaming)
    }

    @Test fun heldEventsExpireInsteadOfReplayingStaleContent() = runTest {
        val (store, events, clock) = fixture()
        val key = store.register("stored-1", "personal")
        events.emit(event("message.delta", "runtime-7", "陈旧片段"))
        advanceUntilIdle()

        clock.now += 60_000L + 1 // one tick past SessionRuntimeStore.PENDING_EVENT_TTL_MS
        store.bindLiveHandle(key, "runtime-7")
        advanceUntilIdle()

        assertTrue(store.runtimes.value.getValue(key).chat.messages.none { it.text == "陈旧片段" })
    }

    @Test fun replayFollowedByReconcileLeavesOneAssistantTurn() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("stored-1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "最终回答"),
        )
        val (store, events, _) = fixture(sessions)
        val key = store.register("stored-1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.complete", "runtime-7", "最终回答"))
        advanceUntilIdle()

        store.applyObservedLifecycle(lifecycle("run.completed", stored = "stored-1", runtime = "runtime-7"))
        advanceUntilIdle()

        val assistants = store.runtimes.value.getValue(key).chat.messages.filter { it.role == Role.ASSISTANT }
        assertEquals("重放 + 对账后只能有一条 assistant", 1, assistants.size)
        assertEquals("最终回答", assistants.single().text)
    }
}
