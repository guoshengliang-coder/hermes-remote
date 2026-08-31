package com.hermes.client.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.data.repository.NotificationMonitoringStrategyStore
import com.hermes.client.data.progress.SessionRuntimeStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Quick Settings tile that toggles notifications — mirrors the Settings > Notifications switch.
 * On/off state is [NotificationSettings.prefs].enabled; the adaptive coordinator owns the service.
 * The DataStore *write* runs on a service-owned scope so it never blocks the main thread; the
 * click's decision, the activity/foreground-service start, and the tile re-render stay synchronous
 * because a TileService's start-from-background token is only valid during onClick's synchronous
 * body. The synchronous read hits DataStore's in-memory cache (warmed in onStartListening).
 */
@AndroidEntryPoint
class NotificationTileService : TileService() {
    @Inject lateinit var settings: NotificationSettings
    @Inject lateinit var strategies: NotificationMonitoringStrategyStore
    @Inject lateinit var runtimes: SessionRuntimeStore
    @Inject lateinit var languages: com.hermes.client.ui.localization.AppLanguageProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            strategies.strategy.first() // Warm both DataStore caches before a synchronous click.
            renderTile(settings.prefs.first().enabled)
        }
    }

    override fun onClick() {
        super.onClick()
        // Decide and start synchronously: the start-from-background token is only valid during
        // onClick's synchronous body, so the activity/service start must not sit behind a suspend.
        // The read is a warm in-memory cache hit; only the DataStore write is deferred to the scope.
        val enabled = runBlocking { settings.prefs.first().enabled }
        val canStart = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        when (tileClickAction(enabled, canStart)) {
            TileAction.ENABLE -> {
                val strategy = runBlocking { strategies.strategy.first() }
                val hasActiveLocalRun = runtimes.runtimes.value.values.any { it.hasActiveWork }
                if (lifecycleMonitoringMode(
                        notificationsEnabled = true,
                        appInForeground = false,
                        hasLocallyStartedRun = hasActiveLocalRun,
                        strategy = strategy,
                    ) == LifecycleMonitoringMode.ACTIVE_BACKGROUND
                ) {
                    // A Quick Settings click grants a short background-start allowance. Start
                    // synchronously only when the selected policy actually needs the service.
                    GatewayConnectionService.start(this)
                }
                renderTile(true)
                scope.launch { settings.setEnabled(true) }
            }
            TileAction.DISABLE -> {
                GatewayConnectionService.stop(this)
                renderTile(false)
                scope.launch { settings.setEnabled(false) }
            }
            TileAction.OPEN_FOR_PERMISSION -> openNotificationSettings()
        }
    }

    private fun renderTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Hermes"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_hermes)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = com.hermes.client.ui.localization.localized(
                languages.current,
                if (enabled) "已开启" else "已关闭",
                if (enabled) "On" else "Off",
            )
        }
        tile.updateTile()
    }

    /** Open the app at Settings > Notifications so its Enable switch can run the permission flow. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openNotificationSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_route", "settings_notifications")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
