package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localized
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

/**
 * The row's second line: rhythm, where the result goes, and how it went last time — the same
 * grammar the Chats and channels lists use (`节奏或落点 · 上次结果`).
 *
 * A healthy job says nothing about its last run: "每天 08:30 · 投递到 钉钉" already tells the reader
 * everything, and appending 上次成功 to every healthy row turns the outcome into noise that the one
 * failing row then has to compete with.
 */
fun cronSublineText(
    scheduleText: String,
    deliver: String?,
    status: CronRowStatus,
    language: AppLanguage,
): String {
    val outcome = when (status) {
        CronRowStatus.FAILED -> localized(language, "上次失败", "last run failed")
        CronRowStatus.UNDELIVERED -> localized(language, "未送达", "not delivered")
        CronRowStatus.OVERDUE -> localized(language, "已逾期", "overdue")
        CronRowStatus.PAUSED -> localized(language, "已暂停", "paused")
        CronRowStatus.OK -> null
    }
    return listOfNotNull(
        cronScheduleText(scheduleText, language).takeIf { it.isNotBlank() && it != "—" },
        cronDeliveryText(deliver).resolve(language),
        outcome,
    ).joinToString("  ·  ")
}
