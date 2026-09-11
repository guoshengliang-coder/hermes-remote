package com.hermes.client.ui.nav

import com.hermes.client.data.network.CronJobDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The card page's pure rules (docs/DESIGN.md §5.1, product decisions 2026-09-11). */
class CardPageLogicTest {

    @Test fun `latency bands are 优 under 50, 普通 to 150, 延迟 above`() {
        assertEquals(LatencyTier.GOOD, latencyTier(0))
        assertEquals(LatencyTier.GOOD, latencyTier(49))
        assertEquals(LatencyTier.NORMAL, latencyTier(50))
        assertEquals(LatencyTier.NORMAL, latencyTier(150))
        assertEquals(LatencyTier.SLOW, latencyTier(151))
        assertEquals(LatencyTier.SLOW, latencyTier(4_000))
    }

    @Test fun `latency prints ms under a second and seconds above`() {
        assertEquals("29 ms", formatLatency(29))
        assertEquals("999 ms", formatLatency(999))
        assertEquals("1.0 s", formatLatency(1_000))
        assertEquals("2.3 s", formatLatency(2_349))
    }

    @Test fun `build badge reads DEV on debug, the type on other builds, nothing on release`() {
        assertEquals("DEV", buildBadgeFor("debug"))
        assertEquals("DEV", buildBadgeFor("Debug"))
        assertEquals("BETA", buildBadgeFor("beta"))
        assertNull(buildBadgeFor("release"))
        assertNull(buildBadgeFor("Release"))
    }

    @Test fun `the scheduled-jobs count is enabled and unpaused jobs only`() {
        val jobs = listOf(
            CronJobDto(id = "a"),
            CronJobDto(id = "b", enabled = false),
            CronJobDto(id = "c", pausedAt = "2026-09-11T00:00:00Z"),
            CronJobDto(id = "d", enabled = true, pausedAt = null),
        )
        assertEquals(2, activeCronCount(jobs))
        assertEquals(0, activeCronCount(emptyList()))
    }
}
