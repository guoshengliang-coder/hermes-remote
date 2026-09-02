package com.hermes.client.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hermes.client.data.repository.LifecycleEventRepository
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fifteen-minute OS-managed fallback used only when no phone-started run is active. */
@AndroidEntryPoint
class LifecycleEventJobService : JobService() {
    @Inject lateinit var events: LifecycleEventRepository
    @Inject lateinit var settings: NotificationSettings
    @Inject lateinit var dispatcher: LifecycleNotificationDispatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val prefs = settings.prefs.first()
                if (prefs.enabled) {
                    events.sync { batch -> dispatcher.dispatch(batch) }
                }
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

object LifecycleEventJobScheduler {
    private const val JOB_ID = 0x484C43
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Application.onCreate also runs when JobScheduler wakes this process. Re-submitting the
        // same ID from that startup path can replace the job that is currently executing.
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, LifecycleEventJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            .build()
        scheduler.schedule(info)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
