package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.util.isoToEpochMs

enum class CronAlertReason { FAILED, OVERDUE }

data class CronAlert(
    val jobId: String,
    val name: String,
    val reason: CronAlertReason,
    val route: String,
    val lastRunAtMs: Long? = null,
)

/** Default grace before an un-run scheduled job counts as overdue. */
const val NEEDS_YOU_GRACE_MS = 5 * 60_000L

/**
 * Cron jobs needing attention. FAILED (last run errored) takes priority — a broken job matters even
 * if paused/disabled. Else OVERDUE: an enabled, non-paused job whose next run is more than [graceMs]
 * past due. Pure — [nowMs] passed in so it unit-tests without a clock.
 */
fun needsAttention(
    crons: List<CronJobDto>,
    nowMs: Long,
    graceMs: Long = NEEDS_YOU_GRACE_MS,
): List<CronAlert> = crons.mapNotNull { job ->
    val name = job.name?.ifBlank { null } ?: job.id
    val route = "cron_detail/${job.id}"
    val failed = job.lastStatus.equals("error", ignoreCase = true) ||
        job.lastStatus.equals("failed", ignoreCase = true)
    val lastRunAtMs = isoToEpochMs(job.lastRunAt)
    when {
        failed -> CronAlert(job.id, name, CronAlertReason.FAILED, route, lastRunAtMs)
        job.enabled && !job.isPaused -> {
            val next = isoToEpochMs(job.nextRunAt)
            if (next != null && next < nowMs - graceMs) CronAlert(job.id, name, CronAlertReason.OVERDUE, route, lastRunAtMs) else null
        }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Waiting sessions: a live run that stopped to ask the user something is the
// single most actionable thing in the app — surface it at the top of Home.
// ---------------------------------------------------------------------------

enum class SessionAlertReason { WAITING_APPROVAL, WAITING_CLARIFICATION }

data class SessionAlert(
    val sessionId: String,
    val title: String,
    val reason: SessionAlertReason,
    val sinceMs: Long? = null,
) {
    val route: String get() = "chat/$sessionId"
}

/**
 * Sessions currently blocked on the user (approval or clarification), newest first. Pure —
 * runtime map and title lookup are passed in so it unit-tests without the store.
 */
fun waitingSessionAlerts(
    runtimes: Map<SessionRuntimeKey, SessionRuntime>,
    profile: String?,
    titleOf: (String) -> String?,
): List<SessionAlert> = runtimes.values
    .filter {
        it.phase == SessionRunPhase.WAITING_APPROVAL || it.phase == SessionRunPhase.WAITING_CLARIFICATION
    }
    .filter { profile.isNullOrBlank() || it.key.profile.isNullOrBlank() || it.key.profile == profile }
    .sortedByDescending { it.lastEventAt }
    .map { runtime ->
        SessionAlert(
            sessionId = runtime.key.sessionId,
            title = titleOf(runtime.key.sessionId).orEmpty(),
            reason = if (runtime.phase == SessionRunPhase.WAITING_APPROVAL) {
                SessionAlertReason.WAITING_APPROVAL
            } else {
                SessionAlertReason.WAITING_CLARIFICATION
            },
            sinceMs = runtime.lastEventAt.takeIf { it > 0 },
        )
    }
