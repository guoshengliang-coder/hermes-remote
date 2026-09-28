package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ActiveSession
import com.hermes.client.data.repository.ActiveSessionsSnapshot
import com.hermes.client.data.repository.ActiveSessionStatus
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    /** HG-155: a late WebSocket delta from the completed turn must not restart its clock. */
    @Test fun observedCompletionOutranksLateProgressForTheSameTurn() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal", "mac-mini")
        store.beginPrompt(key, "跨设备传文件")
        events.emit(event("message.start", "s1"))
        events.emit(event("reasoning.delta", "s1", "正在检查"))
        runCurrent()

        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        assertFalse(store.runtimes.value.getValue(key).phase.isActive)
        events.emit(event("reasoning.delta", "s1", "迟到的旧进度"))
        runCurrent()

        val runtime = store.runtimes.value.getValue(key)
        assertFalse("完成后到达的旧进度不得重新启动会话（HG-155）", runtime.phase.isActive)
        assertFalse(runtime.chat.isGenerating)
        assertTrue(runtime.chat.messages.none { it.isStreaming })
    }

    @Test fun anExplicitIdleInfoAlsoOutranksLateProgress() = runTest {
        val (store, events) = fixture()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "跨设备传文件")
        events.emit(event("message.start", "s1"))
        events.emit(event("session.info", "s1", running = false))
        events.emit(event("reasoning.delta", "s1", "迟到的旧进度"))
        runCurrent()

        assertFalse(store.runtimes.value.getValue(key).phase.isActive)
    }

    @Test fun sharedGatewaySnapshotRetiresAStalePhoneRunWithoutAttachingToPc() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal", "mac-mini")
        f.store.beginPrompt(key, "跨设备传文件")
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        coEvery { f.chat.activeSessions("personal") } returns ActiveSessionsSnapshot(emptyList())

        assertEquals(SessionRuntimeStore.ProbeResult.PROBED, f.store.probe(key, force = true))
        assertFalse(f.store.runtimes.value.getValue(key).phase.isActive)
        coVerify(exactly = 0) { f.chat.resume(any(), any()) }
    }

    @Test fun anIdleSnapshotDoesNotCancelAPromptStillBeingSubmitted() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal", "mac-mini")
        f.store.beginPrompt(key, "刚发送的提问")
        coEvery { f.chat.activeSessions("personal") } returns ActiveSessionsSnapshot(emptyList())

        f.store.probe(key, force = true)

        assertEquals(SessionRunPhase.SUBMITTING, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun sharedGatewaySnapshotDiscoversAPcRunAndItsWaitingState() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal", "mac-mini")
        coEvery { f.chat.activeSessions("personal") } returnsMany listOf(
            ActiveSessionsSnapshot(listOf(ActiveSession("live-1", "s1", ActiveSessionStatus.WORKING))),
            ActiveSessionsSnapshot(listOf(ActiveSession("live-1", "s1", ActiveSessionStatus.WAITING))),
        )

        assertEquals(SessionRuntimeStore.ProbeResult.PROBED, f.store.probe(key, force = true, includeIdle = true))
        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
        assertEquals(SessionRuntimeStore.ProbeResult.PROBED, f.store.probe(key, force = true))
        assertEquals(SessionRunPhase.WAITING_ATTENTION, f.store.runtimes.value.getValue(key).phase)
        coVerify(exactly = 0) { f.chat.resume(any(), any()) }
    }

    @Test fun delayedIdleSnapshotCannotRetireANewerPrompt() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal", "mac-mini")
        val pending = CompletableDeferred<ActiveSessionsSnapshot>()
        coEvery { f.chat.activeSessions("personal") } coAnswers { pending.await() }
        val probe = async { f.store.probe(key, force = true, includeIdle = true) }
        runCurrent()
        f.store.beginPrompt(key, "新的一轮")
        pending.complete(ActiveSessionsSnapshot(emptyList()))
        probe.await()

        assertEquals(SessionRunPhase.SUBMITTING, f.store.runtimes.value.getValue(key).phase)
    }

    @Test fun aPreviousTurnsLateCompletionCannotEndANewerObservedRun() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal", "mac-mini")
        f.store.applyObservedLifecycle(lifecycle("run.started", "s1", occurredAt = "2026-09-05T02:32:00Z"))
        f.store.applyObservedLifecycle(lifecycle("run.completed", "s1", occurredAt = "2026-09-05T02:31:00Z"))

        assertEquals(SessionRunPhase.THINKING, f.store.runtimes.value.getValue(key).phase)
    }

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

    private fun lifecycle(
        kind: String,
        sessionId: String,
        profile: String? = "personal",
        occurredAt: String = "2026-09-05T02:31:09.000Z",
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
        occurredAt = occurredAt,
    )

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val connection: MutableStateFlow<ConnectionState>,
        val chat: ChatRepository,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        sessions: SessionRepository? = null,
    ): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = legacyChatRepositoryFixture()
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

    /**
     * HG-124. A prompt sent from the PC lands in the Hermes transcript without a single message.*
     * event ever reaching the phone — only the run.started/run.completed pair does — so the
     * reconcile ladder is the only channel that can deliver that round to an open page. Its gate
     * used to read "the snapshot's last user text differs from the expectation" as staleness,
     * which rejects exactly the server-ahead snapshot: measured 2026-09-24 on
     * 20260924_102646_68e7a7, three consecutive passes answered "last user turn differs" for a
     * round Hermes had already committed, and the page sat stale until a manual refresh.
     */
    @Test fun reconcileAcceptsASnapshotAheadByACrossDeviceTurn() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "第一轮回答"),
            ChatMessage("h-2", Role.USER, "PC 上发的新问题"),
            ChatMessage("h-3", Role.ASSISTANT, "PC 上跑完的回答"),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "第一轮回答"))
        events.emit(event("message.complete", "s1", "第一轮回答"))
        advanceUntilIdle()

        // The PC sends the next prompt; the phone only ever hears the lifecycle pair for it.
        store.applyObservedLifecycle(lifecycle("run.started", "s1"))
        advanceUntilIdle()
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals("跨端新轮次必须经对账进入页面（HG-124）", "PC 上发的新问题", messages.last { it.role == Role.USER }.text)
        assertEquals("PC 上跑完的回答", messages.last { it.role == Role.ASSISTANT }.text)
    }

    /** The protection the gate exists for: a snapshot that lags the local turn is still refused. */
    @Test fun reconcileStillRefusesASnapshotThatLagsTheLocalTurn() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？"),
            ChatMessage("h-1", Role.ASSISTANT, "第一轮回答"),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "第一轮回答"))
        advanceUntilIdle()

        // Hermes has not committed this phone's follow-up yet; the snapshot cannot contain it.
        store.beginPrompt(key, "手机上接着问")
        events.emit(event("message.start", "s1"))
        advanceUntilIdle()
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue("本地未落盘的轮次不得被对账抹掉", messages.any { it.text == "手机上接着问" })
    }

    /**
     * Equal counts with a different last user turn is divergence, not coverage: the narrowed
     * HG-124 rule accepts a newer row only when the locally observed text still survives inside
     * the snapshot. A snapshot that lost it entirely keeps being refused.
     */
    @Test fun reconcileStillRefusesASnapshotThatLostTheLocalTurn() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "服务端那条不相关的旧轮次"),
            ChatMessage("h-1", Role.ASSISTANT, "第一轮回答"),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "手机上发的")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "第一轮回答"))
        advanceUntilIdle()
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue("轮数相同但丢了本地文本的快照必须拒绝", messages.any { it.text == "手机上发的" })
        assertFalse(messages.any { it.text == "服务端那条不相关的旧轮次" })
    }

    /**
     * HG-141. A run another device started reaches this phone as the lifecycle pair plus the
     * streamed message.* events, but Hermes persists the assistant turn only when it completes —
     * so while that run is going, every REST snapshot is structurally one assistant turn short of
     * what the phone has folded. Judging coverage against the in-flight bubble refused every
     * snapshot, and a conversation opened on the phone froze on its first user turn while the
     * message count kept growing on the server.
     */
    @Test fun reconcileAcceptsASnapshotOfARemoteRunWhileItsLastTurnIsStillStreaming() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal") } returns listOf(
            ChatMessage("h-0", Role.USER, "PC 端的第一句", serverId = 1),
            ChatMessage("h-1", Role.ASSISTANT, "PC 端第一轮完成", serverId = 2),
            ChatMessage("h-2", Role.USER, "PC 端的第二句", serverId = 3),
        )
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.applyObservedLifecycle(lifecycle("run.started", "s1"))
        advanceUntilIdle()

        // The remote run's stream folds a live bubble on the phone; its final turn has not landed.
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "PC 端正在输出的部分"))
        advanceUntilIdle()
        // A completed earlier turn schedules the reconcile ladder; the run keeps going, so the
        // next message.start re-arms the phase before the ladder's first rung fires.
        events.emit(event("message.complete", "s1", "PC 端第一轮完成"))
        events.emit(event("message.start", "s1"))
        events.emit(event("message.delta", "s1", "第二轮正在输出"))
        advanceUntilIdle()

        val runtime = store.runtimes.value.getValue(key)
        assertTrue("运行未结束，阶段应保持活动", runtime.phase.isActive)
        val messages = runtime.chat.messages
        assertTrue("服务端已提交的用户轮必须进入页面", messages.any { it.text == "PC 端的第一句" })
        assertTrue("服务端已完成的助手轮必须进入页面", messages.any { it.text == "PC 端第一轮完成" })
        assertTrue("跨端第二句（已落盘）必须经对账进入页面", messages.any { it.text == "PC 端的第二句" })
        val live = messages.last { it.role == Role.ASSISTANT }
        assertTrue("运行中的在飞气泡不得被对账抹掉", live.isStreaming)
        assertTrue(
            "在飞气泡的已流式文本不得丢失",
            live.text == "PC 端正在输出的部分" || live.text == "第二轮正在输出",
        )
    }

    /**
     * HG-141, window half. A long cross-device run pushes the turn the phone last accepted out of
     * the newest `limit` page. The count checks learned the window exemption in HG-104; the text
     * presence check did not, so every fetch answered "last user turn differs" forever.
     */
    @Test fun reconcileAcceptsATailPageThatNoLongerCarriesTheLastAcceptedTurn() = runTest {
        val sessions = mockk<SessionRepository>()
        val earlyPage = listOf(
            ChatMessage("h-0", Role.USER, "很早以前的问题", serverId = 1),
            ChatMessage("h-1", Role.ASSISTANT, "很早以前的回答", serverId = 2),
        )
        val tailPage = listOf(
            ChatMessage("h-50", Role.USER, "滚出窗口后的新问题", serverId = 50),
            ChatMessage("h-51", Role.ASSISTANT, "滚出窗口后的新回答", serverId = 51),
        )
        coEvery { sessions.history("s1", "personal") } returnsMany listOf(earlyPage, tailPage)
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "很早以前的问题")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "很早以前的回答"))
        advanceUntilIdle()
        // The long remote run has since pushed both early turns out of the newest page.
        store.applyObservedLifecycle(lifecycle("run.started", "s1"))
        advanceUntilIdle()
        store.applyObservedLifecycle(lifecycle("run.completed", "s1"))
        advanceUntilIdle()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue(
            "本地已滑出窗口的轮次不得再被要求出现在尾页里（HG-141）",
            messages.any { it.text == "滚出窗口后的新问题" },
        )
        assertTrue(messages.any { it.text == "滚出窗口后的新回答" })
    }

    /**
     * HG-141, stale-expectation half. A cross-device round landing between the ladder's
     * scheduling and its fetch (via an open-path history accept, as the HG-141 logs show) moved
     * the current expectation past the captured one, and "a newer prompt started" then refused
     * every remaining rung against the expired expectation — the exact server-ahead snapshot
     * HG-124 taught the gate to accept. The expectation must be re-derived at acceptance time.
     */
    @Test fun reconcileJudgesTheSnapshotAgainstTheExpectationAtAcceptanceTime() = runTest {
        val sessions = mockk<SessionRepository>()
        val ahead = listOf(
            ChatMessage("h-0", Role.USER, "昨天公司数据如何？", serverId = 1),
            ChatMessage("h-1", Role.ASSISTANT, "第一轮回答", serverId = 2),
            ChatMessage("h-2", Role.USER, "对账梯子期间到达的跨端新问题", serverId = 3),
            ChatMessage("h-3", Role.ASSISTANT, "跨端新回答", serverId = 4),
        )
        coEvery { sessions.history("s1", "personal") } returns ahead
        val (store, events) = fixture(sessions)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "昨天公司数据如何？")
        events.emit(event("message.start", "s1"))
        events.emit(event("message.complete", "s1", "第一轮回答"))
        // The ladder is scheduled with the pre-cross-device expectation; before its first rung
        // fires, the open path accepts the transcript that now carries the cross-device round.
        runCurrent()
        store.acceptHistory(key, ahead, requestStartedAt = 0L)
        advanceUntilIdle()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue("跨端轮次必须留在页面", messages.any { it.text == "对账梯子期间到达的跨端新问题" })
        assertTrue(messages.any { it.text == "跨端新回答" })
        // With the expectation re-derived at acceptance time the very first rung covers the local
        // turns and the ladder ends; the expired-expectation refusal used to walk all four rungs.
        coVerify(exactly = 1) { sessions.history("s1", "personal") }
    }
}
