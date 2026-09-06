package com.hermes.client.ui.messaging

import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

/** A section of the channel list, in the order the list renders them. */
enum class MessagingGroup { NEEDS_YOU, CONNECTED, PENDING_RESTART, INACTIVE }

data class MessagingSection(
    val group: MessagingGroup,
    val title: LocalizedText,
    val platforms: List<MessagingPlatformDto>,
)

/**
 * Groups configured channels the way the session and cron lists group their rows: what is broken
 * first, then what is working, then what is waiting on you, then what is switched off. A flat list
 * makes the one channel that stopped working indistinguishable from the twenty that are fine.
 *
 * Pure, so the ordering is pinned by tests rather than by reading the screen.
 */
fun messagingSections(platforms: List<MessagingPlatformDto>): List<MessagingSection> {
    val buckets = LinkedHashMap<MessagingGroup, MutableList<MessagingPlatformDto>>()
    for (platform in platforms) {
        val group = when (messagingRowStatus(platform)) {
            MessagingRowStatus.FAILED, MessagingRowStatus.GATEWAY_STOPPED -> MessagingGroup.NEEDS_YOU
            MessagingRowStatus.CONNECTED -> MessagingGroup.CONNECTED
            MessagingRowStatus.PENDING_RESTART -> MessagingGroup.PENDING_RESTART
            // An unknown state is not an alert on its own — the server simply told us something this
            // release does not model. Park it with the inactive ones rather than crying wolf.
            MessagingRowStatus.NOT_CONFIGURED, MessagingRowStatus.DISABLED,
            MessagingRowStatus.UNKNOWN -> MessagingGroup.INACTIVE
        }
        buckets.getOrPut(group) { mutableListOf() }.add(platform)
    }
    // A platform Hermes flagged with needs_attention belongs up top whatever its state says.
    buckets[MessagingGroup.CONNECTED]?.filter { it.needsAttention }?.forEach { flagged ->
        buckets[MessagingGroup.CONNECTED]?.remove(flagged)
        buckets.getOrPut(MessagingGroup.NEEDS_YOU) { mutableListOf() }.add(flagged)
    }
    return MessagingGroup.entries.mapNotNull { group ->
        buckets[group]?.takeIf { it.isNotEmpty() }?.let { MessagingSection(group, messagingGroupTitle(group), it) }
    }
}

fun messagingGroupTitle(group: MessagingGroup): LocalizedText = when (group) {
    MessagingGroup.NEEDS_YOU -> localizedText("需要你处理", "Needs you")
    MessagingGroup.CONNECTED -> localizedText("已连接", "Connected")
    MessagingGroup.PENDING_RESTART -> localizedText("待重启生效", "Waiting for a restart")
    MessagingGroup.INACTIVE -> localizedText("未启用", "Not in use")
}

/** Channels saved but not yet live. The list offers one restart for all of them, not one each. */
fun pendingRestartPlatforms(platforms: List<MessagingPlatformDto>): List<MessagingPlatformDto> =
    platforms.filter { messagingRowStatus(it) == MessagingRowStatus.PENDING_RESTART }
