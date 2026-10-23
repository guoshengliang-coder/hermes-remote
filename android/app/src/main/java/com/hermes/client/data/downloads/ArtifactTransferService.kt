package com.hermes.client.data.downloads

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.hermes.client.R
import com.hermes.client.data.error.*
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.ui.localization.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val CHANNEL = "attachment-downloads"
private const val FOREGROUND_ID = 0x484644
private const val TASK = "task"

object ArtifactTransferScheduler {
    fun schedule(context: Context, task: ArtifactDownloadTask) {
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Explicit Resume may expedite a pending retry, or recreate a task after reboot.
            scheduler.allPendingJobs.filter { it.service.className == ArtifactTransferJobService::class.java.name && it.extras.getString(TASK) == task.id }
                .forEach { scheduler.cancel(it.id) }
            var id = notificationId(task.id)
            while (scheduler.getPendingJob(id) != null) id++
            val job = JobInfo.Builder(id, ComponentName(context, ArtifactTransferJobService::class.java))
                .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes((task.sizeBytes ?: 1024 * 1024).coerceAtLeast(1), 0)
                .setExtras(PersistableBundle().apply { putString(TASK, task.id) }).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
        } else {
            ContextCompat.startForegroundService(context,
                Intent(context, ArtifactTransferService::class.java).putExtra(TASK, task.id))
        }
    }
    fun cancel(context: Context, id: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.allPendingJobs.filter { it.service.className == ArtifactTransferJobService::class.java.name && it.extras.getString(TASK) == id }
                .forEach { scheduler.cancel(it.id) }
        }
    }
}

@AndroidEntryPoint
class ArtifactTransferJobService : JobService() {
    @Inject lateinit var downloads: ArtifactDownloadManager
    @Inject lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(TASK) ?: return false
        if (Build.VERSION.SDK_INT < 34) return false
        setNotification(params, params.jobId, downloadNotification(this, null, LanguagePreference.SYSTEM.resolve()), JOB_END_NOTIFICATION_POLICY_REMOVE)
        running[params.jobId] = scope.launch {
            val language = settings.appLanguage.first()
            try {
                val retry = downloads.runTask(id) { task ->
                    if (task.status == ArtifactDownloadStatus.COMPLETE) postCompletion(this@ArtifactTransferJobService, task, language)
                    else {
                        val notification = downloadNotification(this@ArtifactTransferJobService, task, language)
                        setNotification(params, params.jobId, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
                    }
                }
                withContext(Dispatchers.Main) { running.remove(params.jobId); jobFinished(params, retry) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { withContext(Dispatchers.Main) { running.remove(params.jobId); jobFinished(params, true) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        if (Build.VERSION.SDK_INT < 34) return false
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            params.extras.getString(TASK)?.let(downloads::pause)
            return false
        }
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Older Android fallback, started only by an explicit foreground user action. */
@AndroidEntryPoint
class ArtifactTransferService : Service() {
    @Inject lateinit var downloads: ArtifactDownloadManager
    @Inject lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, Job>()
    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_ID, downloadNotification(this, null, LanguagePreference.SYSTEM.resolve()))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(TASK)
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        if (running[id]?.isActive == true) return START_NOT_STICKY
        running[id] = scope.launch {
            val language = settings.appLanguage.first()
            try {
                var retry: Boolean
                do {
                    retry = downloads.runTask(id) { task ->
                        if (task.status == ArtifactDownloadStatus.COMPLETE) postCompletion(this@ArtifactTransferService, task, language)
                        else getSystemService(NotificationManager::class.java).notify(FOREGROUND_ID,
                            downloadNotification(this@ArtifactTransferService, task, language))
                    }
                    if (retry) delay(30_000)
                } while (retry && isActive)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    running.remove(id)
                    if (running.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

@AndroidEntryPoint
class ArtifactDownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var downloads: ArtifactDownloadManager
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(TASK) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (if (intent.action == "cancel") downloads.cancel(id) else downloads.pause(id)).join() }
            finally { pending.finish() }
        }
    }
}

/** Notification clicks authorize the saved owner again before handing out a local content URI. */
@AndroidEntryPoint
class ArtifactDownloadOpenActivity : ComponentActivity() {
    @Inject lateinit var downloads: ArtifactDownloadManager
    @Inject lateinit var settings: SettingsStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val language = settings.appLanguage.first()
            try {
                val id = intent.getStringExtra(TASK)
                val task = downloads.awaitTask(id) ?: throw com.hermes.client.data.network.HermesApiException(404, "download missing")
                if (downloads.owner(task.deviceId) != task.owner) throw com.hermes.client.data.network.HermesApiException(401, "owner changed")
                val file = downloads.completedFile(task) ?: throw com.hermes.client.data.network.HermesApiException(404, "file missing")
                val uri = FileProvider.getUriForFile(this@ArtifactDownloadOpenActivity, "$packageName.fileprovider", file)
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, task.mimeType ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(this@ArtifactDownloadOpenActivity, AppError(AppErrorCode.ATTACHMENT_NO_VIEWER, false).localizedMessage(language), android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                android.widget.Toast.makeText(this@ArtifactDownloadOpenActivity, artifactDownloadError(error).localizedMessage(language), android.widget.Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}

private fun notificationId(id: String) = 0x48000000 or (id.hashCode() and 0x00ffffff)
private fun downloadNotification(context: Context, task: ArtifactDownloadTask?, language: AppLanguage): Notification {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, localized(language, "文件下载", "File downloads"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_hermes)
        .setContentTitle(task?.name ?: localized(language, "文件下载", "File download"))
        .setContentText(task?.errorCode?.let(AppErrorCode::fromValue)?.let { AppError(it, false).localizedMessage(language) }
            ?: localized(language, "正在下载，可返回 App 查看进度", "Downloading. Open the app for progress."))
        .setOnlyAlertOnce(true).setOngoing(true)
    if (task != null) {
        val total = task.sizeBytes ?: 0
        builder.setProgress(100, if (total > 0) ((task.downloaded * 100) / total).toInt() else 0, total <= 0)
        for ((action, zh, en) in listOf(Triple("pause", "暂停", "Pause"), Triple("cancel", "取消", "Cancel"))) {
            val pending = PendingIntent.getBroadcast(context, notificationId(task.id + action),
                Intent(context, ArtifactDownloadActionReceiver::class.java).setAction(action).putExtra(TASK, task.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, localized(language, zh, en), pending)
        }
    }
    return builder.build()
}

private fun postCompletion(context: Context, task: ArtifactDownloadTask, language: AppLanguage) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = PendingIntent.getActivity(context, notificationId(task.id),
        Intent(context, ArtifactDownloadOpenActivity::class.java).putExtra(TASK, task.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(NotificationManager::class.java).notify(notificationId(task.id) xor 0x01000000,
        NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(task.name).setContentText(localized(language, "下载完成，点按打开", "Download complete. Tap to open."))
            .setContentIntent(open).setAutoCancel(true).build())
}
