package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pending approval or clarify card must not outlive the run it belongs to.
 *
 * Found on the emulator pass of 0.1.98 (2026-09-06): the mock Hermes, like upstream after its
 * approval timeout, moves on without answering an ignored `approval.request`. The card stayed in
 * the committed runtime after `message.complete`, so reopening the finished conversation showed a
 * modal 需要审批 sheet that BACK could not dismiss, and answering it flipped the finished session to
 * a phantom 思考中 (`IDLE→THINKING cause=input-answered`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StalePendingCardTest {

    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
            if (type == "approval.request") {
                put("command", "systemctl restart hermes-gateway")
                put("description", "重启网关服务以应用配置")
            }
            if (type == "clarify.request") {
                put("request_id", "clr-1")
                put("question", "要用哪种发布方式？")
            }
        },
    )

    private fun completed(sessionId: String) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "event-run.completed-$sessionId",
        deviceId = "mac-mini",
        profile = "personal",
        runtimeSessionId = "runtime-$sessionId",
        storedSessionId = sessionId,
        event = "run.completed",
        state = "idle",
        occurredAt = "2026-09-06T02:00:00.000Z",
    )

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
    )

    private fun TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        every { chat.connectionState } returns connection
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            SessionRuntimeStore(chatRepository = chat, appScope = scope, profiles = profiles),
            events,
            connection,
        )
    }

    @Test fun anIgnoredApprovalIsDroppedWhenTheRunCompletes() = runTest {
        val (store, events, _) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "重启一下网关")
        events.emit(event("message.start", "s1"))
        events.emit(event("approval.request", "s1"))
        advanceUntilIdle()

        val waiting = store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.WAITING_APPROVAL, waiting.phase)
        assertNotNull("审批卡在等待期间必须存在", waiting.chat.pendingApproval)

        // Nobody answers; Hermes denies after its timeout and finishes the turn on its own.
        events.emit(event("message.complete", "s1", "已跳过重启。"))
        advanceUntilIdle()

        val finished = store.runtimes.value.getValue(key)
        assertFalse(finished.phase.isActive)
        assertNull("运行结束后审批卡必须消失，否则重开会话会再弹需要审批", finished.chat.pendingApproval)
    }

    @Test fun anIgnoredClarifyIsDroppedByAnObservedCompletion() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s2", "personal")
        store.beginPrompt(key, "部署")
        events.emit(event("message.start", "s2"))
        events.emit(event("clarify.request", "s2"))
        advanceUntilIdle()
        assertNotNull(store.runtimes.value.getValue(key).chat.pendingClarify)

        // The socket dies with the question open; the only terminal signal is the Relay observation.
        connection.value = ConnectionState.Disconnected
        runCurrent()
        store.applyObservedLifecycle(completed("s2"))
        advanceUntilIdle()

        val finished = store.runtimes.value.getValue(key)
        assertFalse(finished.phase.isActive)
        assertNull("观测到的 run.completed 同样要清掉提问卡", finished.chat.pendingClarify)
    }

    @Test fun aReconnectKeepsTheCardTheRunIsStillWaitingOn() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s3", "personal")
        store.beginPrompt(key, "重启一下网关")
        events.emit(event("message.start", "s3"))
        events.emit(event("approval.request", "s3"))
        advanceUntilIdle()

        connection.value = ConnectionState.Disconnected
        runCurrent()
        val offline = store.runtimes.value.getValue(key)
        assertTrue("断线期间运行仍算活动", offline.phase.isActive)
        assertNotNull("断线不是运行结束，审批卡必须保留（HG-8）", offline.chat.pendingApproval)

        connection.value = ConnectionState.Connected
        runCurrent()
        val back = store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.WAITING_APPROVAL, back.phase)
        assertNotNull(back.chat.pendingApproval)
    }

    @Test fun answeringAfterTheRunEndedDoesNotRestartIt() = runTest {
        val (store, events, _) = fixture()
        val key = store.register("s4", "personal")
        store.beginPrompt(key, "重启一下网关")
        events.emit(event("message.start", "s4"))
        events.emit(event("approval.request", "s4"))
        events.emit(event("message.complete", "s4", "已跳过重启。"))
        advanceUntilIdle()
        assertFalse(store.runtimes.value.getValue(key).phase.isActive)

        // A stale sheet (or a notification action racing the completion) is answered anyway.
        store.continueAfterInput(key)
        runCurrent()

        val after = store.runtimes.value.getValue(key)
        assertFalse("已结束的运行不能因为一次迟到的回答变成幻影思考中", after.phase.isActive)
        assertFalse(after.chat.isGenerating)
    }

    @Test fun answeringOnARuntimeThatNeverSawATerminalStillContinues() = runTest {
        val (store, _, _) = fixture()
        // Fresh process, approval answered from the notification shade before any event arrived.
        val key = store.register("s5", "personal")
        store.continueAfterInput(key)
        runCurrent()
        assertEquals(SessionRunPhase.THINKING, store.runtimes.value.getValue(key).phase)
    }
}
