package com.hermes.client.notifications

/**
 * What to do about channel health on this wake-up.
 *
 * Kept pure and separate from the fetch so the noisy part — "do not tell the user the same thing
 * every fifteen minutes" — is decided by tests rather than observed on a device over an afternoon.
 */
data class ChannelHealthDecision(
    /** Channels to notify about now: down, and not already reported. */
    val newlyDown: List<String> = emptyList(),
    /** The set to remember; recovered channels drop out so a later outage notifies again. */
    val remembered: Set<String> = emptySet(),
    /** True when nothing is down any more and a standing notification should be withdrawn. */
    val clearAll: Boolean = false,
)

/**
 * A channel that is down notifies once. It keeps notifying only if it recovers and breaks again —
 * a permanent outage that re-announced itself every fifteen minutes would train the user to swipe
 * the whole category away, and the one notification that mattered would go with it.
 */
fun decideChannelHealth(down: Set<String>, alreadyReported: Set<String>): ChannelHealthDecision {
    val newly = (down - alreadyReported).sorted()
    return ChannelHealthDecision(
        newlyDown = newly,
        remembered = down,
        clearAll = down.isEmpty() && alreadyReported.isNotEmpty(),
    )
}
