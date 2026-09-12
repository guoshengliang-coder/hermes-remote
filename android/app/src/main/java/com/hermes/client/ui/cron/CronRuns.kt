package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
import kotlin.math.roundToLong

/**
 * How long one run took, from the two epoch-second stamps Hermes already sends
 * (`started_at` / `ended_at`).
 *
 * The design source draws a duration on every history item. There is no duration FIELD — but
 * there are two timestamps, so this is arithmetic on data we have rather than UI invented for a
 * mock (docs/DESIGN.md §7 item 8). The three things the same mock draws that are NOT derivable —
 * a run number, a per-run log link, a retention notice — are deliberately absent.
 *
 * Returns null when the run has not finished, when either stamp is missing, or when the pair is
 * inconsistent: an end before its start is a clock or a server bug, and "-3s" helps nobody.
 */
fun cronRunDuration(startedAt: Double?, endedAt: Double?): LocalizedText? {
    if (startedAt == null || endedAt == null) return null
    val seconds = (endedAt - startedAt).roundToLong()
    if (seconds < 0) return null
    return when {
        seconds < 60 -> localizedText("耗时 ${seconds}s", "took ${seconds}s")
        seconds < 3600 -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) localizedText("耗时 $m 分", "took ${m}m")
            else localizedText("耗时 $m 分 $s 秒", "took ${m}m ${s}s")
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) localizedText("耗时 $h 小时", "took ${h}h")
            else localizedText("耗时 $h 小时 $m 分", "took ${h}h ${m}m")
        }
    }
}

/** Resolves [cronRunDuration] for [language], or null when there is nothing to say. */
fun cronRunDurationLabel(startedAt: Double?, endedAt: Double?, language: AppLanguage): String? =
    cronRunDuration(startedAt, endedAt)?.resolve(language)
