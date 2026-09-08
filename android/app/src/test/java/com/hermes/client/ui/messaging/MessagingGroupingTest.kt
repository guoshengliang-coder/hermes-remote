package com.hermes.client.ui.messaging

import com.hermes.client.data.network.MessagingPlatformDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun platform(id: String, state: String?, needsAttention: Boolean = false) =
    MessagingPlatformDto(id = id, state = state, needsAttention = needsAttention)

class MessagingGroupingTest {
    @Test fun sections_are_ordered_broken_then_working_then_waiting_then_off() {
        val sections = messagingSections(
            listOf(
                platform("email", "disabled"),
                platform("dingtalk", "connected"),
                platform("feishu", "pending_restart"),
                platform("slack", "startup_failed"),
            ),
        )
        assertEquals(
            listOf(
                MessagingGroup.NEEDS_YOU,
                MessagingGroup.CONNECTED,
                MessagingGroup.PENDING_RESTART,
                MessagingGroup.INACTIVE,
            ),
            sections.map { it.group },
        )
        assertEquals("slack", sections.first().platforms.single().id)
    }

    @Test fun empty_groups_are_not_rendered() {
        val sections = messagingSections(listOf(platform("dingtalk", "connected")))
        assertEquals(listOf(MessagingGroup.CONNECTED), sections.map { it.group })
    }

    /** Hermes can flag a channel that still reports `connected` — a retry loop, say. Trust the flag. */
    @Test fun a_needs_attention_flag_lifts_a_connected_channel_to_the_top() {
        val sections = messagingSections(
            listOf(platform("dingtalk", "connected", needsAttention = true), platform("feishu", "connected")),
        )
        assertEquals(MessagingGroup.NEEDS_YOU, sections.first().group)
        assertEquals("dingtalk", sections.first().platforms.single().id)
        assertEquals("feishu", sections[1].platforms.single().id)
    }

    /** An unmodelled state must not masquerade as an alert. */
    @Test fun an_unknown_state_parks_with_the_inactive_ones() {
        val sections = messagingSections(listOf(platform("buzz", "quarantined")))
        assertEquals(MessagingGroup.INACTIVE, sections.single().group)
    }

    @Test fun a_gateway_that_is_down_needs_you() {
        val sections = messagingSections(listOf(platform("dingtalk", "gateway_stopped")))
        assertEquals(MessagingGroup.NEEDS_YOU, sections.single().group)
    }

    @Test fun pending_restart_platforms_are_collected_for_the_single_restart_action() {
        val all = listOf(
            platform("feishu", "pending_restart"),
            platform("wecom", "pending_restart"),
            platform("dingtalk", "connected"),
        )
        assertEquals(listOf("feishu", "wecom"), pendingRestartPlatforms(all).map { it.id })
        assertTrue(pendingRestartPlatforms(listOf(platform("dingtalk", "connected"))).isEmpty())
    }

    @Test fun group_titles_exist_in_both_languages() {
        MessagingGroup.entries.forEach { group ->
            val title = messagingGroupTitle(group)
            assertTrue(title.zh.isNotBlank())
            assertTrue(title.en.isNotBlank())
        }
    }

    /** The default slice hides the two dozen platforms nobody here has ever touched. */
    @Test fun the_configured_slice_leaves_the_untouched_catalog_out() {
        val all = listOf(
            MessagingPlatformDto(id = "dingtalk", state = "connected", configured = true),
            MessagingPlatformDto(id = "irc", state = "not_configured", configured = false),
            MessagingPlatformDto(id = "buzz", state = "not_configured", configured = false),
        )
        assertEquals(listOf("dingtalk"), messagingSlice(all, MessagingFilter.CONFIGURED).map { it.id })
        assertEquals(3, messagingSlice(all, MessagingFilter.ALL).size)
    }
}
