package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.repository.SessionReadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherBadgeTest {
    private val a = SessionRuntimeKey("work", "a")
    private val b = SessionRuntimeKey("work", "b")
    private val c = SessionRuntimeKey("work", "c", deviceId = "mac-1")

    private fun token(key: SessionRuntimeKey) =
        SessionReadStore.token(key.profile, key.sessionId, key.deviceId)

    /** The session list knows these conversations. */
    private fun known(vararg keys: SessionRuntimeKey) = keys.map(::token).toSet()

    private fun card(key: SessionRuntimeKey, kind: NotificationKind) = key to NotificationSpec(
        id = key.sessionId.hashCode(),
        channelId = Notif.CHANNEL_ATTENTION,
        title = "Task ${key.sessionId}",
        body = "",
        route = null,
        kind = kind,
        sessionKey = key,
    )

    @Test fun nothing_unread_and_nothing_waiting_is_no_badge() {
        assertEquals(0, badgeCount(emptySet(), emptyMap(), known()))
    }

    @Test fun unread_sessions_alone_make_the_count() {
        assertEquals(2, badgeCount(setOf(token(a), token(b)), emptyMap(), known(a, b)))
    }

    @Test fun sessions_waiting_on_the_user_count_even_when_they_are_not_unread() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL), card(b, NotificationKind.NEEDS_ANSWER))
        assertEquals(2, badgeCount(emptySet(), cards, known(a, b)))
    }

    /** The whole point of unioning: this is the case a plain sum would get wrong. */
    @Test fun a_session_that_is_both_unread_and_waiting_counts_once() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL))
        assertEquals(1, badgeCount(setOf(token(a)), cards, known(a)))
    }

    @Test fun running_and_finished_cards_do_not_add_to_the_count_on_their_own() {
        val cards = mapOf(card(a, NotificationKind.RUNNING), card(b, NotificationKind.COMPLETED))
        assertEquals(0, badgeCount(emptySet(), cards, known(a, b)))
    }

    /** A completed run is only news until it is read, and the unread set is what says so. */
    @Test fun a_finished_run_counts_through_the_unread_set() {
        val cards = mapOf(card(a, NotificationKind.COMPLETED))
        assertEquals(1, badgeCount(setOf(token(a)), cards, known(a)))
    }

    /**
     * The 0.1.131 regression: the unread set is persisted and unbounded, and only opening that
     * exact conversation clears an entry. Tokens whose session the list can no longer reach are
     * therefore permanently stuck, and counting them put 40 on one user's icon while the app
     * showed no unread dot anywhere.
     */
    @Test fun unread_tokens_for_sessions_the_list_cannot_show_are_not_counted() {
        assertEquals(0, badgeCount(setOf(token(a), token(b), token(c)), emptyMap(), known()))
    }

    @Test fun orphan_tokens_do_not_inflate_the_count_of_real_unread_sessions() {
        val unread = setOf(token(a), token(b), token(c))
        assertEquals(1, badgeCount(unread, emptyMap(), known(a)))
    }

    /**
     * A session waiting on the user has a notification card to act on, so it counts even when the
     * list has not caught up with it — the badge is never pointing at something unreachable.
     */
    @Test fun a_waiting_session_counts_even_when_the_list_does_not_know_it_yet() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL))
        assertEquals(1, badgeCount(emptySet(), cards, known()))
    }

    /** Blanking the badge on every cold start and restoring it a moment later is worse than waiting. */
    @Test fun the_badge_is_left_alone_until_a_session_list_has_loaded() {
        assertNull(badgeCount(setOf(token(a)), mapOf(card(b, NotificationKind.NEEDS_APPROVAL)), null))
    }

    /** A real empty list is a real zero, unlike a list that has not loaded. */
    @Test fun a_loaded_but_empty_list_clears_the_badge() {
        assertEquals(0, badgeCount(setOf(token(a)), emptyMap(), emptySet()))
    }

    @Test fun device_scoped_and_legacy_keys_are_not_confused_for_each_other() {
        val legacy = SessionRuntimeKey("work", "c")
        assertEquals(2, badgeCount(setOf(token(c), token(legacy)), emptyMap(), known(c, legacy)))
    }

    @Test fun reading_the_last_unread_session_clears_the_badge() {
        assertEquals(0, badgeCount(emptySet(), mapOf(card(a, NotificationKind.COMPLETED)), known(a)))
    }
}
