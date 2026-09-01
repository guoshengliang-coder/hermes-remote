package com.hermes.client.ui.sessions

/** How to surface sessions that newly entered the 需要你处理 group. */
enum class NeedsYouReveal { NONE, SCROLL_TO_TOP, SHOW_PILL }

/**
 * LazyColumn's scroll anchoring keeps the viewport pinned to the previously visible row, so a
 * session promoted into the top 需要你处理 group lands OFF SCREEN above the viewport — invisible
 * exactly when it most needs attention, even when the user was already sitting at the top.
 *
 * Policy: with a NEW id in the group, auto-scroll it into view when the reader is near the top
 * and not mid-drag; deep in the list never yank the reader (the chat screens fought that bug
 * for several releases) — offer a tappable pill instead.
 */
fun needsYouRevealAction(
    previousIds: Set<String>,
    currentIds: Set<String>,
    firstVisibleIndex: Int,
    isScrolling: Boolean,
    nearTopThreshold: Int = 2,
): NeedsYouReveal {
    if ((currentIds - previousIds).isEmpty()) return NeedsYouReveal.NONE
    return if (firstVisibleIndex <= nearTopThreshold && !isScrolling) NeedsYouReveal.SCROLL_TO_TOP
    else NeedsYouReveal.SHOW_PILL
}
