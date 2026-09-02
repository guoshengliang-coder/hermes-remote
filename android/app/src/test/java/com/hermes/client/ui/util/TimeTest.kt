package com.hermes.client.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    @Test fun relative_time_past_and_future() {
        assertEquals("just now", relativeTime(now, now))
        assertEquals("5m ago", relativeTime(now - 5 * min, now))
        assertEquals("3h ago", relativeTime(now - 3 * 60 * min, now))
        assertEquals("2d ago", relativeTime(now - 2 * 24 * 60 * min, now))
        assertEquals("in 30m", relativeTime(now + 30 * min, now))
        assertEquals("in 2h", relativeTime(now + 2 * 60 * min, now))
        assertEquals("—", relativeTime(null, now))
    }

    @Test fun iso_to_epoch_ms() {
        assertEquals(0L, isoToEpochMs("1970-01-01T00:00:00Z"))
        assertNull(isoToEpochMs(null))
        assertNull(isoToEpochMs(""))
        assertNull(isoToEpochMs("not-a-date"))
    }

    @Test fun seconds_to_epoch_ms() {
        assertEquals(1_000L, secondsToEpochMs(1.0))
        assertNull(secondsToEpochMs(null))
    }

    @Test fun relativeTimeLabel_isBilingualAndBucketed() {
        val now = 1_700_000_000_000L
        val zh = com.hermes.client.ui.localization.AppLanguage.ZH
        val en = com.hermes.client.ui.localization.AppLanguage.EN
        assertEquals("—", relativeTimeLabel(null, now, zh))
        assertEquals("刚刚", relativeTimeLabel(now - 30_000, now, zh))
        assertEquals("12 分钟前", relativeTimeLabel(now - 12 * 60_000, now, zh))
        assertEquals("12m ago", relativeTimeLabel(now - 12 * 60_000, now, en))
        assertEquals("3 小时前", relativeTimeLabel(now - 3 * 3_600_000, now, zh))
        assertEquals("昨天", relativeTimeLabel(now - 30 * 3_600_000, now, zh))
        assertEquals("yesterday", relativeTimeLabel(now - 30 * 3_600_000, now, en))
        assertEquals("3 天前", relativeTimeLabel(now - 3 * 24 * 3_600_000, now, zh))
        // A timestamp slightly in the future (clock skew) reads as "just now", never negative.
        assertEquals("just now", relativeTimeLabel(now + 5_000, now, en))
    }
}
