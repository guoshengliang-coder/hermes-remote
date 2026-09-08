package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A manual refresh during a run refreshes like a reconnect reconcile instead of queueing. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRefreshDuringRunTest {
    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type, sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId); text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private fun kotlinx.coroutines.test.TestScope.store(): Pair<SessionRuntimeStore, MutableSharedFlow<ServerEvent>> {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        return SessionRuntimeStore(chat, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), profiles) to events
    }

    @Test fun aCoveringSnapshotIsAcceptedWhileTheRunIsActiveAndThePhaseIsLeftAlone() = runTest {
        val (store, events) = store()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在分析"))
        events.emit(event("message.delta", "s1", "部分"))
        runCurrent()

        val result = store.acceptManualHistory(key, listOf(
            ChatMessage("h-0", Role.USER, "昨天数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "权威的部分正文"),
        ))

        val runtime = store.runtimes.value.getValue(key)
        assertEquals(ManualHistoryResult.CHANGED, result)
        assertTrue("phase 由事件决定，刷新不动它", runtime.phase.isActive)
        val tail = runtime.chat.messages.last { it.role == Role.ASSISTANT }
        assertEquals("权威的部分正文", tail.text)
        assertEquals("思考内容继承", "正在分析", tail.thinking)
        assertTrue("尾部仍在流式", tail.isStreaming)
    }

    @Test fun aSnapshotBehindTheLocalTurnIsRefusedWhileTheRunIsActive() = runTest {
        val (store, events) = store()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "第一问")
        events.emit(event("message.complete", "s1", "第一答"))
        runCurrent()
        store.beginPrompt(key, "第二问")
        runCurrent()

        // REST has not persisted the second user turn yet.
        val result = store.acceptManualHistory(key, listOf(
            ChatMessage("h-0", Role.USER, "第一问"),
            ChatMessage("h-1", Role.ASSISTANT, "第一答"),
        ))

        assertEquals(ManualHistoryResult.BUSY, result)
        assertEquals("第二问", store.runtimes.value.getValue(key).chat.messages.last().text)
    }
}
