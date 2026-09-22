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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HG-104: history arrives as the newest page, not the whole transcript. Reconciliation must judge a
 * snapshot on the rows it spans, keep the older rows the reader already has, and an older page
 * must slot in above them without touching what is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedHistoryReconcileTest {
    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
            if (type == "message.start") put("message_id", "agent")
        },
    )

    private fun row(serverId: Long, text: String = "m$serverId") = ChatMessage(
        id = "h-$serverId",
        role = if (serverId % 2L == 1L) Role.USER else Role.ASSISTANT,
        text = text,
        timestamp = serverId,
        serverId = serverId,
    )

    private fun TestScope.store(sessions: SessionRepository?): Pair<SessionRuntimeStore, MutableSharedFlow<ServerEvent>> {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        return SessionRuntimeStore(chat, scope, profiles, sessionRepository = sessions) to events
    }

    @Test fun a_tail_page_is_accepted_and_the_older_rows_on_screen_stay() = runTest {
        val sessions = mockk<SessionRepository>()
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.setVisible(key, true)
        // The reader holds rows 1..6 (an older page plus the tail they opened on).
        store.acceptHistory(key, (1L..6L).map { row(it) }, requestStartedAt = 0L)
        store.beginPrompt(key, "新问题")
        events.emit(event("message.complete", "s1", "新答案"))
        runCurrent()
        // The repository answers with the newest page only — here, rows 5..8. Judged on the whole
        // transcript it holds two user turns where the phone counted four, and every rung of the
        // ladder rejected it.
        coEvery { sessions.history("s1", "personal", null) } returns
            listOf(row(5), row(6), row(7, "新问题"), row(8, "新答案"))

        advanceTimeBy(300L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals((1L..8L).toList(), messages.map { it.serverId })
        assertEquals(listOf("m1", "m2", "m3", "m4"), messages.take(4).map { it.text })
        assertEquals("新答案", messages.last().text)
        advanceTimeBy(15_000L)
        runCurrent()
        coVerify(exactly = 1) { sessions.history("s1", "personal", null) }
    }

    @Test fun a_tail_after_a_gap_replaces_what_is_held() = runTest {
        val sessions = mockk<SessionRepository>()
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.setVisible(key, true)
        store.acceptHistory(key, (1L..4L).map { row(it) }, requestStartedAt = 0L)
        store.beginPrompt(key, "新问题")
        events.emit(event("message.complete", "s1", "新答案"))
        runCurrent()
        // Far more than a page happened elsewhere; the newest page shares no row with the phone.
        coEvery { sessions.history("s1", "personal", null) } returns
            listOf(row(201), row(202), row(203, "新问题"), row(204, "新答案"))

        advanceTimeBy(300L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals("rows 1..4 cannot sit directly above 201", (201L..204L).toList(), messages.map { it.serverId })
        coVerify(exactly = 1) { sessions.history("s1", "personal", null) }
    }

    @Test fun a_stale_tail_is_still_refused() = runTest {
        val sessions = mockk<SessionRepository>()
        val (store, events) = store(sessions)
        val key = store.register("s1", "personal")
        store.setVisible(key, true)
        store.acceptHistory(key, (1L..6L).map { row(it) }, requestStartedAt = 0L)
        store.beginPrompt(key, "新问题")
        events.emit(event("message.complete", "s1", "新答案"))
        runCurrent()
        // Hermes has not committed the new turn yet: the tail ends at row 6.
        coEvery { sessions.history("s1", "personal", null) } returns listOf(row(5), row(6))

        advanceTimeBy(300L)
        runCurrent()

        val messages = store.runtimes.value.getValue(key).chat.messages
        assertEquals("新答案", messages.last().text)
        assertEquals(8, messages.size)
    }

    @Test fun an_older_page_is_prepended_without_touching_the_rows_on_screen() = runTest {
        val (store, _) = store(null)
        val key = store.register("s1", "personal")
        store.acceptHistory(key, (5L..8L).map { row(it) }, requestStartedAt = 0L)
        val before = store.runtimes.value.getValue(key).chat.messages

        // The merged transcript: rows 1..8, with the tail rows mapped afresh (new ids).
        val merged = (1L..8L).map { row(it).copy(id = "h-fresh-$it") }
        val added = store.prependOlderHistory(key, merged)

        val after = store.runtimes.value.getValue(key).chat.messages
        assertEquals(4, added)
        assertEquals((1L..8L).toList(), after.map { it.serverId })
        assertTrue("rows already shown keep their instances and ids", after.drop(4) == before)
        assertEquals(0, store.prependOlderHistory(key, merged))
    }

    @Test fun a_reconcile_after_prepending_keeps_every_id() = runTest {
        val (store, _) = store(null)
        val key = store.register("s1", "personal")
        store.acceptHistory(key, (5L..8L).map { row(it).copy(id = "tail-$it") }, requestStartedAt = 0L)
        store.prependOlderHistory(key, (1L..8L).map { row(it).copy(id = "older-$it") })
        val ids = store.runtimes.value.getValue(key).chat.messages.map { it.id }

        store.acceptManualHistory(key, (1L..8L).map { row(it).copy(id = "rest-$it") })

        assertEquals(ids, store.runtimes.value.getValue(key).chat.messages.map { it.id })
    }
}
