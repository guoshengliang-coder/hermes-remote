package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
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
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val eagerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(SessionRuntimeStore(chat, eagerScope, profiles), events)
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
}
