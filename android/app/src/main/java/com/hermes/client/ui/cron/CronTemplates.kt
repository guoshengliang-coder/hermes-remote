package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/** A one-tap starting point for a new cron job (a preset schedule + a starter prompt). */
data class CronTemplate(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val schedule: Schedule,
    val promptZh: String,
    val promptEn: String,
) {
    fun label(language: AppLanguage) = localized(language, labelZh, labelEn)
    fun prompt(language: AppLanguage) = localized(language, promptZh, promptEn)
}

// Template ids (new_daily, new_weekly, etc.) are a distinct namespace from server job ids (c1, etc.),
// so cronTemplate(id) matching in the edit VM can't collide with a real job.
val CRON_TEMPLATES = listOf(
    CronTemplate(
        "new_daily", "每日摘要 · 9:00", "Daily summary · 9:00", Schedule.Daily(9, 0),
        "总结昨天各项目发生的情况，包括部署、故障以及任何需要我关注的事项。",
        "Summarize what happened across my projects yesterday — deploys, incidents, and anything that needs my attention.",
    ),
    CronTemplate(
        "new_weekly", "每周审计 · 周一 2:00", "Weekly audit · Mon 2:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0),
        "执行依赖和安全审计，并列出所有需要关注的问题。",
        "Run a dependency and security audit and list anything that needs attention.",
    ),
    CronTemplate(
        "new_hourly", "每小时检查", "Hourly check", Schedule.Hourly(0),
        "检查是否有需要我关注的紧急事项，并进行总结。",
        "Check for anything urgent that needs my attention and summarize it.",
    ),
)

fun cronTemplate(id: String): CronTemplate? = CRON_TEMPLATES.firstOrNull { it.id == id }
