package com.hermes.client.notifications

import com.hermes.client.ui.chat.ApprovalChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverActionTest {
    @Test fun allow_once_maps_to_approval_once() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.ONCE), receiverActionFor(Notif.ACTION_ALLOW_ONCE))
    }

    @Test fun allow_session_maps_to_approval_session() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.SESSION), receiverActionFor(Notif.ACTION_ALLOW_SESSION))
    }

    @Test fun deny_maps_to_approval_deny() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.DENY), receiverActionFor(Notif.ACTION_DENY))
    }

    @Test fun reply_and_choice_map_to_clarify_answers() {
        assertEquals(ReceiverAction.Reply, receiverActionFor(Notif.ACTION_REPLY))
        assertEquals(ReceiverAction.Choice, receiverActionFor(Notif.ACTION_CHOICE))
    }

    @Test fun dismissed_maps_to_dismissed() {
        assertEquals(ReceiverAction.Dismissed, receiverActionFor(Notif.ACTION_DISMISSED))
    }

    @Test fun null_open_and_unknown_map_to_unknown() {
        assertEquals(ReceiverAction.Unknown, receiverActionFor(null))
        assertEquals(ReceiverAction.Unknown, receiverActionFor(Notif.ACTION_OPEN))
        assertEquals(ReceiverAction.Unknown, receiverActionFor("something_else"))
    }
}
