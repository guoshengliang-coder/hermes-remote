package com.hermes.client.ui.usage

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.AuxTaskUsageDto
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.data.network.UsageDto
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One usage window, reconciled so the page cannot contradict itself.
 *
 * Hermes reports two disjoint accounts. `daily`/`totals` come from the `sessions` table and cover
 * the MAIN AGENT only; `by_model` already has auxiliary usage (compression, title generation,
 * vision, ...) folded in. Summing the first and reading the second therefore disagree — the old
 * screen showed the daily sum on top and the by-model list underneath, so the rows added up to
 * more than the headline. Everything is derived here instead: the headline is main + aux, and each
 * block states which of the two it covers.
 */
data class UsageSummary(
    val periodDays: Int,
    /** Main-agent tokens: input + output + cache reads. */
    val mainTokens: Long,
    /** Auxiliary tokens across every task. Has no day dimension upstream. */
    val auxTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val sessions: Int,
    val apiCalls: Int,
    val estimatedCost: Double,
    /** Calendar-filled: one entry per day in the window, zeros included. */
    val daily: List<UsageDayDto>,
    val models: List<ModelUsageDto>,
    val auxTasks: List<AuxTaskUsageDto>,
) {
    val totalTokens: Long get() = mainTokens + auxTokens

    /**
     * A window with nothing in it. Distinguished from a failed load so the screen can say "no usage
     * recorded yet" instead of showing zeros that look identical to a broken connector.
     */
    val isEmpty: Boolean get() = totalTokens == 0L && sessions == 0 && apiCalls == 0
}

/** UTC, because that is the day boundary Hermes buckets on. See [fillCalendar]. */
fun utcToday(): LocalDate = LocalDate.now(ZoneOffset.UTC)

/**
 * Expand sparse rows into one entry per day of the window.
 *
 * Hermes emits a row only for days that had sessions, and buckets them with SQLite
 * `date(started_at,'unixepoch')` — a UTC day. Two bugs followed from consuming the rows raw:
 * "this week" took the last 7 ROWS, which spans months when usage is sparse, and the chart drew
 * unequal gaps at equal spacing. Fill first, window afterwards.
 */
fun fillCalendar(rows: List<UsageDayDto>, days: Int, today: LocalDate = utcToday()): List<UsageDayDto> {
    if (days <= 0) return emptyList()
    val byDay = rows.associateBy { it.day }
    val start = today.minusDays((days - 1).toLong())
    return (0 until days).map { offset ->
        val date = start.plusDays(offset.toLong()).toString()
        byDay[date] ?: UsageDayDto(day = date)
    }
}

/** The card page's "this week": the last 7 calendar days, not the last 7 rows that had traffic. */
fun weekWindow(rows: List<UsageDayDto>, today: LocalDate = utcToday()): List<UsageDayDto> =
    fillCalendar(rows, 7, today)

fun summarize(usage: UsageDto, today: LocalDate = utcToday()): UsageSummary {
    val periodDays = usage.periodDays.takeIf { it > 0 } ?: 30
    val totals = usage.totals
    val aux = usage.byTask.sortedByDescending { it.totalTokens }
    return UsageSummary(
        periodDays = periodDays,
        mainTokens = totals.totalTokens,
        auxTokens = aux.sumOf { it.totalTokens },
        inputTokens = totals.inputTokens,
        outputTokens = totals.outputTokens,
        cacheReadTokens = totals.cacheReadTokens,
        sessions = totals.sessions,
        apiCalls = totals.apiCalls,
        // by_model carries auxiliary spend too, so its cost is the fuller of the two figures.
        estimatedCost = maxOf(totals.estimatedCost, usage.byModel.sumOf { it.estimatedCost }),
        daily = fillCalendar(usage.daily, periodDays, today),
        models = usage.byModel.sortedByDescending { it.inputTokens + it.outputTokens },
        auxTasks = aux,
    )
}

/**
 * Map a transport failure onto the registered error contract (`docs/ERROR_HANDLING.md`).
 *
 * The screen used to collapse every failure into `HR-RPC-001`, so "the Mac is asleep" and "the
 * request timed out" produced the same unhelpful sentence. The Gateway answers a request it cannot
 * tunnel with `503 device_offline`, and a stalled tunnel trips the per-call REST deadline as an
 * [InterruptedIOException]; both deserve their own copy and their own recovery.
 */
fun Throwable.toUsageError(stage: String): AppError {
    val cause = (this as? HermesApiException)?.let { "HTTP ${it.code}: ${it.message}" } ?: toString()
    val mapped = when {
        this is HermesApiException && this.code == 503 &&
            message?.contains("device_offline") == true -> AppErrorCode.CONNECTOR_OFFLINE
        this is InterruptedIOException -> AppErrorCode.RPC_TIMEOUT
        else -> AppErrorCode.RPC_FAILED
    }
    return AppError(mapped, retryable = true, technicalCause = cause, stage = stage)
}
