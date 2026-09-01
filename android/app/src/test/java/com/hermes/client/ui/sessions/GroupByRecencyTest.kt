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

    @Test fun buckets_are_today_rolling7_earlier_and_sorted_newest_first() {
        val g = groupByRecency(
            listOf(
                session("t1", "2026-09-01T17:00:00Z"),  // 今天 01:00 CST (Sep 2)
                session("t2", "2026-09-02T01:00:00Z"),  // 今天 09:00 CST — newest
                session("w1", "2026-09-01T10:00:00Z"),  // 昨天 → 前 7 天
                session("w2", "2026-08-26T17:00:00Z"),  // 窗口内最早 (Aug 27 CST 01:00)
                session("e1", "2026-08-25T10:00:00Z"),  // 7 天窗口外 → 更早
                session("e2", null),                     // 无时间戳 → 更早，排最后
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(listOf("t2", "t1"), g.today.map { it.id })
        assertEquals(listOf("w1", "w2"), g.week.map { it.id })
        assertEquals(listOf("e1", "e2"), g.earlier.map { it.id })
    }

    // The window is ROLLING: exactly 7*24h before start-of-today is IN the week bucket;
    // one millisecond earlier is not. No calendar-week cliff.
    @Test fun rolling_window_boundary_is_start_of_today_minus_seven_days() {
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
}
