package com.hermes.client.notifications

import com.hermes.client.data.progress.RunProgress
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Pure mapping from run state to a notification description, or null when nothing should be
 * shown. Mirrors [toNotificationSpec]: all decisions live here so they are testable without
 * Android, and [HermesNotifier] only renders.
 */
fun RunProgress.toSpec(
    prefs: NotificationPrefs,
    language: AppLanguage = AppLanguage.EN,
): RunProgressSpec? {
    if (!prefs.enabled || !prefs.runProgress) return null
    if (!running) return null
    val tenant = profile?.takeIf { it.isNotBlank() }
    return RunProgressSpec(
        title = if (tenant != null) {
            localized(language, "$tenant · 智能体运行中", "$tenant · agent running")
        } else localized(language, "智能体运行中", "Agent running"),
        body = tool?.let {
            localized(language, "正在调用工具：$it", "Calling tool: $it")
        } ?: localized(language, "正在处理…", "Working…"),
        done = done,
        total = total,
        indeterminate = !determinate,
        route = sessionId?.let { "chat/$it" },
        shortText = if (determinate) "$done/$total" else null,
    )
}
