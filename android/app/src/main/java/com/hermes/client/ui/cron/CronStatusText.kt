package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

/**
 * Hermes' raw run outcomes, localized. These arrive as bare server tokens (`ok`, `error`,
 * `delivery_failed`, `timeout`, …) and used to be concatenated straight into the UI — an
 * English-only token shown as the primary text, which `ERROR_HANDLING.md` forbids.
 *
 * Unknown values are returned verbatim rather than swallowed: a future upstream outcome should
 * still tell the reader something, and hiding it would make the row silently lie.
 */
fun cronStatusText(raw: String?): LocalizedText? = when (raw?.trim()?.lowercase()?.ifBlank { null }) {
    null -> null
    // `cron_complete` is what a RUN reports when it finished (the job's `last_status` says `ok`).
    // Without this branch the verbatim fallback put the bare server token on screen as the primary
    // text — exactly what ERROR_HANDLING.md forbids, and the design source draws it that way too.
    "ok", "success", "succeeded", "cron_complete" -> localizedText("成功", "Succeeded")
    "error", "failed" -> localizedText("失败", "Failed")
    // Ran fine, output never reached the channel. See HR-CRON-001.
    "delivery_failed" -> localizedText("已运行，未送达", "Ran, not delivered")
    "running" -> localizedText("运行中", "Running")
    "timeout", "timed_out" -> localizedText("超时", "Timed out")
    "cancelled", "canceled" -> localizedText("已取消", "Cancelled")
    "skipped" -> localizedText("已跳过", "Skipped")
    else -> localizedText(raw.trim(), raw.trim())
}

/** Resolves [cronStatusText] for [language], or null when there is no status to show. */
fun cronStatusLabel(raw: String?, language: AppLanguage): String? = cronStatusText(raw)?.resolve(language)
