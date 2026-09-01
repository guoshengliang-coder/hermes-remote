package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeStoreTest {
    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
        val chat: ChatRepository,
    )

    private fun lifecycle(
        kind: String,
        sessionId: String = "external",
        profile: String? = "personal",
    ) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "event-$kind-$sessionId",
        deviceId = "mac-mini",
        profile = profile,
        runtimeSessionId = "runtime-$sessionId",
        storedSessionId = sessionId,
        event = kind,
        state = when (kind) {
            "run.waiting" -> "waiting"
            "run.completed" -> "idle"
            else -> "working"
        },
        occurredAt = "2026-08-31T08:30:00.000Z",
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        sessions: com.hermes.client.data.repository.SessionRepository? = null,
    ): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        every { chat.connectionState } returns connection
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val eagerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            SessionRuntimeStore(
                chatRepository = chat,
                appScope = eagerScope,
                profiles = profiles,
                sessionRepository = sessions,
            ),
            events,
            connection,
            chat,
        )
    }

    @Test fun notificationTargetMapsALiveHandleBackToItsStoredConversation() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("stored-42", "artist")
        fixture.store.bindLiveHandle(key, "runtime-17")

        assertEquals(key, fixture.store.notificationTarget("runtime-17"))
        assertEquals(key, fixture.store.notificationTarget("stored-42"))
    }

    @Test fun observedLifecycleBindsItsRuntimeIdToTheStoredConversation() = runTest {
        val fixture = fixture()
        val observed = lifecycle("run.started", sessionId = "stored-42", profile = "artist")

        fixture.store.applyObservedLifecycle(observed)

        assertEquals(
            SessionRuntimeKey("artist", "stored-42"),
            fixture.store.notificationTarget("runtime-stored-42"),
        )
    }

    @Test fun intentionalDisconnectMarksAnActiveTurnForResume() = runTest {
        val fixture = fixture()
        val key = fixture.store.register("s1", "personal")
        fixture.store.beginPrompt(key, "继续处理")
        fixture.events.emit(event("message.start", "s1"))
        fixture.events.emit(event("message.delta", "s1", "半截内容"))
        runCurrent()

        fixture.connection.value = ConnectionState.Disconnected
        runCurrent()

        val runtime = fixture.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.RECONNECTING, runtime.phase)
        assertTrue(runtime.chat.isGenerating)
        assertEquals("半截内容", runtime.chat.messages.last().text)
    }

    @Test fun offscreen_stream_is_retained_and_marked_running() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "查一下")
        advanceUntilIdle()

        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在分析"))
        events.emit(event("message.delta", "s1", "部分结果"))
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.STREAMING, runtime.phase)
        assertTrue(runtime.chat.isGenerating)
        assertEquals("正在分析", runtime.chat.messages.last().thinking)
        assertEquals("部分结果", runtime.chat.messages.last().text)
    }

    @Test fun stale_history_does_not_overwrite_a_live_turn() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "新问题")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "实时内容"))
        advanceUntilIdle()

        store.acceptHistory(
            key,
            listOf(ChatMessage("old", Role.ASSISTANT, "旧历史")),
            requestStartedAt = System.currentTimeMillis() + 10_000,
        )

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals("实时内容", messages.last().text)
    }

    @Test fun two_sessions_keep_independent_streams() = runTest {
        val (store, events) = fixture()
        val first = store.register("s1", "personal")
        val second = store.register("s2", "personal")
        store.beginPrompt(first, "一")
        store.beginPrompt(second, "二")
        advanceUntilIdle()

        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "回答一"))
        events.emit(event("message.start", "s2"))
        events.emit(event("message.delta", "s2", "回答二"))
        advanceUntilIdle()

        assertEquals("回答一", store.runtimes.value.getValue(first).chat.messages.last().text)
        assertEquals("回答二", store.runtimes.value.getValue(second).chat.messages.last().text)
    }

    @Test fun unscoped_event_with_two_active_sessions_is_not_misrouted() = runTest {
        val (store, events) = fixture()
        val first = store.register("s1", "personal")
        val second = store.register("s2", "personal")
        store.beginPrompt(first, "一")
        store.beginPrompt(second, "二")

        events.emit(ServerEvent("message.delta", null, buildJsonObject { put("text", "ambiguous") }))
        advanceUntilIdle()

        assertEquals("一", store.runtimes.value.getValue(first).chat.messages.last().text)
        assertEquals("二", store.runtimes.value.getValue(second).chat.messages.last().text)
    }

    @Test fun terminal_event_retries_until_server_history_contains_the_turn() = runTest {
        val sessions = mockk<com.hermes.client.data.repository.SessionRepository>()
        val user = ChatMessage("persisted-user", Role.USER, "开始")
        val answer = ChatMessage("persisted-answer", Role.ASSISTANT, "完成内容")
        coEvery { sessions.history("s1", "personal") } returnsMany listOf(
            listOf(user),
            listOf(user, answer),
            listOf(user, answer),
            listOf(user, answer),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "开始")
        events.emit(event("message.complete", "s1", "完成内容"))
        runCurrent()

        advanceTimeBy(250L)
        runCurrent()
        // First pass: REST does not yet contain the answer turn — rejected, history not loaded.
        assertTrue(!store.runtimes.value.getValue(key).chat.historyLoaded)

        advanceTimeBy(1_000L)
        runCurrent()
        // Second pass accepted: persisted CONTENT lands, while identity alignment reuses the
        // live ids so list keys survive the swap — the REST id must NOT replace the local one.
        val chat = store.runtimes.value.getValue(key).chat
        assertTrue(chat.historyLoaded)
        assertTrue(chat.messages.any { it.text == answer.text })
        assertTrue(chat.messages.none { it.id == "persisted-answer" })
    }

    @Test fun acceptHistory_reusesLiveIds_soListKeysSurviveReopen() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.setVisible(key, true)          // completion while visible -> phase IDLE, keepLive=false
        store.beginPrompt(key, "你好")
        events.emit(event("message.complete", "s1", "答案"))
        runCurrent()
        val runtime = store.runtimes.value.getValue(key)
        val liveIds = runtime.chat.messages.map { it.id }
        assertTrue(liveIds.none { it.startsWith("h-") })
        // Reopen path: REST history lands with h-* ids; identity must be reused, not replaced.
        store.acceptHistory(
            key,
            listOf(
                ChatMessage("h-0-10", Role.USER, "你好"),
                ChatMessage("h-1-11", Role.ASSISTANT, "答案"),
            ),
            requestStartedAt = Long.MAX_VALUE,
        )
        val after = store.runtimes.value.getValue(key).chat.messages
        assertEquals("答案", after.last().text)
        assertTrue(after.none { it.id.startsWith("h-") })
        assertEquals(liveIds.take(after.size), after.map { it.id })
    }

    @Test fun completion_while_offscreen_becomes_unread() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "开始")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "完成内容"))
        advanceUntilIdle()

        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(key).phase)
        assertTrue("personal/s1" in store.unreadTokens.value)
        store.setVisible(key, true)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(key).phase)
        store.markRead(key)
        assertEquals(SessionRunPhase.IDLE, store.runtimes.value.getValue(key).phase)
        assertFalse("personal/s1" in store.unreadTokens.value)
    }

    @Test fun resumed_running_session_restores_generating_state() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")

        events.emit(
            ServerEvent(
                "session.info",
                "s1",
                buildJsonObject { put("session_id", "s1"); put("running", true) },
            ),
        )
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.THINKING, runtime.phase)
        assertTrue(runtime.chat.isGenerating)
    }

    @Test fun opening_a_just_completed_turn_does_not_accept_shorter_server_history() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "开始")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "手机已经收到的完整答案"))
        advanceUntilIdle()

        store.setVisible(key, true)
        store.acceptHistory(
            key,
            listOf(ChatMessage("old", Role.USER, "开始")),
            requestStartedAt = System.currentTimeMillis() + 10_000,
        )
        store.markRead(key) // successful history display clears the unread badge

        assertEquals("手机已经收到的完整答案", store.runtimes.value.getValue(key).chat.messages.last().text)
    }

    @Test fun foregroundRecoveryWaitsForCompleteHistoryBeforeReportingReady() = runTest {
        val sessions = mockk<com.hermes.client.data.repository.SessionRepository>()
        val user = ChatMessage("persisted-user", Role.USER, "开始")
        val answer = ChatMessage("persisted-answer", Role.ASSISTANT, "完整答案")
        coEvery { sessions.history("s1", "personal") } returnsMany listOf(
            listOf(user),
            listOf(user, answer),
        )
        val fixture = fixture(sessions)
        coEvery { fixture.chat.resume("s1", "personal") } returns "runtime-s1"
        val key = fixture.store.register("s1", "personal")
        fixture.store.beginPrompt(key, "开始")
        fixture.events.emit(event("message.delta", "s1", "完整答案"))
        runCurrent()

        assertTrue(fixture.store.recoverVisibleSession(key))

        coVerify(exactly = 2) { sessions.history("s1", "personal") }
        coVerify(exactly = 1) { fixture.chat.resume("s1", "personal") }
        assertEquals("完整答案", fixture.store.runtimes.value.getValue(key).chat.messages.last().text)
        assertFalse("personal/s1" in fixture.store.unreadTokens.value)
        // Stop the resumed run so the store's background process poller can settle in runTest.
        fixture.events.emit(event("error", "s1"))
        runCurrent()
    }

    @Test fun foregroundRecoveryDoesNotResumeOrHideGateForPersistentlyStaleHistory() = runTest {
        val sessions = mockk<com.hermes.client.data.repository.SessionRepository>()
        val user = ChatMessage("persisted-user", Role.USER, "开始")
        coEvery { sessions.history("s1", "personal") } returns listOf(user)
        val fixture = fixture(sessions)
        val key = fixture.store.register("s1", "personal")
        fixture.store.beginPrompt(key, "开始")
        fixture.events.emit(event("message.delta", "s1", "完整答案"))
        runCurrent()

        assertFalse(fixture.store.recoverVisibleSession(key))

        coVerify(exactly = 4) { sessions.history("s1", "personal") }
        coVerify(exactly = 0) { fixture.chat.resume(any(), any()) }
    }

    @Test fun idle_runtime_cache_is_bounded_but_keeps_active_sessions() = runTest {
        val (store, _) = fixture()
        repeat(25) { store.register("idle-$it", "personal") }
        assertEquals(20, store.runtimes.value.size)

        val active = store.register("active", "personal")
        store.beginPrompt(active, "继续运行")
        repeat(25) { store.register("more-$it", "personal") }

        assertTrue(active in store.runtimes.value)
        assertEquals(SessionRunPhase.SUBMITTING, store.runtimes.value.getValue(active).phase)
        assertTrue(store.runtimes.value.size <= 21)
    }

    @Test fun observed_external_run_updates_list_state_without_becoming_phone_owned() = runTest {
        val (store, _) = fixture()
        val key = SessionRuntimeKey("personal", "external")

        store.applyObservedLifecycle(lifecycle("run.started"))
        assertEquals(SessionRunPhase.THINKING, store.runtimes.value.getValue(key).phase)
        assertTrue(store.runtimes.value.getValue(key).hasActiveWork)
        assertFalse(store.runtimes.value.getValue(key).startedLocally)

        store.applyObservedLifecycle(lifecycle("run.waiting"))
        assertEquals(SessionRunPhase.WAITING_ATTENTION, store.runtimes.value.getValue(key).phase)

        store.applyObservedLifecycle(lifecycle("run.completed"))
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(key).phase)
        assertTrue("personal/external" in store.unreadTokens.value)
    }

    @Test fun profileless_completion_finishes_and_reconciles_the_default_profile_runtime() = runTest {
        val sessions = mockk<com.hermes.client.data.repository.SessionRepository>()
        val persisted = listOf(
            ChatMessage("persisted-user", Role.USER, "查询"),
            ChatMessage("persisted-answer", Role.ASSISTANT, "服务端已经生成完成的完整回答"),
        )
        coEvery { sessions.history("s-default", "default") } returns persisted
        val (store, events) = fixture(sessions)
        val key = store.register("s-default", "default")
        store.beginPrompt(key, "查询")
        events.emit(event("message.start", "s-default"))
        events.emit(event("message.delta", "s-default", "手机离开前收到的部分回答"))
        advanceUntilIdle()

        store.applyObservedLifecycle(
            lifecycle("run.completed", sessionId = "s-default", profile = null),
        )
        runCurrent()

        assertEquals(1, store.runtimes.value.keys.count { it.sessionId == "s-default" })
        assertFalse(store.runtimes.value.getValue(key).chat.isGenerating)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, store.runtimes.value.getValue(key).phase)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(
            "服务端已经生成完成的完整回答",
            store.runtimes.value.getValue(key).chat.messages.last().text,
        )
    }
}
