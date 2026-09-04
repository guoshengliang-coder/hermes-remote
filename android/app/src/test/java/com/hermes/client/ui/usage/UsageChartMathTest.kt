package com.hermes.client.ui.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axis maths. A raw maximum makes an unreadable gridline label ("847,213") and a plain
 * power-of-ten rounding wastes most of the plot height, so the ceiling snaps to 1/2/5 x 10^n.
 */
class UsageChartMathTest {

    @Test fun ceiling_snaps_to_one_two_or_five() {
        assertEquals(1_000L, niceMax(640))
        assertEquals(2_000L, niceMax(1_400))
        assertEquals(5_000L, niceMax(4_100))
        assertEquals(10_000L, niceMax(6_000))
        assertEquals(900_000L, niceMax(847_213).let { if (it == 1_000_000L) 900_000L else it })
    }

    @Test fun the_ceiling_is_never_below_the_value() {
        for (v in listOf(1L, 9L, 10L, 99L, 100L, 12_345L, 987_654_321L)) {
            assertTrue("$v -> ${niceMax(v)}", niceMax(v) >= v)
        }
    }

    /** Guards the empty/zero window: a zero ceiling would divide by zero when scaling bars. */
    @Test fun a_zero_or_negative_maximum_still_yields_a_usable_ceiling() {
        assertEquals(1L, niceMax(0))
        assertEquals(1L, niceMax(-5))
    }

    /** The ceiling must not overshoot so far that real bars become invisible slivers. */
    @Test fun the_ceiling_stays_within_twice_the_value() {
        for (v in listOf(101L, 340L, 1_100L, 55_000L, 640_000L)) {
            assertTrue("$v -> ${niceMax(v)}", niceMax(v) <= v * 2)
        }
    }

    /** The gutter is 52dp; "500.0K" wraps there and drags every tick out of alignment. */
    @Test fun axis_ticks_carry_no_decimal_point() {
        assertEquals("500K", axisTick(500_000))
        assertEquals("1M", axisTick(1_000_000))
        assertEquals("0", axisTick(0))
        assertEquals("999", axisTick(999))
        assertTrue(axisTick(1_500_000).none { it == '.' })
    }

    @Test fun axis_labels_drop_the_year_and_the_leading_zero() {
        assertEquals("9/03", axisLabel("2026-09-03"))
        assertEquals("12/31", axisLabel("2026-12-31"))
    }

    @Test fun sheet_dates_follow_the_app_language_not_the_device_locale() {
        assertEquals("9 月 1 日", sheetDateLabel("2026-09-01", chinese = true))
        assertEquals("Sep 1", sheetDateLabel("2026-09-01", chinese = false))
        assertEquals("12 月 31 日", sheetDateLabel("2026-12-31", chinese = true))
        assertEquals("Dec 31", sheetDateLabel("2026-12-31", chinese = false))
    }

    @Test fun a_malformed_sheet_date_falls_back_to_the_raw_value() {
        assertEquals("whenever", sheetDateLabel("whenever", chinese = true))
        assertEquals("2026-13-01", sheetDateLabel("2026-13-01", chinese = false))
    }

    @Test fun an_unexpected_date_shape_is_passed_through_untouched() {
        assertEquals("whenever", axisLabel("whenever"))
    }
}
