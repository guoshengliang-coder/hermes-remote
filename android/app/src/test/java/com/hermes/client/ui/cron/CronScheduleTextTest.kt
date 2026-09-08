package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class CronScheduleTextTest {
    /** What the list was actually showing before: the raw expression. */
    @Test fun a_daily_job_reads_as_a_time_not_an_expression() {
        assertEquals("每天 18:15", cronScheduleText("15 18 * * *", AppLanguage.ZH))
        assertEquals("每天 08:00", cronScheduleText("0 8 * * *", AppLanguage.ZH))
        assertEquals("Every day at 08:00", cronScheduleText("0 8 * * *", AppLanguage.EN))
    }

    /** The two watchdogs on this Hermes use a two-minute step, which parseCron calls Advanced. */
    @Test fun step_minutes_read_as_every_n_minutes() {
        assertEquals("每 2 分钟", cronScheduleText("*/2 * * * *", AppLanguage.ZH))
        assertEquals("每 30 分钟", cronScheduleText("*/30 * * * *", AppLanguage.ZH))
        assertEquals("Every 2 minutes", cronScheduleText("*/2 * * * *", AppLanguage.EN))
        assertEquals("每分钟", cronScheduleText("*/1 * * * *", AppLanguage.ZH))
    }

    @Test fun step_hours_read_as_every_n_hours() {
        assertEquals("每 3 小时", cronScheduleText("0 */3 * * *", AppLanguage.ZH))
        assertEquals("Every 3 hours", cronScheduleText("0 */3 * * *", AppLanguage.EN))
    }

    @Test fun hourly_and_weekly_go_through_the_existing_describer() {
        assertEquals("每小时", cronScheduleText("0 * * * *", AppLanguage.ZH))
        assertEquals("每月 1 日 09:00", cronScheduleText("0 9 1 * *", AppLanguage.ZH))
    }

    /** An expression this release cannot phrase is shown as-is, never mistranslated. */
    @Test fun an_unrecognised_expression_is_returned_verbatim() {
        assertEquals("5 4 * * 1-5", cronScheduleText("5 4 * * 1-5", AppLanguage.ZH))
        assertEquals("", cronScheduleText(null, AppLanguage.ZH))
        assertEquals("—", cronScheduleText("—", AppLanguage.ZH))
    }
}
