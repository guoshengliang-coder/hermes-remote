package com.hermes.client.notifications

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.LifecycleEventRepository
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.data.repository.NotificationMonitoringStrategyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class MonitoringDecision(
    val mode: LifecycleMonitoringMode,
    val prefs: NotificationPrefs,
    val appInForeground: Boolean,
)

/**
 * Battery-aware process coordinator.
 *
 * Foreground: responsive two-second Relay inbox sync. Background with a phone-started run: the
 * existing foreground service remains alive. Background idle: no socket or app timer; Android's
 * persisted JobScheduler wakeup is the fallback until FCM is configured.
 */
@Singleton
class LifecycleMonitoringCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: NotificationSettings,
    private val runtimes: SessionRuntimeStore,
    private val events: LifecycleEventRepository,
    private val dispatcher: LifecycleNotificationDispatcher,
    private val strategyStore: NotificationMonitoringStrategyStore,
    private val gatewayClient: HermesGatewayClient,
    private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val foreground = MutableStateFlow(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground.value = true
                Lifecycle.Event.ON_STOP -> foreground.value = false
                else -> Unit
            }
        })
        appScope.launch {
            combine(
                settings.prefs,
                foreground,
                runtimes.runtimes.map { map ->
                    map.values.any { it.startedLocally && it.hasActiveWork }
                }.distinctUntilChanged(),
                strategyStore.strategy,
            ) { prefs, appInForeground, activeLocalRun, strategy ->
                MonitoringDecision(
                    lifecycleMonitoringMode(prefs.enabled, appInForeground, activeLocalRun, strategy),
                    prefs,
                    appInForeground,
                )
            }.distinctUntilChanged().collectLatest { decision ->
                when (decision.mode) {
                    LifecycleMonitoringMode.DISABLED -> {
                        GatewayConnectionService.stop(context)
                        LifecycleEventJobScheduler.cancel(context)
                        if (!decision.appInForeground) gatewayClient.close()
                    }
                    LifecycleMonitoringMode.FOREGROUND -> {
                        GatewayConnectionService.stop(context)
                        LifecycleEventJobScheduler.cancel(context)
                        pollUntilModeChanges(decision.prefs, FOREGROUND_POLL_MS, appInForeground = true)
                    }
                    LifecycleMonitoringMode.ACTIVE_BACKGROUND -> {
                        LifecycleEventJobScheduler.cancel(context)
                        GatewayConnectionService.start(context)
                    }
                    LifecycleMonitoringMode.IDLE_BACKGROUND -> {
                        GatewayConnectionService.stop(context)
                        gatewayClient.close()
                        LifecycleEventJobScheduler.schedule(context)
                    }
                }
            }
        }
    }

    private suspend fun pollUntilModeChanges(
        prefs: NotificationPrefs,
        intervalMs: Long,
        appInForeground: Boolean,
    ) {
        while (currentCoroutineContext().isActive) {
            val moreAvailable = runCatching {
                val result = events.sync { batch -> dispatcher.dispatch(batch, prefs, appInForeground) }
                result.moreAvailable
            }.onFailure { DebugLog.log("lifecycle", "event sync failed: ${it.message}") }
                .getOrDefault(false)
            if (!moreAvailable) delay(intervalMs)
        }
    }

    private companion object {
        const val FOREGROUND_POLL_MS = 2_000L
    }
}
