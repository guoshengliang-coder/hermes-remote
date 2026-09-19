package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.repository.SessionReadStore
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherBadgeTest {
    private val a = SessionRuntimeKey("work", "a")
    private val b = SessionRuntimeKey("work", "b")
    private val c = SessionRuntimeKey("work", "c", deviceId = "mac-1")

    private fun token(key: SessionRuntimeKey) =
        SessionReadStore.token(key.profile, key.sessionId, key.deviceId)

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
        assertEquals(0, badgeCount(emptySet(), emptyMap()))
    }

    @Test fun unread_sessions_alone_make_the_count() {
        assertEquals(2, badgeCount(setOf(token(a), token(b)), emptyMap()))
    }

    @Test fun sessions_waiting_on_the_user_count_even_when_they_are_not_unread() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL), card(b, NotificationKind.NEEDS_ANSWER))
        assertEquals(2, badgeCount(emptySet(), cards))
    }

    /** The whole point of unioning: this is the case a plain sum would get wrong. */
    @Test fun a_session_that_is_both_unread_and_waiting_counts_once() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL))
        assertEquals(1, badgeCount(setOf(token(a)), cards))
    }

    @Test fun running_and_finished_cards_do_not_add_to_the_count_on_their_own() {
        val cards = mapOf(card(a, NotificationKind.RUNNING), card(b, NotificationKind.COMPLETED))
        assertEquals(0, badgeCount(emptySet(), cards))
    }

    /** A completed run is only news until it is read, and the unread set is what says so. */
    @Test fun a_finished_run_counts_through_the_unread_set() {
        val cards = mapOf(card(a, NotificationKind.COMPLETED))
        assertEquals(1, badgeCount(setOf(token(a)), cards))
    }

    /**
     * The unread set is persisted and outlives the runtime snapshot; those sessions still draw a
     * dot in the session list, so the badge has to agree with the list rather than with the shade.
     */
    @Test fun unread_sessions_with_no_card_still_count() {
        val cards = mapOf(card(a, NotificationKind.NEEDS_APPROVAL))
        assertEquals(3, badgeCount(setOf(token(a), token(b), token(c)), cards))
    }

    @Test fun device_scoped_and_legacy_keys_are_not_confused_for_each_other() {
        val legacy = SessionRuntimeKey("work", "c")
        assertEquals(2, badgeCount(setOf(token(c), token(legacy)), emptyMap()))
    }

    @Test fun reading_the_last_unread_session_clears_the_badge() {
        assertEquals(0, badgeCount(emptySet(), mapOf(card(a, NotificationKind.COMPLETED))))
    }
}
