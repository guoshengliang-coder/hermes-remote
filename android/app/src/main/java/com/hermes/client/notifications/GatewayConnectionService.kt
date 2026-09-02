package com.hermes.client.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the gateway WebSocket connected while a phone-started run is in
 * flight (or the Real-time strategy is selected) and polls the Relay inbox. It posts no session
 * cards itself: events fold into [com.hermes.client.data.progress.SessionRuntimeStore] and the
 * [SessionNotificationCoordinator] projects the shade from there, so cards outlive this service.
 */
@AndroidEntryPoint
class GatewayConnectionService : Service() {
    @Inject lateinit var client: HermesGatewayClient
    @Inject lateinit var settings: NotificationSettings
    @Inject lateinit var notifier: HermesNotifier
    @Inject lateinit var profiles: com.hermes.client.data.repository.ProfileManager
    @Inject lateinit var lifecycleEvents: com.hermes.client.data.repository.LifecycleEventRepository
    @Inject lateinit var lifecycleDispatcher: LifecycleNotificationDispatcher
    @Inject lateinit var runtimes: com.hermes.client.data.progress.SessionRuntimeStore

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    // Latest notification prefs, kept current by a collector so the poll loop never blocks on
    // DataStore. @Volatile for cross-thread visibility (scope has no single-thread dispatcher).
    @Volatile private var latestPrefs = NotificationPrefs()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification(activeCount()))
        client.connect()
        // START_STICKY means Android can recreate this service headlessly (no UI ever launched),
        // in which case ProfileManager's list is still empty — nothing but UI code ever calls
        // refresh(). Seed it here so a bare-restart run's cards still know whether to show the
        // identity name. Non-blocking and runCatching-wrapped: a gateway unreachable at boot must
        // not crash the service.
        if (profiles.active.value == null) {
            scope.launch { runCatching { profiles.refresh() } }
        }
        scope.launch { settings.prefs.collect { latestPrefs = it } }
        // Keep the ongoing service card honest about how many sessions it is watching.
        scope.launch {
            runtimes.runtimes.map { map -> map.values.count { it.hasActiveWork } }
                .distinctUntilChanged()
                .collect { count ->
                    runCatching {
                        NotificationManagerCompat.from(this@GatewayConnectionService)
                            .notify(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification(count))
                    }
                }
        }
        scope.launch {
            while (currentCoroutineContext().isActive) {
                val moreAvailable = if (latestPrefs.enabled) {
                    runCatching {
                        lifecycleEvents.sync { batch -> lifecycleDispatcher.dispatch(batch) }.moreAvailable
                    }.getOrDefault(false)
                } else false
                if (!moreAvailable) delay(LIFECYCLE_POLL_MS)
            }
        }
    }

    private fun activeCount(): Int = runtimes.runtimes.value.values.count { it.hasActiveWork }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ (API 35) caps a dataSync foreground service at ~6h and calls this instead of
    // just killing the process; Android 16 (API 36) added a fgsType-aware overload. Implement
    // both so whichever the OS invokes stops the service cleanly rather than crashing/ANR-ing.
    // Deliberately no auto-restart here (deferred) — just let the OS stop us.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        // Session cards belong to the coordinator and must survive the service: a completed card
        // posted seconds before the service stops is exactly what the user comes back to.
        scope.cancel()
        // LifecycleMonitoringCoordinator owns socket suspension. Do not close here: Service
        // destruction can be delivered after ON_START, and an old service closing the singleton
        // client would race and kill the newly restored foreground connection.
        super.onDestroy()
    }

    companion object {
        private const val LIFECYCLE_POLL_MS = 3_000L

        fun start(context: Context) {
            val i = Intent(context, GatewayConnectionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayConnectionService::class.java))
        }
    }
}
