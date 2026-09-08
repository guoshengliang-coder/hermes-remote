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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression set for the session-state desync family reported as HG-6, HG-7 and HG-8.
 *
 * All three are one incident on one conversation (2026-09-05, `20260905_102612_6d5fd4`),
 * reconstructed from four independent sources: the Hermes `messages` table on the Mac mini, the
 * Gateway's `lifecycle-events.json`, the HK Nginx access log, and the reporter's screenshots.
 *
 * The measured facts these tests encode:
 *  - The WebSocket carrying the run closed at 10:31:02; the run finished at 10:31:08. `message.complete`
 *    was never delivered and is never replayed across a reconnect.
 *  - `run.completed` reached the Gateway at 10:31:08 but was only delivered to the phone at 10:33:13,
 *    because Android had the app in Doze. Across 180 observed completions, 26% were delivered more
 *    than 30s late (vs 1% of `run.started`: the phone is awake when a run starts, asleep when it ends).
 *  - Every session-level terminal writer clears `phase` and `ChatUiState.isGenerating` but never
 *    `ChatMessage.isStreaming`, so the bubble kept rendering "生成中" with a running chronometer for
 *    20+ minutes across two further completed turns.
 *  - Reconnecting collapses every active phase into THINKING, so a run that is waiting for the user
 *    silently renders as "思考中".
 *  - REST history carries no reasoning or tool calls (`MessageDto` does not model them), and
 *    `acceptReconciledHistory` replaces the message list wholesale, so a reconcile erases both.
 *
 * Each test here fails against the current implementation by design; see the report in the HG-6/7/8
 * analysis. They are the definition of done for the fix, not a description of today's behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateDesyncRegressionTest {

    private fun event(type: String, sessionId: String, text: String? = null, running: Boolean? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            running?.let { put("running", it) }
            if (type == "message.start") put("message_id", "agent")
            if (type == "tool.start" || type == "tool.complete") {
                put("tool_id", "t1")
                put("name", "terminal")
            }
        },
    )

    private fun lifecycle(kind: String, sessionId: String, profile: String? = "personal") = LifecycleEventDto(
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
        occurredAt = "2026-09-05T02:31:09.000Z",
    )

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        sessions: SessionRepository? = null,
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
        )
    }

    /**
     * HG-6. The socket dies before the run finishes, so the only terminal signal is the Relay
     * observation minutes later. It must close the bubble, not just the session-level flags.
     */
    @Test fun observedCompletionAfterALostSocketClosesTheStreamingBubble() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在分析"))
        events.emit(event("message.delta", "s1", "部分结果"))
        advanceUntilIdle()

        // 10:31:02 — the socket carrying this run closes, six seconds before the run ends.
        connection.value = ConnectionState.Disconnected
        runCurrent()

        // 10:33:13 — Doze ends, the inbox finally delivers the completion the socket never carried.
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        assertFalse("会话级已结束", runtime.phase.isActive)
        assertFalse("isGenerating 已清除", runtime.chat.isGenerating)
        assertFalse(
            "气泡必须停止显示生成中：僵尸 isStreaming 会让计时器一直走（HG-6）",
            runtime.chat.messages.last { it.role == Role.ASSISTANT }.isStreaming,
        )
    }

    /**
     * HG-7. Once the composer unlocks, the user sends again. A turn that already ended must not
     * leave a second bubble claiming to be live.
     */
    @Test fun aNewPromptAfterAnObservedCompletionLeavesExactlyOneLiveBubble() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "第一轮回答"))
        advanceUntilIdle()
        connection.value = ConnectionState.Disconnected
        runCurrent()
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        // 10:47:22 — the composer is back to "send", so the user asks a follow-up.
        connection.value = ConnectionState.Connected
        store.beginPrompt(key, "html我看不到，我远程访问你的")
        events.emit(event("message.start", "s1"))
        advanceUntilIdle()

        val live = store.runtimes.value.getValue(key).chat.messages.count { it.isStreaming }
        assertEquals("同一时刻只能有一个气泡在生成中（HG-7）", 1, live)
    }

    /**
     * HG-8, first half. `run.waiting` means the run is blocked on the user. A reconnect must not
     * rewrite that into "thinking", or the user is never told they are being waited on.
     */
    @Test fun reconnectPreservesAWaitingPhaseInsteadOfCollapsingItToThinking() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "html我看不到，我远程访问你的")
        events.emit(event("message.start", "s1"))
        advanceUntilIdle()

        // 10:56:32 — the run has been waiting on the user since 10:53:10.
        store.applyObservedLifecycle(lifecycle("run.waiting", "s1"))
        advanceUntilIdle()
        assertEquals(SessionRunPhase.WAITING_ATTENTION, store.runtimes.value.getValue(key).phase)

        // 10:57:08 and 10:57:49 — two reconnects in the next 77 seconds.
        connection.value = ConnectionState.Reconnecting
        runCurrent()
        connection.value = ConnectionState.Connected
        advanceUntilIdle()

        assertEquals(
            "重连不得把「等待你处理」降级成「思考中」（HG-8）",
            SessionRunPhase.WAITING_ATTENTION,
            store.runtimes.value.getValue(key).phase,
        )
    }

    /**
     * HG-8, second half. REST history models neither reasoning nor tool calls, so a reconcile that
     * replaces the list wholesale silently deletes both. It may correct and add; it may not delete
     * what it does not model.
     */
    @Test fun historyReconciliationKeepsReasoningAndToolsRestDoesNotCarry() = runTest {
        val sessions = mockk<SessionRepository>()
        // What the Gateway actually returns: text only — no reasoning, no tool calls.
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "完成内容"),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在分析"))
        events.emit(event("tool.start", "s1"))
        events.emit(event("tool.complete", "s1"))
        events.emit(event("message.complete", "s1", "完成内容"))
        advanceUntilIdle()

        val answer = store.runtimes.value.getValue(key).chat.messages.last { it.role == Role.ASSISTANT }
        assertEquals("对账后正文应为权威版本", "完成内容", answer.text)
        assertEquals(
            "对账不得抹掉已流式收到的思考内容（HG-8）",
            "正在分析",
            answer.thinking,
        )
        assertTrue(
            "对账不得抹掉已流式收到的工具记录（HG-8）",
            answer.tools.isNotEmpty(),
        )
    }

    /**
     * The invariant behind the fix, checked after every step of a seeded random walk over every
     * writer the store has: phase is the truth, isGenerating follows it, and a non-active phase
     * never leaves a bubble streaming. A future writer that forgets one of the three trips this
     * test instead of a user.
     */
    @Test fun everyWriterLeavesPhaseGeneratingAndStreamingConsistent() = runTest {
        val (store, events, connection) = fixture()
        val key = store.register("s1", "personal")
        val random = kotlin.random.Random(20260905)
        val socketEvents = listOf(
            "message.start", "reasoning.delta", "message.delta", "tool.start", "tool.complete",
            "message.complete", "error", "approval.request", "clarify.request",
        )
        val observed = listOf(
            "run.started", "run.waiting", "run.resumed", "run.completed", "run.interrupted", "run.unknown",
        )
        repeat(400) { step ->
            when (random.nextInt(10)) {
                0 -> store.beginPrompt(key, "问题 $step")
                1 -> events.emit(event(socketEvents.random(random), "s1", "片段 $step"))
                2 -> events.emit(event("session.info", "s1", running = random.nextBoolean()))
                3 -> store.applyObservedLifecycle(lifecycle(observed.random(random), "s1"))
                4 -> connection.value = if (random.nextBoolean()) ConnectionState.Disconnected else ConnectionState.Connected
                5 -> store.markInterrupted(key)
                6 -> store.finishLocal(key)
                7 -> store.markFailed(key, store.runtimes.value.getValue(key).chat)
                8 -> store.continueAfterInput(key)
                9 -> {
                    store.setAppInForeground(random.nextBoolean())
                    store.setVisible(key, random.nextBoolean())
                    store.markRead(key)
                }
            }
            advanceUntilIdle()
            val runtime = store.runtimes.value.getValue(key)
            assertEquals(
                "step $step (${runtime.phase}): isGenerating 必须等于 phase.isActive",
                runtime.phase.isActive,
                runtime.chat.isGenerating,
            )
            if (!runtime.phase.isActive) {
                assertTrue(
                    "step $step (${runtime.phase}): 非活跃 phase 下不得有流式气泡",
                    runtime.chat.messages.none { it.isStreaming },
                )
            }
        }
    }

    /**
     * A reconnect mid-run re-reads history while the run is still active. The REST rows carry no
     * streaming state, so the swap used to drop the running indicator off the tail bubble while the
     * list row still said "思考中" — the blank chat under a spinning row (HG-8).
     */
    @Test fun reconcileDuringAnActiveRunKeepsTheTailBubbleStreaming() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "html我看不到，我远程访问你的"),
            ChatMessage("h-1", Role.ASSISTANT, "权威的部分正文"),
        )
        val (store, events, connection) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "html我看不到，我远程访问你的")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "部分"))
        advanceUntilIdle()

        connection.value = ConnectionState.Disconnected
        runCurrent()
        connection.value = ConnectionState.Connected
        advanceTimeBy(300L)
        runCurrent()

        val runtime = store.runtimes.value.getValue(key)
        val tail = runtime.chat.messages.last { it.role == Role.ASSISTANT }
        assertTrue("运行仍在进行", runtime.phase.isActive)
        assertEquals("对账应接受权威正文", "权威的部分正文", tail.text)
        assertTrue("对账期间运行未结束，尾部气泡必须仍在流式状态（HG-8）", tail.isStreaming)
    }

    /**
     * Inheriting fields the REST row lacks must not make the reconcile look "unaccepted": that would
     * walk every rung of the retry ladder and re-download the transcript each time.
     */
    @Test fun inheritedFieldsDoNotStopTheReconcileFromAccepting() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "完成内容"),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在分析"))
        events.emit(event("message.complete", "s1", "完成内容"))
        advanceUntilIdle()

        // One accepted pass must end the ladder; a rejected one would fetch on every rung.
        coVerify(exactly = 1) { sessions.history("s1", "personal") }
        assertEquals("正在分析", store.runtimes.value.getValue(key).chat.messages.last().thinking)
    }
}
