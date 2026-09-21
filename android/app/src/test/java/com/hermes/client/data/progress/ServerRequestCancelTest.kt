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
}
