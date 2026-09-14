package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GroupByRecencyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // Wed 2026-09-02 10:00 CST.
    private val now = Instant.parse("2026-09-02T02:00:00Z").toEpochMilli()

    private fun session(id: String, at: String?) = Session(
        id = id, title = id, model = null, provider = null, messageCount = 1,
        profile = "p", lastActive = at?.let { Instant.parse(it).toEpochMilli() },
    )

    @Test fun buckets_are_today_yesterday_rolling7_earlier_and_sorted_newest_first() {
        val g = groupByRecency(
            listOf(
                session("t1", "2026-09-01T17:00:00Z"),  // 今天 01:00 CST (Sep 2)
                session("t2", "2026-09-02T01:00:00Z"),  // 今天 09:00 CST — newest
                session("y1", "2026-09-01T10:00:00Z"),  // 昨天 18:00 CST (Sep 1)
                session("w1", "2026-08-31T10:00:00Z"),  // 前天 → 前 7 天
                session("w2", "2026-08-26T17:00:00Z"),  // 窗口内最早 (Aug 27 CST 01:00)
                session("e1", "2026-08-25T10:00:00Z"),  // 7 天窗口外 → 更早
                session("e2", null),                     // 无时间戳 → 更早，排最后
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(listOf("t2", "t1"), g.today.map { it.id })
        assertEquals(listOf("y1"), g.yesterday.map { it.id })
        assertEquals(listOf("w1", "w2"), g.week.map { it.id })
        assertEquals(listOf("e1", "e2"), g.earlier.map { it.id })
    }

    // HG-52 carved 昨天 out of the week bucket without moving the window: start-of-yesterday is the
    // first millisecond of 昨天, and one millisecond earlier is still 前 7 天.
    @Test fun yesterday_boundary_is_start_of_yesterday() {
        val startOfYesterday = Instant.parse("2026-08-31T16:00:00Z").toEpochMilli() // Sep 1 00:00 CST
        val g = groupByRecency(
            listOf(
                Session("in", "in", null, null, 1, "p", lastActive = startOfYesterday),
                Session("out", "out", null, null, 1, "p", lastActive = startOfYesterday - 1),
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(listOf("in"), g.yesterday.map { it.id })
        assertEquals(listOf("out"), g.week.map { it.id })
    }

    // The 7-day window did not move when 昨天 was split out: what used to be Today + Previous 7 days
    // is now Today + Yesterday + Previous 7 days, covering exactly the same span, and Earlier keeps
    // everything it had. This is the guarantee the item asked for, so it is asserted directly.
    @Test fun splitting_out_yesterday_did_not_move_the_seven_day_window() {
        val startOfToday = Instant.parse("2026-09-01T16:00:00Z").toEpochMilli() // Sep 2 00:00 CST
        val floor = startOfToday - 7L * 24 * 60 * 60 * 1000
        val g = groupByRecency(
            listOf(
                Session("in", "in", null, null, 1, "p", lastActive = floor),
                Session("out", "out", null, null, 1, "p", lastActive = floor - 1),
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(listOf("in"), g.week.map { it.id })
        assertEquals(listOf("out"), g.earlier.map { it.id })
    }

    /**
     * Yesterday is a CALENDAR day back, not `now - 24h`.
     *
     * On the US spring-forward Sunday, 2026-03-08 in New York, the day is 23 hours long. A session
     * at 00:30 that morning is yesterday when viewed on the Monday; `startOfToday - 24h` would put
     * the boundary at 23:00 on the Saturday and drag the last hour of Saturday into 昨天 with it.
     */
    @Test fun yesterday_is_a_calendar_day_even_across_a_dst_change() {
        val newYork = ZoneId.of("America/New_York")
        // Mon 2026-03-09 10:00 EDT.
        val mondayMorning = Instant.parse("2026-03-09T14:00:00Z").toEpochMilli()
        val g = groupByRecency(
            listOf(
                // Sun 2026-03-08 00:30 EST — inside the short day, so 昨天.
                session("y1", "2026-03-08T05:30:00Z"),
                // Sat 2026-03-07 23:30 EST — the hour `startOfToday - 24h` would wrongly capture.
                session("w1", "2026-03-08T04:30:00Z"),
            ),
            nowMs = mondayMorning, zone = newYork,
        )
        assertEquals(listOf("y1"), g.yesterday.map { it.id })
        assertEquals(listOf("w1"), g.week.map { it.id })
    }
}
