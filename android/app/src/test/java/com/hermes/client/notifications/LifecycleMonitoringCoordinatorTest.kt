package com.hermes.client.notifications

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.LifecycleEventRepository
import com.hermes.client.data.repository.NotificationMonitoringStrategy
import com.hermes.client.data.repository.NotificationMonitoringStrategyStore
import com.hermes.client.data.repository.NotificationSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        every { gateway.close() } answers {
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

        verify(atLeast = 2) { gateway.close() }
    }
}
