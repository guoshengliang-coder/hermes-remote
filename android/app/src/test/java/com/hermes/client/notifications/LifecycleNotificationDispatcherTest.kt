package com.hermes.client.notifications

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inbox folds cross-device run state into the shared session state and only then projects the
 * shade (HG-142). The two halves must stay separable: turning notifications off may remove the
 * cards, never the state sync — the fetch loops in GatewayConnectionService and
 * LifecycleEventJobService therefore run unconditionally, and this pins the dispatcher half of
 * that contract so a future gate cannot reappear one layer down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleNotificationDispatcherTest {

    private fun lifecycle(kind: String, sessionId: String) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "event-$kind-$sessionId",
        deviceId = "mac-mini",
        profile = "personal",
        runtimeSessionId = "runtime-$sessionId",
        storedSessionId = sessionId,
        event = kind,
        state = when (kind) {
            "run.completed" -> "idle"
            else -> "working"
        },
        occurredAt = "2026-09-26T13:31:57.000Z",
    )

    @Test fun dispatchFoldsRunStateEvenWhenTheShadeHasNothingToSay() = runTest {
        val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
        val chat = mockk<ChatRepository>(relaxed = true)
        every { chat.events } returns events
        every { chat.connectionState } returns MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val profiles = mockk<ProfileManager>(relaxed = true)
        every { profiles.active } returns MutableStateFlow<String?>("personal")
        val store = SessionRuntimeStore(
            chatRepository = chat,
            appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            profiles = profiles,
        )
        // The shade side is a no-op here: notifications off, or nothing worth a card. The fold
        // must not depend on it.
        val coordinator = mockk<SessionNotificationCoordinator>(relaxed = true)
        val dispatcher = LifecycleNotificationDispatcher(runtimes = store, notifications = coordinator)
        val key = store.register("s1", "personal")

        dispatcher.dispatch(listOf(lifecycle("run.started", "s1")))
        advanceUntilIdle()

        assertEquals(
            "通知关闭也不能断掉跨设备运行状态的折叠（HG-142）",
            SessionRunPhase.THINKING,
            store.runtimes.value.getValue(key).phase,
        )
    }
}
