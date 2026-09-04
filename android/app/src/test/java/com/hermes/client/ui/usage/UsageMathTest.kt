package com.hermes.client.ui.usage

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.AuxTaskUsageDto
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.data.network.UsageDto
import com.hermes.client.data.network.UsageTotalsDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.LocalDate

class UsageMathTest {

    private val today = LocalDate.of(2026, 9, 3)

    private fun day(d: String, input: Long = 0, output: Long = 0, cache: Long = 0, sessions: Int = 0) =
        UsageDayDto(day = d, inputTokens = input, outputTokens = output, cacheReadTokens = cache, sessions = sessions)

    // --- calendar fill -----------------------------------------------------

    @Test fun fill_emits_one_entry_per_day_in_order() {
        val filled = fillCalendar(listOf(day("2026-09-01", input = 5)), days = 3, today = today)
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), filled.map { it.day })
    }

    @Test fun fill_inserts_zero_days_for_gaps() {
        val filled = fillCalendar(listOf(day("2026-09-03", input = 7)), days = 3, today = today)
        assertEquals(listOf(0L, 0L, 7L), filled.map { it.inputTokens })
    }

    @Test fun fill_drops_rows_outside_the_window() {
        val filled = fillCalendar(listOf(day("2026-01-01", input = 999), day("2026-09-03", input = 7)), 3, today)
        assertEquals(7L, filled.sumOf { it.inputTokens })
    }

    @Test fun fill_of_zero_days_is_empty() {
        assertTrue(fillCalendar(listOf(day("2026-09-03")), days = 0, today = today).isEmpty())
    }

    /**
     * The bug this function exists for: Hermes emits no row for a day without sessions, so the card
     * page's `takeLast(7)` was "the last seven ACTIVE days". With one session a month, 本周用量
     * silently summed half a year.
     */
    @Test fun week_window_is_seven_calendar_days_not_seven_active_days() {
        val sparse = listOf(
            day("2026-03-02", input = 1_000, sessions = 1),
            day("2026-05-11", input = 1_000, sessions = 1),
            day("2026-07-20", input = 1_000, sessions = 1),
            day("2026-08-30", input = 40, sessions = 2),
            day("2026-09-02", input = 60, sessions = 3),
        )
        val week = weekWindow(sparse, today)
        assertEquals(7, week.size)
        assertEquals("2026-08-28", week.first().day)
        assertEquals("2026-09-03", week.last().day)
        // Only the two days that really fall inside the week.
        assertEquals(100L, week.sumOf { it.inputTokens })
        assertEquals(5, week.sumOf { it.sessions })
    }

    // --- reconciliation ----------------------------------------------------

    private val usage = UsageDto(
        daily = listOf(day("2026-09-02", input = 60, output = 10, cache = 30, sessions = 3)),
        byModel = listOf(
            ModelUsageDto(model = "small", inputTokens = 10, outputTokens = 5, estimatedCost = 0.1),
            ModelUsageDto(model = "big", inputTokens = 900, outputTokens = 100, estimatedCost = 1.9),
        ),
        byTask = listOf(
            AuxTaskUsageDto(task = "title_generation", inputTokens = 30, outputTokens = 10),
            AuxTaskUsageDto(task = "compression", inputTokens = 200, outputTokens = 60),
        ),
        totals = UsageTotalsDto(
            inputTokens = 600, outputTokens = 100, cacheReadTokens = 300,
            estimatedCost = 1.5, sessions = 12, apiCalls = 140,
        ),
        periodDays = 30,
    )

    @Test fun headline_is_main_plus_auxiliary() {
        val s = summarize(usage, today)
        assertEquals(1_000L, s.mainTokens)   // 600 + 100 + 300
        assertEquals(300L, s.auxTokens)      // (30+10) + (200+60)
        assertEquals(1_300L, s.totalTokens)
    }

    /**
     * `totals` counts the main agent only; `by_model` already has auxiliary spend folded in. Taking
     * the larger is what stops the headline cost from being lower than the model rows beneath it.
     */
    @Test fun cost_takes_the_fuller_of_the_two_accounts() {
        assertEquals(2.0, summarize(usage, today).estimatedCost, 1e-9)
    }

    @Test fun models_are_ordered_by_token_volume() {
        assertEquals(listOf("big", "small"), summarize(usage, today).models.map { it.model })
    }

    @Test fun aux_tasks_are_ordered_by_token_volume() {
        assertEquals(
            listOf("compression", "title_generation"),
            summarize(usage, today).auxTasks.map { it.task },
        )
    }

    @Test fun daily_is_filled_to_the_period_length() {
        val s = summarize(usage, today)
        assertEquals(30, s.daily.size)
        assertEquals("2026-09-03", s.daily.last().day)
    }

    @Test fun a_zero_period_falls_back_to_thirty_days() {
        assertEquals(30, summarize(usage.copy(periodDays = 0), today).periodDays)
    }

    // --- empty vs failed ---------------------------------------------------

    @Test fun an_untouched_profile_reads_as_empty() {
        assertTrue(summarize(UsageDto(), today).isEmpty)
    }

    @Test fun a_profile_with_only_auxiliary_traffic_is_not_empty() {
        val auxOnly = UsageDto(byTask = listOf(AuxTaskUsageDto(task = "vision", inputTokens = 5)))
        assertFalse(summarize(auxOnly, today).isEmpty)
    }

    @Test fun a_profile_with_sessions_but_no_tokens_is_not_empty() {
        val started = UsageDto(totals = UsageTotalsDto(sessions = 1))
        assertFalse(summarize(started, today).isEmpty)
    }

    // --- error mapping -----------------------------------------------------

    @Test fun an_offline_connector_gets_its_own_code() {
        val e = HermesApiException(503, """{"error":"device_offline"}""").toUsageError("usage_load")
        assertEquals(AppErrorCode.CONNECTOR_OFFLINE, e.code)
        assertTrue(e.retryable)
    }

    @Test fun a_stalled_tunnel_reads_as_a_timeout() {
        assertEquals(AppErrorCode.RPC_TIMEOUT, SocketTimeoutException("timeout").toUsageError("usage_load").code)
    }

    @Test fun other_server_failures_stay_generic() {
        assertEquals(AppErrorCode.RPC_FAILED, HermesApiException(500, "boom").toUsageError("usage_load").code)
        // 503 alone is not enough — the Relay uses it for capacity too.
        assertEquals(
            AppErrorCode.RPC_FAILED,
            HermesApiException(503, """{"error":"relay_capacity_reached"}""").toUsageError("usage_load").code,
        )
    }

    @Test fun the_diagnostic_keeps_the_http_status() {
        val e = HermesApiException(503, "device_offline").toUsageError("usage_load")
        assertTrue(e.technicalCause!!.contains("503"))
        assertEquals("usage_load", e.stage)
    }

    // --- wire parsing ------------------------------------------------------

    /** Mirrors the app's Json (see AppModule): SQLite SUM() over no rows comes back as null. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test fun null_aggregates_from_an_empty_database_parse_as_zero() {
        val payload = """
            {"daily":[],"by_model":[],"by_task":[],"period_days":30,
             "totals":{"total_input":null,"total_output":null,"total_cache_read":null,
                       "total_estimated_cost":null,"total_sessions":null,"total_api_calls":null}}
        """.trimIndent()
        val dto = json.decodeFromString<UsageDto>(payload)
        assertEquals(0L, dto.totals.inputTokens)
        assertEquals(0, dto.totals.sessions)
        assertTrue(summarize(dto, today).isEmpty)
    }

    @Test fun unknown_upstream_fields_are_ignored() {
        val payload = """
            {"daily":[{"day":"2026-09-03","input_tokens":5,"cache_read_tokens":2,"surprise":true}],
             "skills":{"anything":1},"tools":{"grep":3}}
        """.trimIndent()
        val dto = json.decodeFromString<UsageDto>(payload)
        assertEquals(1, dto.daily.size)
        assertEquals(2L, dto.daily.first().cacheReadTokens)
    }

    @Test fun compact_switches_unit_at_the_thousand_and_million_marks() {
        assertEquals("999", 999L.compact())
        assertEquals("1.0K", 1_000L.compact())
        assertEquals("1.0M", 1_000_000L.compact())
        assertEquals("14.7M", 14_700_000L.compact())
    }
}
