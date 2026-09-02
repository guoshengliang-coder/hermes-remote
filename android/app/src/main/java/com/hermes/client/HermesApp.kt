package com.hermes.client

import android.app.Application
import com.hermes.client.data.diagnostics.CrashReporter
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.SettingsStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import androidx.glance.appwidget.updateAll
import com.hermes.client.widget.HermesWidget

@HiltAndroidApp
class HermesApp : Application() {
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var lifecycleMonitoring: com.hermes.client.notifications.LifecycleMonitoringCoordinator
    @Inject lateinit var modelCatalog: com.hermes.client.data.repository.ModelCatalogStore
    @Inject lateinit var notifier: com.hermes.client.notifications.HermesNotifier
    @Inject lateinit var sessionNotifications: com.hermes.client.notifications.SessionNotificationCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a file so the next launch can surface the trace (no adb).
        CrashReporter.install(this)
        // Channels must exist even when Smart/Power-saving mode never starts the foreground
        // service; creating channels is local-only and does not open a network connection.
        notifier.ensureChannels()
        // Restore the diagnostic-logging toggle at launch so capture is active before the
        // Diagnostics screen is ever opened (e.g. to catch a failure on the first session open).
        settingsStore.debugLogging
            .distinctUntilChanged()
            .onEach { DebugLog.setEnabled(it) }
            .launchIn(appScope)
        // Notification channels live outside Compose and Android keeps them after creation. Re-run
        // channel creation when the in-app language changes so their names follow the user's choice.
        settingsStore.appLanguage
            .distinctUntilChanged()
            .onEach {
                notifier.ensureChannels(it)
                HermesWidget().updateAll(this)
            }
            .launchIn(appScope)
        lifecycleMonitoring.start()
        // One card per session, projected from the runtime store; must start on the main thread.
        sessionNotifications.start()
        // Keep the model catalog warm: refresh in the background on every start/foreground so
        // the model picker opens instantly from cache instead of showing a loading state.
        modelCatalog.start()
    }
}
