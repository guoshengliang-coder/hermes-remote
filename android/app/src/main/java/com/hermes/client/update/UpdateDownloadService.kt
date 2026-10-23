package com.hermes.client.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermes.client.MainActivity
import com.hermes.client.R
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.notifications.Notif
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LanguagePreference
import com.hermes.client.ui.localization.resolve
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val UPDATE_DOWNLOAD_ACTION_PAUSE = "com.hermes.client.update.PAUSE"
internal const val UPDATE_DOWNLOAD_ACTION_CANCEL = "com.hermes.client.update.CANCEL_DOWNLOAD"

/**
 * Runs the update download outside the page (HG-223). The page promises "you can leave this
 * page — the download continues in the background", and with the platform DownloadManager this
 * service replaces, that promise is finally backed by an owner that survives navigation.
 *
 * The service is deliberately thin: the engine owns checkpoint state, this owns the foreground
 * lifecycle, the progress card, and the retry cadence.
 */
@AndroidEntryPoint
class UpdateDownloadService : android.app.Service() {
    @Inject lateinit var engine: UpdateDownloadEngine
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var ready: UpdateReadyNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var language: AppLanguage = LanguagePreference.SYSTEM.resolve()
    private var lastNotifiedAt = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, progressNotification(engine.record.value, LanguagePreference.SYSTEM.resolve()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (work?.isActive == true) return START_STICKY
        work = scope.launch {
            language = settings.appLanguage.first()
            val restored = engine.restore()
            if (restored == null) {
                withContext(kotlinx.coroutines.NonCancellable) { finish() }
                return@launch
            }
            postProgress(restored, force = true)
            try {
                while (isActive) {
                    val current = engine.record.value ?: break
                    if (current.status == UpdateDownloadStatus.COMPLETE || current.status == UpdateDownloadStatus.FAILED ||
                        current.status == UpdateDownloadStatus.PAUSED
                    ) break
                    val retry = try {
                        engine.run { postProgress(it) }
                    } catch (_: Exception) {
                        false
                    }
                    if (!retry) break
                    // The engine kept every fsynced range. Wait for a network instead of burning
                    // retries in a loop, then continue from the committed offsets.
                    while (isActive && !online()) delay(NETWORK_POLL_MS)
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                // Pause/cancel cancels this coroutine through the engine; the foreground card must
                // still be removed in a cancelled context.
                withContext(kotlinx.coroutines.NonCancellable) { finish() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun postProgress(record: UpdateDownloadRecord, force: Boolean = false) {
        val stamp = System.currentTimeMillis()
        if (!force && stamp - lastNotifiedAt < NOTIFY_INTERVAL_MS) return
        lastNotifiedAt = stamp
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(record, language))
    }

    private suspend fun finish() {
        val record = engine.record.value ?: engine.restore()
        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Verification and installation still need the page (or its notification path); tell the
        // user the bytes are ready rather than claiming they were verified.
        if (record?.status == UpdateDownloadStatus.COMPLETE) ready.notifyDownloaded(record.version)
    }

    private fun online(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun progressNotification(record: UpdateDownloadRecord?, language: AppLanguage): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                Notif.CHANNEL_UPDATE_PROGRESS,
                localized(language, "更新下载进度", "Update download progress"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        val version = record?.version?.versionName
        val title = when {
            record == null -> localized(language, "正在准备更新", "Preparing the update")
            record.status == UpdateDownloadStatus.PAUSED -> localized(language, "更新下载已暂停", "Update download paused")
            else -> localized(language, "正在下载更新 ${version ?: ""}", "Downloading update ${version ?: ""}")
        }
        val builder = NotificationCompat.Builder(this, Notif.CHANNEL_UPDATE_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(title)
            .setContentText(progressText(record, language))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent())
        if (record != null && record.totalBytes > 0) {
            val percent = (record.downloadedBytes * 100 / record.totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
            if (record.status != UpdateDownloadStatus.PAUSED) {
                builder.addAction(0, localized(language, "暂停", "Pause"), actionIntent(UPDATE_DOWNLOAD_ACTION_PAUSE))
                builder.addAction(0, localized(language, "取消", "Cancel"), actionIntent(UPDATE_DOWNLOAD_ACTION_CANCEL))
            } else {
                builder.addAction(0, localized(language, "取消", "Cancel"), actionIntent(UPDATE_DOWNLOAD_ACTION_CANCEL))
            }
        }
        return builder.build()
    }

    private fun progressText(record: UpdateDownloadRecord?, language: AppLanguage): String {
        if (record == null) return localized(language, "可离开本页，下载会在后台继续。", "You can leave this page — the download continues in the background.")
        val pct = if (record.totalBytes > 0) (record.downloadedBytes * 100 / record.totalBytes).toInt() else 0
        val bytes = "${formatUpdateBytes(record.downloadedBytes)} / ${formatUpdateBytes(record.totalBytes)}"
        return when (record.status) {
            UpdateDownloadStatus.PAUSED -> localized(language, "已暂停，打开应用可继续；已下载部分会保留。", "Paused. Open the app to continue; downloaded bytes are kept.")
            UpdateDownloadStatus.FAILED -> localized(language, "下载中断，已保留已下载部分。", "Interrupted. Downloaded bytes are kept.")
            else -> "$pct% · $bytes" // l10n-allow: progress counter
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        NOTIFICATION_ID,
        Intent(this, MainActivity::class.java).putExtra("extra_route", "app_update")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        NOTIFICATION_ID + action.hashCode(),
        Intent(this, UpdateDownloadActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val NOTIFICATION_ID = Notif.UPDATE_PROGRESS_NOTIFICATION_ID
        const val NOTIFY_INTERVAL_MS = 1_000L
        const val RETRY_DELAY_MS = 5_000L
        const val NETWORK_POLL_MS = 15_000L
    }
}

object UpdateDownloadScheduler {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, UpdateDownloadService::class.java))
    }
}

/** Pause and cancel from the progress card; the page owns the same two actions. */
@AndroidEntryPoint
class UpdateDownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: UpdateDownloadEngine

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UPDATE_DOWNLOAD_ACTION_PAUSE -> engine.pause()
            UPDATE_DOWNLOAD_ACTION_CANCEL -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        engine.cancel()
                        UpdateDownloadScheduler.stop(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

internal fun formatUpdateBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0)) // l10n-allow: byte counter
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0) // l10n-allow: byte counter
    else -> "$bytes B" // l10n-allow: byte counter
}
