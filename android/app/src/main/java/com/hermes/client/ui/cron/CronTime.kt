package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Run times for the scheduled-jobs screens, in the app's language rather than the device's locale.
 *
 * `ui/util/Time.kt`'s `formatIso` / `formatEpoch` build their pattern from `Locale.getDefault()`,
 * which is the phone's locale — but the app's language is its own setting (§6: 计划、投递、状态和
 * 时间均有中文/英文文案), so a Chinese UI on an English phone printed 「Sep 12, 18:15」 where the
 * design source says 「9月 12日 18:15」. Those two helpers have no other callers.
 *
 * Formatted in the device's time zone on purpose: the reader wants to know when the job runs for
 * THEM, not in whatever offset the gateway reported.
 */
fun cronTimeText(at: ZonedDateTime?, language: AppLanguage): String {
    if (at == null) return "—"
    val hh = "%02d".format(at.hour)
    val mm = "%02d".format(at.minute)
    return when (language) {
        AppLanguage.ZH -> "${at.monthValue}月 ${at.dayOfMonth}日 $hh:$mm"
        AppLanguage.EN -> "${EN_MONTHS[at.monthValue - 1]} ${at.dayOfMonth}, $hh:$mm"
    }
}

/** ISO-8601 as Hermes sends it on a job (`2026-09-12T18:15:00+08:00`). Unparseable → verbatim. */
fun cronTimeText(iso: String?, language: AppLanguage): String {
    val text = iso?.takeIf { it.isNotBlank() } ?: return "—"
    val parsed = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()) }
        .getOrNull()
    // An expression we cannot parse is shown as it arrived rather than guessed at — the same rule
    // the schedule text follows (docs/DESIGN.md §5.18).
    return parsed?.let { cronTimeText(it, language) } ?: text
}

/** Epoch seconds as Hermes sends them on a run (`started_at`). */
fun cronRunTimeText(seconds: Double?, language: AppLanguage): String {
    if (seconds == null) return "—"
    val at = runCatching {
        Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault())
    }.getOrNull()
    return cronTimeText(at, language)
}

private val EN_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
