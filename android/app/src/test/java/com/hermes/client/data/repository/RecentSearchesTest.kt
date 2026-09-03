package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test fun push_moves_to_front_dedupes_and_caps() {
        var list = emptyList<String>()
        for (i in 1..10) list = pushRecentSearch(list, "q$i")
        assertEquals(RECENT_SEARCHES_MAX, list.size)
        assertEquals("q10", list.first())
        assertEquals("q3", list.last())

        list = pushRecentSearch(list, " Q5 ")
        assertEquals("Q5", list.first())
        assertEquals(RECENT_SEARCHES_MAX, list.size)
        assertEquals(1, list.count { it.equals("q5", ignoreCase = true) })
    }

    @Test fun push_ignores_blank() {
        assertEquals(listOf("a"), pushRecentSearch(listOf("a"), "   "))
    }

    @Test fun remove_is_case_insensitive_and_trimmed() {
        assertEquals(listOf("b"), removeRecentSearch(listOf("Deploy", "b"), " deploy "))
    }
}
