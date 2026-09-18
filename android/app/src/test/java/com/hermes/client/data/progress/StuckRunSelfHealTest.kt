package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HG-59: the Mac finished the turn, the phone kept spinning for ten minutes.
 *
 * Three independent defects had to line up, and every one of them is exercised here:
 *  - the `message.complete` was lost with the socket (routine: 22 starts, 17 completes in one
 *    day's log), so the only thing that could retire the phase was a pushed `session.info`;
 *  - Hermes did not send that push, and a successful `session.resume` was being read as proof
 *    that the run was still going — 86 probes, all successful, all no-ops;
 *  - every prompt in that conversation carried images, so upstream's bookkeeping made the
 *    persisted user row differ from the typed text and reconciliation rejected all 34 snapshots,
 *    which also blocked the pull-to-refresh that was the user's only way out.
 *
 * The mock's `session.resume` always answers with `session.info{running:false}`, which is why
 * none of this was reachable from the existing suite. These tests never send that push.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StuckRunSelfHealTest {
    /** What upstream persists for a turn sent with images: the typed text plus its own bookkeeping. */
    private fun withUpstreamImageNote(typed: String) = "$typed [User attached image: /tmp/a.png]"

    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type, sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId); text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private class Clock(var now: Long = System.currentTimeMillis()) : () -> Long {
        override fun invoke() = now
    }

    private data class Fixture(
        val store: SessionRuntimeStore,
        val events: MutableSharedFlow<ServerEvent>,
        val chat: ChatRepository,
        val sessions: SessionRepository,
        val clock: Clock,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val sessions = mockk<SessionRepository>(relaxed = true)
        val clock = Clock()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(
            chat, scope, profiles,
            sessionRepository = sessions,
            clock = clock,
        )
        runCurrent()
        return Fixture(store, events, chat, sessions, clock)
    }

    /**
     * The whole incident, end to end: a turn sent with images, its completion lost with the
     * socket, and an upstream that never pushes `session.info`. Before the fix the run stayed
     * THINKING no matter how many times it was asked about.
     */
    @Test fun aRunWhoseCompletionWasLostSettlesFromTheTranscript() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "归档附件图片")
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        // The socket dies here. No message.complete, and no session.info will ever arrive.
        coEvery { f.chat.resume("s1", "personal") } returns null
        coEvery { f.sessions.history(any(), any(), any()) } returns listOf(
            ChatMessage("h-0", Role.USER, withUpstreamImageNote("归档附件图片")),
            ChatMessage("h-1", Role.ASSISTANT, "已经归档完毕。"),
        )

        f.clock.now += 4 * 60_000L
        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        val runtime = f.store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.COMPLETED_UNREAD, runtime.phase)
        assertFalse("气泡不再转圈", runtime.chat.isGenerating)
    }

    /**
     * The guard that keeps the backstop honest. A long tool call goes quiet for minutes too —
     * the difference is that upstream has not persisted an answer for it, and a bare silence
     * timeout could not tell the two apart.
     */
    @Test fun aRunUpstreamHasNotAnsweredYetKeepsRunning() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "跑一个很久的工具")
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        coEvery { f.chat.resume("s1", "personal") } returns null
        // REST has the prompt, but no answer yet: the run really is still going.
        coEvery { f.sessions.history(any(), any(), any()) } returns listOf(
            ChatMessage("h-0", Role.USER, "跑一个很久的工具"),
        )

        f.clock.now += 10 * 60_000L
        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        assertTrue("没有答案就不能宣布结束", f.store.runtimes.value.getValue(key).phase.isActive)
    }

    /** A fresh run is never second-guessed, however the probe was triggered. */
    @Test fun aRunThatIsStillSpeakingIsLeftAlone() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "在说话")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.delta", "s1", "正在写"))
        runCurrent()
        coEvery { f.chat.resume("s1", "personal") } returns null
        coEvery { f.sessions.history(any(), any(), any()) } returns listOf(
            ChatMessage("h-0", Role.USER, "在说话"),
            ChatMessage("h-1", Role.ASSISTANT, "写完了"),
        )

        f.store.probe(key, force = true)
        advanceTimeBy(1_501L); runCurrent()

        assertTrue("没到静默阈值就不去翻历史", f.store.runtimes.value.getValue(key).phase.isActive)
    }

    /**
     * The push winning the race must not be overridden by a transcript read that started first.
     */
    @Test fun anArrivingSessionInfoWinsOverTheTranscriptRead() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "归档附件图片")
        f.events.emit(event("message.start", "s1"))
        runCurrent()
        coEvery { f.chat.resume("s1", "personal") } returns null
        coEvery { f.sessions.history(any(), any(), any()) } returns listOf(
            ChatMessage("h-0", Role.USER, withUpstreamImageNote("归档附件图片")),
            ChatMessage("h-1", Role.ASSISTANT, "已经归档完毕。"),
        )

        f.clock.now += 4 * 60_000L
        f.store.probe(key, force = true)
        f.events.emit(event("message.complete", "s1", "已经归档完毕。"))
        advanceTimeBy(1_501L); runCurrent()

        val runtime = f.store.runtimes.value.getValue(key)
        assertFalse(runtime.phase.isActive)
        assertEquals("已经归档完毕。", runtime.chat.messages.last { it.role == Role.ASSISTANT }.text)
    }

    /**
     * The reconcile guard itself. Upstream's note on the user row is not a reason to throw away
     * an authoritative snapshot — the assistant branch right beside it has always been this
     * tolerant.
     */
    @Test fun aSnapshotWhoseUserRowCarriesUpstreamsImageNoteIsAccepted() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "归档附件图片")
        f.events.emit(event("message.start", "s1"))
        f.events.emit(event("message.delta", "s1", "写了一半"))
        runCurrent()

        val result = f.store.acceptManualHistory(key, listOf(
            ChatMessage("h-0", Role.USER, withUpstreamImageNote("归档附件图片")),
            ChatMessage("h-1", Role.ASSISTANT, "已经归档完毕。"),
        ))

        assertEquals("下拉刷新不该再被挡成 BUSY", ManualHistoryResult.CHANGED, result)
        val runtime = f.store.runtimes.value.getValue(key)
        assertEquals("已经归档完毕。", runtime.chat.messages.last { it.role == Role.ASSISTANT }.text)
    }

    /** A snapshot genuinely behind the local turn is still refused — the guard is narrowed, not removed. */
    @Test fun aSnapshotMissingTheLocalTurnIsStillRefused() = runTest {
        val f = fixture()
        val key = f.store.register("s1", "personal")
        f.store.beginPrompt(key, "第一问")
        f.events.emit(event("message.complete", "s1", "第一答"))
        runCurrent()
        f.store.beginPrompt(key, "第二问")
        runCurrent()

        val result = f.store.acceptManualHistory(key, listOf(
            ChatMessage("h-0", Role.USER, "第一问"),
            ChatMessage("h-1", Role.ASSISTANT, "第一答"),
        ))

        assertEquals(ManualHistoryResult.BUSY, result)
    }
}
