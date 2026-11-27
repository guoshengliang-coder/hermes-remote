package com.hermes.client.data.progress

import com.hermes.client.data.repository.PendingSendConversation
import com.hermes.client.domain.Session
import org.junit.Assert.*
import org.junit.Test

class SendListFeedbackTest {
    private val started = 1_720_000_010_456L
    private val pending = SendListFeedback("op", PendingSendConversation("account", "mac", "work", "local", null, true), "First message", started)

    @Test fun first_turn_remains_listable_until_the_server_row_arrives() {
        val local = mergeSendListFeedback(emptyList(), listOf(pending)).single()
        assertTrue(local.localSendInProgress)
        val accepted = pending.copy(owner = pending.owner.copy(sessionId = "real", localCreation = false), accepted = true)
        val waitingForList = mergeSendListFeedback(emptyList(), listOf(accepted)).single()
        assertEquals(local.localListKey, waitingForList.localListKey)
        assertFalse(waitingForList.localSendInProgress)
        val server = Session("real", "Server title", null, null, 1, "work", lastActive = started, deviceId = "mac")
        assertEquals(listOf(server.copy(localListKey = pending.id)), mergeSendListFeedback(listOf(server), listOf(accepted)))
    }

    @Test fun existing_row_keeps_metadata_and_updates_activity_without_colliding_between_roles() {
        val existing = Session("real", "Existing", null, null, 5, "work", deviceId = "mac")
        val other = existing.copy(profile = "default", title = "Other role")
        val rows = mergeSendListFeedback(listOf(existing, other), listOf(pending.copy(owner = pending.owner.copy(sessionId = "real", localCreation = false))))
        assertEquals(2, rows.size)
        assertEquals("Existing", rows[0].title)
        assertEquals(started, rows[0].lastActive)
        assertEquals(other, rows[1])
    }

    @Test fun old_activity_from_the_same_second_does_not_clear_existing_send_feedback() {
        val existing = Session("real", "Existing", null, null, 5, "work", lastActive = started / 1000 * 1000, deviceId = "mac")
        val accepted = pending.copy(owner = pending.owner.copy(sessionId = "real", localCreation = false),
            newRow = false, accepted = true, baselineCount = 5)
        assertEquals(started, mergeSendListFeedback(listOf(existing), listOf(accepted)).single().lastActive)
        assertTrue(accepted.caughtUp(existing.copy(messageCount = 6)))
    }

}
