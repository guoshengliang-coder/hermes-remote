package com.hermes.client.ui.settings

import com.hermes.client.ui.localization.AppLanguage
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeCheckTimeTest {
    private val zone = ZoneId.systemDefault()
    // Anchor "now" at local noon so the today/yesterday boundaries are deterministic in any zone.
    private val now = LocalDate.now(zone).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    @Test fun justNowAndMinutes() {
        assertEquals("刚刚", relativeCheckTime(now - 20_000, now, AppLanguage.ZH))
        assertEquals("3 分钟前", relativeCheckTime(now - 3 * 60_000, now, AppLanguage.ZH))
        assertEquals("3m ago", relativeCheckTime(now - 3 * 60_000, now, AppLanguage.EN))
    }

    @Test fun sameDayShowsTodayWithClock() {
        val morning = now - 3 * 60 * 60_000
        assertEquals("今天 09:00", relativeCheckTime(morning, now, AppLanguage.ZH))
        assertTrue(relativeCheckTime(morning, now, AppLanguage.EN).startsWith("Today "))
    }

    @Test fun yesterdayAndFullDate() {
        val yesterday = now - 24 * 60 * 60_000
        assertTrue(relativeCheckTime(yesterday, now, AppLanguage.ZH).startsWith("昨天 "))
        val lastWeek = now - 7L * 24 * 60 * 60_000
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(relativeCheckTime(lastWeek, now, AppLanguage.ZH)))
    }

    @Test fun invalidTimestampRendersEmpty() {
        assertEquals("", relativeCheckTime(0, now, AppLanguage.ZH))
        assertEquals(0L, parseInstantMs("not-a-date"))
    }
}
