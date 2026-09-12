package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** 节奏或落点 · 上次结果 — the grammar the three lists share. */
    @Test fun the_subline_reads_rhythm_target_then_outcome() {
        assertEquals(
            "每天 08:30  ·  投递到 钉钉",
            cronSublineText("每天 08:30", "dingtalk", CronRowStatus.OK, AppLanguage.ZH),
        )
        assertEquals(
            "每 10 分钟  ·  只存不发  ·  上次失败",
            cronSublineText("每 10 分钟", null, CronRowStatus.FAILED, AppLanguage.ZH),
        )
        assertEquals(
            "每天 09:30  ·  投递到 Slack  ·  未送达",
            cronSublineText("每天 09:30", "slack", CronRowStatus.UNDELIVERED, AppLanguage.ZH),
        )
    }

    /** A healthy row says nothing about its last run, so the failing one has no competition. */
    @Test fun a_healthy_row_does_not_announce_success() {
        val zh = cronSublineText("每天 08:30", "local", CronRowStatus.OK, AppLanguage.ZH)
        assertTrue("成功" !in zh)
        assertEquals("Daily 08:30  ·  Saved only", cronSublineText("Daily 08:30", "local", CronRowStatus.OK, AppLanguage.EN))
    }

    @Test fun an_absent_schedule_does_not_leave_a_dangling_separator() {
        assertEquals("只存不发  ·  已暂停", cronSublineText("—", null, CronRowStatus.PAUSED, AppLanguage.ZH))
        assertEquals("只存不发", cronSublineText("", null, CronRowStatus.OK, AppLanguage.ZH))
    }

    @Test fun the_two_halves_of_the_subline_rebuild_the_whole_line() {
        // The row paints the base and the outcome in different colours, so it reads them through
        // the two halves rather than the whole string. If they ever stop composing back into
        // cronSublineText, the row and the pinned grammar have silently parted ways.
        for (status in CronRowStatus.entries) {
            for (deliver in listOf(null, "local", "origin", "dingtalk")) {
                for (language in AppLanguage.entries) {
                    val whole = cronSublineText("0 8 * * *", deliver, status, language)
                    val rebuilt = listOfNotNull(
                        cronSublineBase("0 8 * * *", deliver, language),
                        cronSublineOutcome(status, language),
                    ).joinToString(CRON_SUBLINE_SEPARATOR)
                    assertEquals(whole, rebuilt)
                }
            }
        }
    }

    @Test fun only_a_healthy_row_has_no_outcome_half() {
        assertNull(cronSublineOutcome(CronRowStatus.OK, AppLanguage.ZH))
        assertEquals("上次失败", cronSublineOutcome(CronRowStatus.FAILED, AppLanguage.ZH))
        assertEquals("overdue", cronSublineOutcome(CronRowStatus.OVERDUE, AppLanguage.EN))
    }
}
