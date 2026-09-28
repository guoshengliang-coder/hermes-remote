package com.hermes.client.notifications

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.LifecycleEventRepository
import com.hermes.client.data.repository.NotificationMonitoringStrategy
import com.hermes.client.data.repository.NotificationMonitoringStrategyStore
import com.hermes.client.data.repository.NotificationSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The monitoring loop is the only thing that restores the socket when the app returns to the
 * foreground, so a single failing step must never terminate it (problem E of the
 * background-connection review: Android 12+ can refuse a background foreground-service start,
 * and the crash took every later mode change down with it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleMonitoringCoordinatorTest {

    @Test fun foregroundArrivingAfterIdleCheckReconnectsAfterClose() = runTest {
        val settings = mockk<NotificationSettings>(relaxed = true)
        every { settings.prefs } returns MutableStateFlow(NotificationPrefs(enabled = true))
        val strategyStore = mockk<NotificationMonitoringStrategyStore>(relaxed = true)
        every { strategyStore.strategy } returns MutableStateFlow(NotificationMonitoringStrategy.ADAPTIVE)
        val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)
        every { runtimeStore.runtimes } returns MutableStateFlow(emptyMap())
        every { runtimeStore.visibleSessions } returns MutableStateFlow(emptySet())
        val accountSessions = mockk<AccountSessionManager>(relaxed = true)
        every { accountSessions.hasLoadedConnection() } returns true
        val gateway = mockk<HermesGatewayClient>(relaxed = true)
        val job = SupervisorJob()
        val coordinator = LifecycleMonitoringCoordinator(
            context = RuntimeEnvironment.getApplication(), settings = settings, runtimes = runtimeStore,
            events = mockk(relaxed = true), dispatcher = mockk(relaxed = true),
            strategyStore = strategyStore, gatewayClient = gateway,
            credentials = mockk(relaxed = true), accountSessions = accountSessions,
            appScope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
        )
        @Suppress("UNCHECKED_CAST")
        val foreground = LifecycleMonitoringCoordinator::class.java.getDeclaredField("foreground")
            .apply { isAccessible = true }.get(coordinator) as MutableStateFlow<Boolean>
        coordinator.lifecycleSeam = { name ->
            if (name == "idle:after-ownership-check") foreground.value = true
        }
        try {
            coordinator.start()
            runCurrent()
            advanceTimeBy(45_000)
            runCurrent()
            verify(exactly = 1) { gateway.close("app idle in the background") }
            verify(exactly = 1) { gateway.connect() }
        } finally {
            job.cancel()
        }
    }

    @Test fun activeWorkArrivingAfterIdleCheckRestoresSocketAfterClose() = runTest {
        mockkObject(GatewayConnectionService.Companion)
        every { GatewayConnectionService.start(any()) } returns true
        val settings = mockk<NotificationSettings>(relaxed = true)
        every { settings.prefs } returns MutableStateFlow(NotificationPrefs(enabled = true))
        val strategyStore = mockk<NotificationMonitoringStrategyStore>(relaxed = true)
        every { strategyStore.strategy } returns MutableStateFlow(NotificationMonitoringStrategy.ADAPTIVE)
        val runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())
        val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)
        every { runtimeStore.runtimes } returns runtimes
        every { runtimeStore.visibleSessions } returns MutableStateFlow(emptySet())
        val gateway = mockk<HermesGatewayClient>(relaxed = true)
        val job = SupervisorJob()
        val coordinator = LifecycleMonitoringCoordinator(
            context = RuntimeEnvironment.getApplication(), settings = settings, runtimes = runtimeStore,
            events = mockk(relaxed = true), dispatcher = mockk(relaxed = true),
            strategyStore = strategyStore, gatewayClient = gateway,
            credentials = mockk(relaxed = true),
            appScope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
        )
        coordinator.lifecycleSeam = { name ->
            if (name == "idle:after-ownership-check") {
                val key = SessionRuntimeKey("personal", "s1")
                runtimes.value = mapOf(key to SessionRuntime(
                    key = key, phase = SessionRunPhase.STREAMING, startedLocally = true,
                ))
            }
        }
        try {
            coordinator.start()
            runCurrent()
            advanceTimeBy(45_000)
            runCurrent()
            verify(exactly = 1) { gateway.close("app idle in the background") }
            verify(exactly = 1) { gateway.connect() }
        } finally {
            job.cancel()
        }
    }

    @Test fun foregroundReturnWinsOverExpiredBackgroundGrace() = runTest {
        val prefs = MutableStateFlow(NotificationPrefs(enabled = true))
        val settings = mockk<NotificationSettings>(relaxed = true)
        every { settings.prefs } returns prefs
        val strategyStore = mockk<NotificationMonitoringStrategyStore>(relaxed = true)
        every { strategyStore.strategy } returns MutableStateFlow(NotificationMonitoringStrategy.ADAPTIVE)
        val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)
        every { runtimeStore.runtimes } returns MutableStateFlow(emptyMap())
        every { runtimeStore.visibleSessions } returns MutableStateFlow(emptySet())
        val gateway = mockk<HermesGatewayClient>(relaxed = true)
        val job = SupervisorJob()
        val coordinator = LifecycleMonitoringCoordinator(
            context = RuntimeEnvironment.getApplication(), settings = settings, runtimes = runtimeStore,
            events = mockk(relaxed = true), dispatcher = mockk(relaxed = true),
            strategyStore = strategyStore, gatewayClient = gateway,
            credentials = mockk(relaxed = true),
            appScope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
        )
        try {
            coordinator.start()
            runCurrent() // IDLE_BACKGROUND starts its 45-second grace.
            advanceTimeBy(45_000)
            // Put ON_START's foreground update between the timer becoming due and its continuation.
            @Suppress("UNCHECKED_CAST")
            val foreground = LifecycleMonitoringCoordinator::class.java.getDeclaredField("foreground")
                .apply { isAccessible = true }.get(coordinator) as MutableStateFlow<Boolean>
            foreground.value = true
            runCurrent()
            verify(exactly = 0) { gateway.close("app idle in the background") }
        } finally {
            job.cancel()
        }
    }

    @Test fun aFailingModeStepDoesNotTerminateTheMonitoringLoop() = runTest {
        val prefs = MutableStateFlow(NotificationPrefs(enabled = false))
        val strategy = MutableStateFlow(NotificationMonitoringStrategy.ADAPTIVE)
        val runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())

        val settings = mockk<NotificationSettings>(relaxed = true)
        every { settings.prefs } returns prefs
        val strategyStore = mockk<NotificationMonitoringStrategyStore>(relaxed = true)
        every { strategyStore.strategy } returns strategy
        val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)
        every { runtimeStore.runtimes } returns runtimes
        every { runtimeStore.visibleSessions } returns MutableStateFlow(emptySet())

        val gateway = mockk<HermesGatewayClient>(relaxed = true)
        var closes = 0
        every { gateway.close(any()) } answers {
            closes += 1
            // Stand in for any step that can blow up mid-decision (a refused foreground-service
            // start, a JobScheduler or keystore failure): the first one throws, later ones do not.
            if (closes == 1) throw IllegalStateException("close failed")
        }

        val coordinator = LifecycleMonitoringCoordinator(
            context = RuntimeEnvironment.getApplication(),
            settings = settings,
            runtimes = runtimeStore,
            events = mockk<LifecycleEventRepository>(relaxed = true),
            dispatcher = mockk<LifecycleNotificationDispatcher>(relaxed = true),
            strategyStore = strategyStore,
            gatewayClient = gateway,
            credentials = mockk<CredentialStore>(relaxed = true),
            appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        coordinator.start()

        // Notifications off, nothing running, app backgrounded → DISABLED, which closes the socket
        // once the grace period elapses. That first close throws.
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // A later decision must still be served: the loop survived the failure above.
        prefs.value = NotificationPrefs(enabled = true)
        strategy.value = NotificationMonitoringStrategy.POWER_SAVING
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        verify(atLeast = 2) { gateway.close(any()) }
    }

    /**
     * When Android refuses the foreground service there is nothing protecting the process, so the
     * run's socket is held on a lease rather than indefinitely (decision D4: five minutes).
     */
    @Test fun aRefusedForegroundServiceHoldsTheSocketOnACappedLease() = runTest {
        mockkObject(GatewayConnectionService.Companion)
        every { GatewayConnectionService.start(any()) } returns false

        val prefs = MutableStateFlow(NotificationPrefs(enabled = false))
        val strategy = MutableStateFlow(NotificationMonitoringStrategy.ADAPTIVE)
        val running = SessionRuntimeKey("personal", "s1") to SessionRuntime(
            key = SessionRuntimeKey("personal", "s1"),
            phase = SessionRunPhase.STREAMING,
            startedLocally = true,
        )
        val runtimes = MutableStateFlow(mapOf(running))

        val settings = mockk<NotificationSettings>(relaxed = true)
        every { settings.prefs } returns prefs
        val strategyStore = mockk<NotificationMonitoringStrategyStore>(relaxed = true)
        every { strategyStore.strategy } returns strategy
        val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)
        every { runtimeStore.runtimes } returns runtimes
        every { runtimeStore.visibleSessions } returns MutableStateFlow(emptySet())
        val gateway = mockk<HermesGatewayClient>(relaxed = true)

        LifecycleMonitoringCoordinator(
            context = RuntimeEnvironment.getApplication(),
            settings = settings,
            runtimes = runtimeStore,
            events = mockk<LifecycleEventRepository>(relaxed = true),
            dispatcher = mockk<LifecycleNotificationDispatcher>(relaxed = true),
            strategyStore = strategyStore,
            gatewayClient = gateway,
            credentials = mockk<com.hermes.client.data.auth.CredentialStore>(relaxed = true),
            appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        ).start()

        // Notifications are off and a locally started run is in flight: R1 puts this in
        // ACTIVE_BACKGROUND, and the refused service must not turn into an immediate disconnect.
        runCurrent()
        advanceTimeBy(4 * 60_000)
        runCurrent()
        verify(exactly = 0) { gateway.close(any()) }

        advanceTimeBy(2 * 60_000)
        runCurrent()
        verify(exactly = 1) { gateway.close("keep-alive lease expired") }
    }

    @After fun tearDown() = unmockkAll()
}
