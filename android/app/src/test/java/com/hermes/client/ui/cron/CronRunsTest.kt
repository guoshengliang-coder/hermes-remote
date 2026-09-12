package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The run history's duration (docs/DESIGN.md §5.18). It is derived, not a field — the point of
 * these cases is that the derivation refuses to guess when the pair of stamps cannot support one.
 */
class CronRunsTest {
    private fun zh(started: Double?, ended: Double?) =
        cronRunDurationLabel(started, ended, AppLanguage.ZH)

    private fun en(started: Double?, ended: Double?) =
        cronRunDurationLabel(started, ended, AppLanguage.EN)

    @Test
    fun seconds_read_as_seconds() {
        assertEquals("耗时 42s", zh(1_000.0, 1_042.0))
        assertEquals("took 42s", en(1_000.0, 1_042.0))
    }

    @Test
    fun a_minute_or_more_splits_into_minutes_and_seconds() {
        assertEquals("耗时 3 分 12 秒", zh(0.0, 192.0))
        assertEquals("took 3m 12s", en(0.0, 192.0))
    }

    @Test
    fun a_whole_number_of_minutes_does_not_say_zero_seconds() {
        assertEquals("耗时 5 分", zh(0.0, 300.0))
        assertEquals("took 5m", en(0.0, 300.0))
    }

    @Test
    fun hours_drop_the_seconds() {
        assertEquals("耗时 1 小时 4 分", zh(0.0, 3_840.0))
        assertEquals("took 1h 4m", en(0.0, 3_840.0))
        assertEquals("耗时 2 小时", zh(0.0, 7_200.0))
    }

    @Test
    fun a_run_still_going_has_no_duration() {
        assertNull(zh(1_000.0, null))
        assertNull(zh(null, 1_042.0))
        assertNull(zh(null, null))
    }

    @Test
    fun an_end_before_its_start_is_refused_rather_than_shown_negative() {
        // A clock skew or a server bug. "-3s" on screen helps nobody and looks like our arithmetic.
        assertNull(zh(1_042.0, 1_039.0))
    }

    @Test
    fun sub_second_runs_round_rather_than_vanish() {
        assertEquals("耗时 1s", zh(0.0, 0.6))
        assertEquals("耗时 0s", zh(0.0, 0.2))
    }
}
