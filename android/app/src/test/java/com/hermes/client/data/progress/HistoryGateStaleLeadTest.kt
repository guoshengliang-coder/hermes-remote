package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
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
import kotlinx.coroutines.test.TestScope
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
 * HG-124 relapse (0.1.142, 20260924_102646_68e7a7): a transcript restored from disk refused the
 * server's strictly-ahead snapshot on every automatic path — re-entering the session, the recovery
 * ladder, the reuse short-circuit — while only the manual refresh, which judges coverage with the
 * same tolerance, showed the new content. These pin the two repairs: a covering snapshot wins over
 * the keep-live clauses, a quiet expired lead loses to a strictly-ahead snapshot, and a recent
 * local lead keeps its protection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryGateStaleLeadTest {
    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private fun row(serverId: Long, text: String, timestamp: Long) = ChatMessage(
        id = "h-$serverId",
        role = if (serverId % 2L == 1L) Role.USER else Role.ASSISTANT,
        text = text,
        timestamp = timestamp,
        serverId = serverId,
    )

    private fun TestScope.store(sessions: SessionRepository? = null): Pair<SessionRuntimeStore, MutableSharedFlow<ServerEvent>> {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return SessionRuntimeStore(chat, scope, profiles, sessionRepository = sessions) to events
    }

    /** Ages the runtime's quiet clock past the stale-lead window (10 minutes) without waiting. */
    private fun SessionRuntimeStore.ageBeyondStaleLead() {
        nowProvider = { System.currentTimeMillis() + 11 * 60_000L }
    }

    @Test fun aCoveringSnapshotReplacesTheTranscriptOnReopen() = runTest {
        val (store, events) = store()
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "手机上的追问")
        events.emit(event("message.complete", "s1", "追问的回答"))
        runCurrent()
        // A later open() refetches and REST now carries the phone's turn plus the PC's newer one.
        // requestStartedAt = 0 makes the old `lastEventAt > requestStartedAt` clause fire too, so
        // the pre-repair code discarded this snapshot unread.
        store.acceptHistory(
            key,
            listOf(
                row(1, "手机上的追问", 1_000),
                row(2, "追问的回答", 2_000),
                row(3, "PC 的新消息", 3_000),
                row(4, "PC 的新回答", 4_000),
            ),
            requestStartedAt = 0L,
        )

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals(listOf("手机上的追问", "追问的回答", "PC 的新消息", "PC 的新回答"), messages.map { it.text })
    }

    @Test fun aSnapshotBehindTheLocalTurnStillKeepsTheTranscriptOnReopen() = runTest {
        val (store, events) = store()
        val key = store.register("s1", "personal")
        store.acceptHistory(
            key,
            listOf(row(1, "旧问", 1_000), row(2, "旧答", 2_000)),
            requestStartedAt = 0L,
        )
        store.beginPrompt(key, "手机上的追问")
        events.emit(event("message.complete", "s1", "追问的回答"))
        runCurrent()

        // REST has not persisted the phone's turn yet; the re-entry fetch must not roll it back.
        store.acceptHistory(
            key,
            listOf(row(1, "旧问", 1_000), row(2, "旧答", 2_000)),
            requestStartedAt = 0L,
        )

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals("追问的回答", messages.last().text)
        assertTrue(messages.any { it.text == "手机上的追问" })
    }

    @Test fun aStrictlyAheadSnapshotRetiresAStaleLocalLeadInTheLadder() = runTest {
        val sessions = mockk<SessionRepository>()
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.setVisible(key, true)
        store.acceptHistory(
            key,
            listOf(row(1, "旧问", 1_000), row(2, "旧答", 2_000)),
            requestStartedAt = 0L,
        )
        store.beginPrompt(key, "手机上的追问")
        events.emit(event("message.complete", "s1", "追问的回答"))
        runCurrent()
        val now = System.currentTimeMillis()
        store.ageBeyondStaleLead()
        // The lead never persisted; hours later the PC went on without it.
        coEvery { sessions.history("s1", "personal", null) } returns listOf(
            row(1, "旧问", 1_000),
            row(2, "旧答", 2_000),
            row(3, "PC 的新消息", now + 9_000),
            row(4, "PC 的新回答", now + 10_000),
        )

        advanceTimeBy(300L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue("服务端领先且本地领先已过期：接受快照", messages.any { it.text == "PC 的新回答" })
        assertFalse("从未落库的本地行退场", messages.any { it.text == "追问的回答" })
    }

    @Test fun aRecentLocalLeadStillRefusesTheSnapshotInTheLadder() = runTest {
        val sessions = mockk<SessionRepository>()
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.setVisible(key, true)
        store.acceptHistory(
            key,
            listOf(row(1, "旧问", 1_000), row(2, "旧答", 2_000)),
            requestStartedAt = 0L,
        )
        store.beginPrompt(key, "手机上的追问")
        events.emit(event("message.complete", "s1", "追问的回答"))
        runCurrent()
        val now = System.currentTimeMillis()
        coEvery { sessions.history("s1", "personal", null) } returns listOf(
            row(1, "旧问", 1_000),
            row(2, "旧答", 2_000),
            row(3, "PC 的新消息", now + 9_000),
            row(4, "PC 的新回答", now + 10_000),
        )

        advanceTimeBy(15_000L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue("本地领先尚新：仍被保护", messages.any { it.text == "追问的回答" })
    }

    @Test fun reuseReconcileFetchesOncePerIntervalAndCatchesUp() = runTest {
        val sessions = mockk<SessionRepository>()
        coEvery { sessions.history("s1", "personal", null) } returns listOf(
            row(1, "旧问", 1_000),
            row(2, "旧答", 2_000),
            row(3, "PC 的新消息", 3_000),
            row(4, "PC 的新回答", 4_000),
        )
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.acceptHistory(
            key,
            listOf(row(1, "旧问", 1_000), row(2, "旧答", 2_000)),
            requestStartedAt = 0L,
        )

        store.requestReuseReconcile(key)
        store.requestReuseReconcile(key)
        advanceTimeBy(11_000L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertTrue(messages.any { it.text == "PC 的新回答" })
        coVerify(exactly = 1) { sessions.history("s1", "personal", null) }
    }
}
