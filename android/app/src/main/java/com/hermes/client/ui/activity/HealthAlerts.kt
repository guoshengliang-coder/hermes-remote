package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.ui.messaging.MessagingRowStatus
import com.hermes.client.ui.messaging.messagingRowStatus

/** A channel that is down, plus the scheduled jobs that failed to deliver *because* it is down. */
data class ChannelAlert(val id: String, val name: String, val affectedJobs: Int)

/**
 * What the home screen's single alert slot should say.
 *
 * [channels] are root causes; [standaloneJobs] are the scheduled jobs whose trouble is their own.
 * [total] is what the strip counts — deliberately NOT channels + every failing job, because one
 * broken channel plus the three reports it swallowed is one problem, not four.
 *
 * [standaloneJobs] holds the alerts rather than a count (HG-50, 2026-09-14). It used to be an
 * `Int`, which is why the strip could only ever open the list: by the time the screen saw it, the
 * one job it was counting had been thrown away. The alerts already carry `jobId` and a
 * `cron_detail/…` route, so keeping them costs nothing and lets a single failure go straight to
 * the job it is about.
 */
data class MergedHealth(
    val channels: List<ChannelAlert> = emptyList(),
    val standaloneJobs: List<CronAlert> = emptyList(),
) {
    val standaloneCronJobs: Int get() = standaloneJobs.size
    val total: Int get() = channels.size + standaloneJobs.size
    val hasChannelCause: Boolean get() = channels.isNotEmpty()

    /**
     * The one scheduled job this strip is about, or null when it is about several or about a
     * channel. A channel cause outranks it: the fix is on the channel, so that is where the tap
     * goes, however few jobs are behind it (docs/DESIGN.md §5.16).
     */
    val soleStandaloneJob: CronAlert? get() =
        standaloneJobs.singleOrNull()?.takeIf { channels.isEmpty() }
}

private fun MessagingPlatformDto.isDown(): Boolean =
    needsAttention || messagingRowStatus(this) in setOf(
        MessagingRowStatus.FAILED, MessagingRowStatus.GATEWAY_STOPPED,
    )

/**
 * Root-cause-first merge. A channel that is down absorbs the delivery failures it caused: those
 * jobs ran fine, and telling the user to go fix three scheduled jobs would send them to the wrong
 * screen three times.
 *
 * Attribution needs the job's `deliver` to name the platform outright. `origin` (the chat the job
 * was created from) cannot be resolved to a platform from here, so such a job keeps its own alert
 * rather than being folded on a guess — an unattributed failure is better than a wrong one.
 */
fun mergeHealth(
    crons: List<CronJobDto>,
    platforms: List<MessagingPlatformDto>,
    nowMs: Long,
): MergedHealth {
    val down = platforms.filter { it.isDown() }.associateBy { it.id }
    val alerts = needsAttention(crons, nowMs)
    val byJobId = crons.associateBy { it.id }

    val absorbed = mutableMapOf<String, Int>()
    val standalone = mutableListOf<CronAlert>()
    for (alert in alerts) {
        val job = byJobId[alert.jobId]
        val target = job?.deliver?.trim()?.lowercase()
        if (alert.reason == CronAlertReason.UNDELIVERED && target != null && target in down) {
            absorbed[target] = (absorbed[target] ?: 0) + 1
        } else {
            standalone.add(alert)
        }
    }
    val channels = down.values
        .map { ChannelAlert(it.id, it.name ?: it.id, absorbed[it.id] ?: 0) }
        .sortedByDescending { it.affectedJobs }
    return MergedHealth(channels = channels, standaloneJobs = standalone)
}
