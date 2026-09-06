package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.MessagingPlatformDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private fun job(id: String, lastStatus: String? = null, deliver: String? = null) =
    CronJobDto(id = id, name = id, lastStatus = lastStatus, deliver = deliver)
private fun platform(id: String, state: String?, name: String? = null, needsAttention: Boolean = false) =
    MessagingPlatformDto(id = id, name = name ?: id, state = state, needsAttention = needsAttention)

class HealthAlertsTest {
    @Test fun a_broken_channel_absorbs_the_deliveries_it_swallowed() {
        val merged = mergeHealth(
            crons = listOf(
                job("standup", "delivery_failed", deliver = "slack"),
                job("rota", "delivery_failed", deliver = "slack"),
                job("weekly", "delivery_failed", deliver = "slack"),
            ),
            platforms = listOf(platform("slack", "startup_failed", name = "Slack")),
            nowMs = NOW,
        )
        // One problem, not four.
        assertEquals(1, merged.total)
        assertEquals(3, merged.channels.single().affectedJobs)
        assertEquals(0, merged.standaloneCronJobs)
        assertTrue(merged.hasChannelCause)
    }

    @Test fun a_real_run_failure_is_never_absorbed() {
        val merged = mergeHealth(
            crons = listOf(job("broken", "error", deliver = "slack")),
            platforms = listOf(platform("slack", "startup_failed")),
            nowMs = NOW,
        )
        assertEquals(2, merged.total)
        assertEquals(1, merged.standaloneCronJobs)
        assertEquals(0, merged.channels.single().affectedJobs)
    }

    @Test fun a_delivery_failure_to_a_healthy_channel_keeps_its_own_alert() {
        val merged = mergeHealth(
            crons = listOf(job("daily", "delivery_failed", deliver = "dingtalk")),
            platforms = listOf(platform("dingtalk", "connected")),
            nowMs = NOW,
        )
        assertEquals(1, merged.total)
        assertEquals(1, merged.standaloneCronJobs)
        assertFalse(merged.hasChannelCause)
    }

    /** `origin` cannot be resolved to a platform from the phone; guessing would blame the wrong one. */
    @Test fun an_unattributable_delivery_failure_is_not_folded() {
        val merged = mergeHealth(
            crons = listOf(job("daily", "delivery_failed", deliver = "origin")),
            platforms = listOf(platform("slack", "startup_failed")),
            nowMs = NOW,
        )
        assertEquals(1, merged.standaloneCronJobs)
        assertEquals(0, merged.channels.single().affectedJobs)
    }

    @Test fun a_needs_attention_flag_counts_as_a_root_cause() {
        val merged = mergeHealth(
            crons = emptyList(),
            platforms = listOf(platform("dingtalk", "connected", needsAttention = true)),
            nowMs = NOW,
        )
        assertEquals(1, merged.total)
        assertTrue(merged.hasChannelCause)
    }

    @Test fun channels_with_the_most_fallout_come_first() {
        val merged = mergeHealth(
            crons = listOf(
                job("a", "delivery_failed", deliver = "slack"),
                job("b", "delivery_failed", deliver = "slack"),
                job("c", "delivery_failed", deliver = "feishu"),
            ),
            platforms = listOf(platform("feishu", "startup_failed"), platform("slack", "startup_failed")),
            nowMs = NOW,
        )
        assertEquals(listOf("slack", "feishu"), merged.channels.map { it.id })
    }

    @Test fun nothing_wrong_means_nothing_to_show() {
        val merged = mergeHealth(
            crons = listOf(job("ok", "ok")),
            platforms = listOf(platform("dingtalk", "connected")),
            nowMs = NOW,
        )
        assertEquals(0, merged.total)
    }

    @Test fun deliver_matching_ignores_case_and_padding() {
        val merged = mergeHealth(
            crons = listOf(job("a", "delivery_failed", deliver = "  SLACK ")),
            platforms = listOf(platform("slack", "startup_failed")),
            nowMs = NOW,
        )
        assertEquals(1, merged.channels.single().affectedJobs)
        assertEquals(0, merged.standaloneCronJobs)
    }
}
