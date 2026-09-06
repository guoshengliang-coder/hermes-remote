package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.util.isoToEpochMs

enum class CronAlertReason { FAILED, UNDELIVERED, OVERDUE }

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
 * if paused/disabled. Then UNDELIVERED: Hermes reports `delivery_failed` when the agent run itself
 * SUCCEEDED but its output never reached the target channel; `last_error` is null for it and the
 * detail lives in `last_delivery_error`. Recognising only error/failed made that case read as a
 * clean run, so a daily report that silently stopped arriving looked healthy here. Else OVERDUE: an
 * enabled, non-paused job whose next run is more than [graceMs] past due. Pure — [nowMs] passed in
 * so it unit-tests without a clock.
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
    val undelivered = job.lastStatus.equals("delivery_failed", ignoreCase = true)
    val lastRunAtMs = isoToEpochMs(job.lastRunAt)
    when {
        failed -> CronAlert(job.id, name, CronAlertReason.FAILED, route, lastRunAtMs)
        undelivered -> CronAlert(job.id, name, CronAlertReason.UNDELIVERED, route, lastRunAtMs)
        job.enabled && !job.isPaused -> {
            val next = isoToEpochMs(job.nextRunAt)
            if (next != null && next < nowMs - graceMs) CronAlert(job.id, name, CronAlertReason.OVERDUE, route, lastRunAtMs) else null
        }
        else -> null
    }
}
