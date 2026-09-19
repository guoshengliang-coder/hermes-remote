package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.RELAY_RESPONSE_TOO_LARGE_CODE
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * HG-65: the phone asked for an answer it could not receive, forever.
 *
 * One conversation's `session.resume` answer had grown to 26.3 MiB — Hermes stores an attachment as
 * a reference but re-inlines it as base64 on every read, so 75 rasterised PDF pages came back with
 * the transcript. The Connector could not relay a frame that size, and the client asked again on
 * every reconnect: 234 `session.resume` calls, zero answers, in one log.
 *
 * With the Connector's side of the fix the tunnel survives and the call is refused instead
 * (`-32001`). These tests cover the client's half: stop asking a question whose answer cannot
 * arrive, and recover the conversation the other way — through history, which the relay's frame
 * ceiling does not apply to because REST responses are chunked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UndeliverableResumeTest {
    private data class Fixture(
        val store: SessionRuntimeStore,
        val chat: ChatRepository,
        val sessions: SessionRepository,
        val connection: MutableStateFlow<ConnectionState>,
        val events: MutableSharedFlow<ServerEvent>,
        val scope: CoroutineScope,
    )

    /**
     * Run [body] against a fresh store and shut the store down afterwards.
     *
     * The shutdown is not ceremony. These tests deliberately leave a run in flight, and
     * `scheduleProcessPolling` keeps re-polling on a `delay` for exactly as long as the runtime has
     * active work — so a store still alive when `runTest` drains the shared virtual clock is a loop
     * that never ends. It does not fail the test; it burns a core until someone notices.
     */
    private fun storeTest(body: suspend kotlinx.coroutines.test.TestScope.(Fixture) -> Unit) = runTest {
        val f = fixture()
        try {
            body(f)
        } finally {
            f.scope.cancel()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        every { chat.events } returns events
        every { chat.connectionState } returns connection
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val sessions = mockk<SessionRepository>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SessionRuntimeStore(chat, scope, profiles, sessionRepository = sessions)
        runCurrent()
        return Fixture(store, chat, sessions, connection, events, scope)
    }

    private fun event(type: String, sessionId: String) = ServerEvent(
        type = type, sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            if (type == "message.start") put("message_id", "agent")
        },
    )

    /** Put [sessionId] into a run, so a reconnect has something to resume. */
    private suspend fun Fixture.startRun(sessionId: String) {
        store.bindLiveHandle(SessionRuntimeKey("personal", sessionId), sessionId)
        events.emit(event("message.start", sessionId))
    }

    private fun kotlinx.coroutines.test.TestScope.reconnect(f: Fixture) {
        f.connection.value = ConnectionState.Reconnecting
        runCurrent()
        f.connection.value = ConnectionState.Connected
        runCurrent()
    }

    @Test
    fun `a resume whose answer cannot be relayed is not asked again on the next reconnect`() = storeTest { f ->
        val session = "20260918_204034_16def7"
        coEvery { f.chat.resume(session, "personal") } throws
            GatewayRpcException(RELAY_RESPONSE_TOO_LARGE_CODE, "response is 27577909 bytes")
        f.startRun(session)

        reconnect(f)
        reconnect(f)
        reconnect(f)

        // Once, on the reconnect that discovered it. The two after that are the ones that used to
        // cost the Mac seconds of re-reading images to build a reply nobody could receive.
        coVerify(exactly = 1) { f.chat.resume(session, "personal") }
    }

    @Test
    fun `an ordinary resume failure keeps being retried`() = storeTest { f ->
        val session = "20260918_175751_67f262"
        // Losing the socket mid-call is transient by nature: the next connection is a new chance,
        // and suppressing it would strand a conversation that has nothing wrong with it.
        coEvery { f.chat.resume(session, "personal") } throws GatewayRpcException(0, "not connected")
        f.startRun(session)

        reconnect(f)
        reconnect(f)

        coVerify(exactly = 2) { f.chat.resume(session, "personal") }
    }

    @Test
    fun `history is still reconciled for a conversation whose resume cannot be delivered`() = storeTest { f ->
        val session = "20260918_204034_16def7"
        coEvery { f.chat.resume(session, "personal") } throws
            GatewayRpcException(RELAY_RESPONSE_TOO_LARGE_CODE, "response is 27577909 bytes")
        f.startRun(session)

        reconnect(f)
        reconnect(f)

        // Let the reconcile ladder's first rung fire.
        advanceTimeBy(500)
        runCurrent()

        // The skip must not also skip the recovery: history is what decides whether the run
        // finished, and it reaches the phone over a path the frame ceiling does not apply to.
        coVerify(atLeast = 1) { f.sessions.history(session, "personal", any()) }
    }

    @Test
    fun `a resume that lands clears the suppression`() = storeTest { f ->
        val session = "20260918_204034_16def7"
        val key = SessionRuntimeKey("personal", session)
        coEvery { f.chat.resume(session, "personal") } throws
            GatewayRpcException(RELAY_RESPONSE_TOO_LARGE_CODE, "response is 27577909 bytes")
        f.startRun(session)
        reconnect(f)

        // The user opened the conversation and its resume got through — whatever made the answer
        // undeliverable is no longer true, so the automatic path may ask again.
        f.store.bindLiveHandle(key, "live-handle")
        runCurrent()
        f.store.bindLiveHandle(key, session)
        f.events.emit(event("message.start", session))
        reconnect(f)

        coVerify(exactly = 2) { f.chat.resume(session, "personal") }
    }
}
