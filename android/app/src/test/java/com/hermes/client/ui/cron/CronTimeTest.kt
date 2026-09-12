package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Run times follow the APP's language, not the phone's locale — that was the bug the old
 * `formatIso` carried (docs/DESIGN.md §5.18, `CronTime.kt`).
 */
class CronTimeTest {
    private val at = ZonedDateTime.of(2026, 9, 12, 18, 15, 0, 0, ZoneId.of("UTC"))

    @Test fun a_time_reads_in_the_apps_language() {
        assertEquals("9月 12日 18:15", cronTimeText(at, AppLanguage.ZH))
        assertEquals("Sep 12, 18:15", cronTimeText(at, AppLanguage.EN))
    }

    @Test fun the_clock_is_zero_padded_in_both_languages() {
        val early = ZonedDateTime.of(2026, 1, 5, 8, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals("1月 5日 08:00", cronTimeText(early, AppLanguage.ZH))
        assertEquals("Jan 5, 08:00", cronTimeText(early, AppLanguage.EN))
    }

    @Test fun an_absent_time_is_a_dash_rather_than_a_blank() {
        assertEquals("—", cronTimeText(null as ZonedDateTime?, AppLanguage.ZH))
        assertEquals("—", cronTimeText(null as String?, AppLanguage.EN))
        assertEquals("—", cronTimeText("   ", AppLanguage.EN))
        assertEquals("—", cronRunTimeText(null, AppLanguage.ZH))
    }

    @Test fun an_unparseable_stamp_is_shown_as_it_arrived_rather_than_guessed_at() {
        // Same rule the schedule text follows: never invent a reading of something upstream sent.
        assertEquals("tomorrow-ish", cronTimeText("tomorrow-ish", AppLanguage.ZH))
    }

    @Test fun an_offset_stamp_is_read_as_an_instant() {
        // 2026-09-12T18:15+08:00 is 10:15 UTC. Both render the same instant in the device zone.
        val utc = cronTimeText("2026-09-12T10:15:00Z", AppLanguage.EN)
        val plus8 = cronTimeText("2026-09-12T18:15:00+08:00", AppLanguage.EN)
        assertEquals(utc, plus8)
    }

    @Test fun epoch_seconds_and_an_iso_stamp_agree() {
        val iso = cronTimeText("2026-09-12T10:15:00Z", AppLanguage.ZH)
        assertEquals(iso, cronRunTimeText(1_789_208_100.0, AppLanguage.ZH))
    }
}
