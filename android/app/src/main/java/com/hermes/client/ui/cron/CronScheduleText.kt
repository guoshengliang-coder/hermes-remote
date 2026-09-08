package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

private val STEP_MINUTES = Regex("""^\*/(\d{1,3})\s+\*\s+\*\s+\*\s+\*$""")
private val STEP_HOURS = Regex("""^(\d{1,2})\s+\*/(\d{1,2})\s+\*\s+\*\s+\*$""")

/**
 * A schedule as a person would say it.
 *
 * Hermes sends `schedule_display` set to the cron expression itself, so the list had been showing
 * raw five-field expressions — including step forms like every-two-minutes. The app has had
 * [parseCron] and [describe] all along and this screen simply never called them.
 *
 * Step expressions ("every N minutes") are handled here rather than in [parseCron] on purpose:
 * they are a display concern, and adding a variant to the Schedule model would ripple into
 * [toCron], [nextRun] and the editor for no gain.
 *
 * Anything still unrecognised is returned verbatim. A schedule shown as an expression is worse
 * than one shown in words, but far better than a wrong translation of it.
 */
fun cronScheduleText(raw: String?, language: AppLanguage): String {
    val expr = raw?.trim().orEmpty()
    if (expr.isEmpty() || expr == "—") return expr
    STEP_MINUTES.find(expr)?.let { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@let
        return if (n == 1) localized(language, "每分钟", "Every minute")
        else localized(language, "每 $n 分钟", "Every $n minutes")
    }
    STEP_HOURS.find(expr)?.let { m ->
        val n = m.groupValues[2].toIntOrNull() ?: return@let
        val minute = m.groupValues[1].toIntOrNull() ?: 0
        val at = if (minute == 0) "" else localized(language, "的 %02d 分".format(minute), " at %02d past".format(minute))
        return if (n == 1) localized(language, "每小时$at", "Every hour$at")
        else localized(language, "每 $n 小时$at", "Every $n hours$at")
    }
    return when (val parsed = parseCron(expr)) {
        is Schedule.Advanced -> parsed.expr
        else -> parsed.describe(language)
    }
}
