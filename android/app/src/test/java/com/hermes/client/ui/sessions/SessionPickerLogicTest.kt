package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPickerLogicTest {
    private fun session(id: String, title: String, profile: String? = "personal") = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 3, profile = profile,
    )

    private val all = listOf(
        session("s1", "重构 gateway 路由中间件"),
        session("s2", "翻译 Android 文案"),
        session("w1", "工作那边的会话", profile = "work"),
    )

    // The one that matters: another identity's conversation must not even be offered.
    // DESIGN.md §1 principle 1 — and it is invisible on a phone with a single identity.
    @Test fun other_identities_are_not_offered() {
        val ids = sessionPickerCandidates(all, activeProfile = "personal", excludeSessionId = null).map { it.id }
        assertEquals(listOf("s1", "s2"), ids)
    }

    @Test fun the_conversation_you_are_in_is_not_offered() {
        val ids = sessionPickerCandidates(all, activeProfile = "personal", excludeSessionId = "s1").map { it.id }
        assertEquals(listOf("s2"), ids)
    }

    @Test fun a_blank_active_profile_filters_nothing() {
        // Single-profile setups report no active profile; filtering on that would empty the list.
        assertEquals(3, sessionPickerCandidates(all, activeProfile = null, excludeSessionId = null).size)
        assertEquals(3, sessionPickerCandidates(all, activeProfile = "  ", excludeSessionId = null).size)
    }

    @Test fun query_matches_title_case_insensitively_and_ignores_surrounding_space() {
        assertTrue(matchesPickerQuery(session("s1", "重构 gateway 路由中间件"), "gateway"))
        assertTrue(matchesPickerQuery(session("s1", "重构 Gateway 路由中间件"), "  gateway "))
        assertTrue(matchesPickerQuery(session("s1", "重构 gateway 路由中间件"), "路由"))
        assertFalse(matchesPickerQuery(session("s1", "重构 gateway 路由中间件"), "翻译"))
    }

    @Test fun an_empty_query_matches_everything() {
        assertTrue(matchesPickerQuery(session("s1", "任意标题"), ""))
        assertTrue(matchesPickerQuery(session("s1", "任意标题"), "   "))
    }

    @Test fun rows_stop_being_selectable_once_the_slots_are_used_up() {
        val selected = setOf("s1", "s2")
        assertTrue("an already-selected row must stay toggleable off", pickerRowEnabled("s1", selected, remainingSlots = 2))
        assertFalse("a new row must not be selectable past the cap", pickerRowEnabled("s3", selected, remainingSlots = 2))
        assertTrue(pickerRowEnabled("s3", selected, remainingSlots = 4))
    }

    @Test fun selectable_count_never_goes_negative() {
        assertEquals(2, pickerSelectableCount(remainingSlots = 4, selected = 2))
        assertEquals(0, pickerSelectableCount(remainingSlots = 4, selected = 4))
        assertEquals(0, pickerSelectableCount(remainingSlots = 4, selected = 9))
    }

    @Test fun no_slots_left_disables_every_unselected_row() {
        assertFalse(pickerRowEnabled("s1", emptySet(), remainingSlots = 0))
    }

    @Test fun the_two_modes_carry_what_distinguishes_them() {
        // Reference takes several and therefore has a cap; Deliver takes exactly one and does not.
        val reference = SessionPickerMode.Reference(remainingSlots = 4)
        assertEquals(4, reference.remainingSlots)
        assertTrue(SessionPickerMode.Deliver is SessionPickerMode)
    }

}
