package com.hermes.client.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsYouRevealTest {
    @Test fun no_new_ids_means_no_action() {
        assertEquals(
            NeedsYouReveal.NONE,
            needsYouRevealAction(setOf("a"), setOf("a"), firstVisibleIndex = 0, isScrolling = false),
        )
        // Shrinking set (an approval resolved) must not scroll or pill either.
        assertEquals(
            NeedsYouReveal.NONE,
            needsYouRevealAction(setOf("a", "b"), setOf("a"), firstVisibleIndex = 0, isScrolling = false),
        )
    }

    @Test fun new_id_near_top_scrolls_into_view() {
        assertEquals(
            NeedsYouReveal.SCROLL_TO_TOP,
            needsYouRevealAction(emptySet(), setOf("a"), firstVisibleIndex = 0, isScrolling = false),
        )
        assertEquals(
            NeedsYouReveal.SCROLL_TO_TOP,
            needsYouRevealAction(setOf("a"), setOf("a", "b"), firstVisibleIndex = 2, isScrolling = false),
        )
    }

    @Test fun new_id_while_deep_in_list_shows_pill_instead_of_yanking() {
        assertEquals(
            NeedsYouReveal.SHOW_PILL,
            needsYouRevealAction(emptySet(), setOf("a"), firstVisibleIndex = 9, isScrolling = false),
        )
    }

    @Test fun new_id_mid_drag_shows_pill_even_near_top() {
        assertEquals(
            NeedsYouReveal.SHOW_PILL,
            needsYouRevealAction(emptySet(), setOf("a"), firstVisibleIndex = 0, isScrolling = true),
        )
    }
}
