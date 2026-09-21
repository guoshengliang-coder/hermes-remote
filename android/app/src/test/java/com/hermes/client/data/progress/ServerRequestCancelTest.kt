package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.ServerRequests
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `request.cancel` on the newer Hermes: the card it names goes away (and with it the notification,
 * which is projected from this state), the run stops claiming to wait on the user, and the store
 * asks Hermes what the run did next instead of guessing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRequestCancelTest {

    private fun approvalRequest(sessionId: String, id: String) =
        ServerRequests.toEvent(
            JsonPrimitive(id), "approval",
            buildJsonObject {
                put("session_id", sessionId)
                put("request_id", "q-1")
                put("command", "rm -rf build")
            },
        )!!

    private fun cancel(sessionId: String, id: String) = ServerEvent(
        type = "request.cancel",
        sessionId = sessionId,
        payload = buildJsonObject {
            put("id", id)
            put("method", "approval")
            put("reason", "timeout")
        },
    )

    private fun start(sessionId: String) = ServerEvent(
        "message.start", sessionId, buildJsonObject { put("session_id", sessionId); put("message_id", "a") },
    )

    @Test fun a_cancel_tears_down_the_named_card_and_probes_the_run() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        // The probe's answer is not what is under test; hold it so the phase the cancel itself set
        // can be read before anything the probe learns overwrites it.
        coEvery { chat.resume(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(chatRepository = chat, appScope = scope, profiles = profiles)

        val key = store.register("s1", "personal")
        store.beginPrompt(key, "清理构建目录")
        events.emit(start("s1"))
        events.emit(approvalRequest("s1", "srq-0123456789ab"))
        advanceUntilIdle()
        val waiting = store.runtimes.value.getValue(key)
        assertEquals(SessionRunPhase.WAITING_APPROVAL, waiting.phase)
        assertEquals("srq-0123456789ab", waiting.chat.pendingApproval?.serverRequestId)

        // A cancel for some other request leaves the card alone.
        events.emit(cancel("s1", "srq-ffffffffffff"))
        advanceUntilIdle()
        assertNotNull(store.runtimes.value.getValue(key).chat.pendingApproval)
        coVerify(exactly = 0) { chat.resume("s1", any()) }

        events.emit(cancel("s1", "srq-0123456789ab"))
        advanceUntilIdle()
        val after = store.runtimes.value.getValue(key)
        assertNull(after.chat.pendingApproval)
        assertEquals(SessionRunPhase.THINKING, after.phase)
        coVerify { chat.resume("s1", "personal") }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** With two approvals open, answering the first must leave the run waiting on the second. */
    @Test fun answering_one_of_two_queued_approvals_keeps_the_run_waiting_on_the_next() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(chatRepository = chat, appScope = scope, profiles = profiles)

        val key = store.register("s1", "personal")
        store.beginPrompt(key, "清理")
        events.emit(start("s1"))
        events.emit(approvalRequest("s1", "srq-aaaaaaaaaaaa"))
        events.emit(approvalRequest("s1", "srq-bbbbbbbbbbbb"))
        advanceUntilIdle()
        assertEquals("srq-aaaaaaaaaaaa", store.runtimes.value.getValue(key).chat.pendingApproval?.serverRequestId)

        // What the notification shade does after a successful answer.
        store.settleShadeAnswer(
            key, ShadeAnswer(approval = true, requestId = "srq-aaaaaaaaaaaa", serverRequest = true),
            expired = false, language = com.hermes.client.ui.localization.AppLanguage.EN,
        )
        val after = store.runtimes.value.getValue(key)
        assertEquals("srq-bbbbbbbbbbbb", after.chat.pendingApproval?.serverRequestId)
        assertEquals(SessionRunPhase.WAITING_APPROVAL, after.phase)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun storeWith(events: MutableSharedFlow<ServerEvent>, scope: CoroutineScope): SessionRuntimeStore {
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        return SessionRuntimeStore(chatRepository = chat, appScope = scope, profiles = profiles)
    }

    private val en = com.hermes.client.ui.localization.AppLanguage.EN

    /**
     * X answered elsewhere, Y queued behind it and now on screen, the notification still showing X:
     * tapping X's button must settle X, not pop Y unanswered.
     */
    @Test fun a_stale_shade_answer_settles_its_own_approval_not_the_one_now_showing() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = storeWith(events, scope)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "清理")
        events.emit(start("s1"))
        events.emit(approvalRequest("s1", "srq-xxxxxxxxxxxx"))
        events.emit(approvalRequest("s1", "srq-yyyyyyyyyyyy"))
        events.emit(cancel("s1", "srq-xxxxxxxxxxxx"))
        advanceUntilIdle()
        assertEquals("srq-yyyyyyyyyyyy", store.runtimes.value.getValue(key).chat.pendingApproval?.serverRequestId)

        store.settleShadeAnswer(key, ShadeAnswer(true, "srq-xxxxxxxxxxxx", true), expired = true, language = en)
        val after = store.runtimes.value.getValue(key)
        assertEquals("srq-yyyyyyyyyyyy", after.chat.pendingApproval?.serverRequestId)
        assertEquals(SessionRunPhase.WAITING_APPROVAL, after.phase)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** A lost action is never silent: an expired shade answer leaves its notice in the conversation. */
    @Test fun an_expired_shade_answer_leaves_the_registered_notice_in_the_chat() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = storeWith(events, scope)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "清理")
        events.emit(start("s1"))
        events.emit(approvalRequest("s1", "srq-xxxxxxxxxxxx"))
        advanceUntilIdle()

        store.settleShadeAnswer(key, ShadeAnswer(true, "srq-xxxxxxxxxxxx", true), expired = true, language = en)
        val approvalNotice = store.runtimes.value.getValue(key).chat.messages.last()
        assertEquals(com.hermes.client.domain.Role.SYSTEM, approvalNotice.role)
        assertTrue(approvalNotice.text.contains("HR-APPROVAL-003"))

        store.updateChat(key) {
            it.copy(pendingClarify = com.hermes.client.ui.chat.ClarifyRequest(
                "srq-cccccccccccc", listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "Q?")), serverRequest = true,
            ))
        }
        store.settleShadeAnswer(key, ShadeAnswer(false, "srq-cccccccccccc", true, null, "a"), expired = true, language = en)
        val chat = store.runtimes.value.getValue(key).chat
        assertNull(chat.pendingClarify)
        assertTrue(chat.messages.last().text.contains("HR-CLARIFY-001"))
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** A clarify answered from an old notification must not touch a different question now showing. */
    @Test fun a_shade_clarify_answer_for_another_request_leaves_the_current_one_alone() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = storeWith(events, scope)
        val key = store.register("s1", "personal")
        store.beginPrompt(key, "部署")
        val current = com.hermes.client.ui.chat.ClarifyRequest(
            "srq-newnewnewnew", listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "Now?")), serverRequest = true,
        )
        store.updateChat(key) { it.copy(pendingClarify = current) }

        store.settleShadeAnswer(key, ShadeAnswer(false, "srq-oldoldoldold", true, null, "a"), expired = true, language = en)
        assertEquals(current, store.runtimes.value.getValue(key).chat.pendingClarify)
        store.settleShadeAnswer(key, ShadeAnswer(false, "srq-oldoldoldold", true, null, "a"), expired = false, language = en)
        assertEquals(current, store.runtimes.value.getValue(key).chat.pendingClarify)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
