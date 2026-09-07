package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

/** A section of the scheduled-jobs list, in render order. */
enum class CronGroup { NEEDS_YOU, ACTIVE, PAUSED }

data class CronSection(val group: CronGroup, val title: LocalizedText, val jobs: List<CronJobDto>)

/**
 * Groups scheduled jobs the way the Chats and channels lists group theirs: what needs a person
 * first, then what is running, then what is switched off. A flat list left a failed job sitting
 * between two healthy ones, distinguishable only by a small leading glyph — and the home screen's
 * alert strip could say "2 need attention" while the list gave no clue which two.
 */
fun cronSections(jobs: List<CronJobDto>, nowMs: Long): List<CronSection> {
    val buckets = LinkedHashMap<CronGroup, MutableList<CronJobDto>>()
    for (job in jobs) {
        val group = when (cronRowStatus(job, nowMs)) {
            CronRowStatus.FAILED, CronRowStatus.UNDELIVERED, CronRowStatus.OVERDUE -> CronGroup.NEEDS_YOU
            CronRowStatus.PAUSED -> CronGroup.PAUSED
            CronRowStatus.OK -> CronGroup.ACTIVE
        }
        buckets.getOrPut(group) { mutableListOf() }.add(job)
    }
    return CronGroup.entries.mapNotNull { group ->
        buckets[group]?.takeIf { it.isNotEmpty() }?.let { CronSection(group, cronGroupTitle(group), it) }
    }
}

fun cronGroupTitle(group: CronGroup): LocalizedText = when (group) {
    CronGroup.NEEDS_YOU -> localizedText("需要你处理", "Needs you")
    CronGroup.ACTIVE -> localizedText("已启用", "Active")
    CronGroup.PAUSED -> localizedText("已暂停", "Paused")
}

/**
 * Where a job sends its result, as a row subtitle. `local` means "save only, no message" — and it
 * is the server's default, so most jobs say it; naming it beats leaving the reader to guess
 * whether a silent job is broken or simply not configured to speak.
 */
fun cronDeliveryText(deliver: String?): LocalizedText = when (val target = deliver?.trim()?.lowercase()?.ifBlank { null }) {
    null, "local" -> localizedText("只存不发", "Saved only")
    "origin" -> localizedText("投递到来源聊天", "Delivered to its origin chat")
    else -> localizedText(
        "投递到 ${com.hermes.client.ui.sessions.botSourceLabel(target)}",
        "Delivered to ${com.hermes.client.ui.sessions.botSourceLabel(target)}",
    )
}
