package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()
private fun job(
    id: String, lastStatus: String? = null, enabled: Boolean = true, state: String? = null,
    nextRunAt: String? = iso(NOW + 3_600_000), deliver: String? = null,
) = CronJobDto(
    id = id, name = id, enabled = enabled, state = state, nextRunAt = nextRunAt,
    lastStatus = lastStatus, deliver = deliver,
)

class CronSectionsTest {
    @Test fun trouble_comes_first_then_active_then_paused() {
        val sections = cronSections(
            listOf(
                job("healthy", "ok"),
                job("paused", state = "paused"),
                job("broken", "error"),
                job("undelivered", "delivery_failed"),
            ),
            NOW,
        )
        assertEquals(listOf(CronGroup.NEEDS_YOU, CronGroup.ACTIVE, CronGroup.PAUSED), sections.map { it.group })
        assertEquals(listOf("broken", "undelivered"), sections.first().jobs.map { it.id })
    }

    @Test fun an_overdue_job_needs_you_too() {
        val sections = cronSections(listOf(job("late", nextRunAt = iso(NOW - 600_000))), NOW)
        assertEquals(CronGroup.NEEDS_YOU, sections.single().group)
    }

    @Test fun empty_groups_are_not_rendered() {
        assertEquals(listOf(CronGroup.ACTIVE), cronSections(listOf(job("ok", "ok")), NOW).map { it.group })
    }

    /** `local` is the server's default, so most jobs land here — it must read as a choice, not a gap. */
    @Test fun delivery_targets_are_named_in_both_languages() {
        assertEquals("只存不发", cronDeliveryText(null).resolve(AppLanguage.ZH))
        assertEquals("Saved only", cronDeliveryText("local").resolve(AppLanguage.EN))
        assertEquals("投递到来源聊天", cronDeliveryText("origin").resolve(AppLanguage.ZH))
        assertEquals("投递到 钉钉", cronDeliveryText("dingtalk").resolve(AppLanguage.ZH))
        assertEquals("Delivered to Slack", cronDeliveryText("Slack").resolve(AppLanguage.EN))
    }

    @Test fun group_titles_exist_in_both_languages() {
        CronGroup.entries.forEach {
            assertTrue(cronGroupTitle(it).zh.isNotBlank())
            assertTrue(cronGroupTitle(it).en.isNotBlank())
        }
    }
}
