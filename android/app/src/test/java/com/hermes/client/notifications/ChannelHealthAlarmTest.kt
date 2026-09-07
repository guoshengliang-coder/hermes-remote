package com.hermes.client.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelHealthAlarmTest {
    @Test fun a_channel_going_down_notifies_once() {
        val first = decideChannelHealth(down = setOf("slack"), alreadyReported = emptySet())
        assertEquals(listOf("slack"), first.newlyDown)
        // The same outage on the next wake-up says nothing.
        val second = decideChannelHealth(down = setOf("slack"), alreadyReported = first.remembered)
        assertTrue(second.newlyDown.isEmpty())
        assertFalse(second.clearAll)
    }

    @Test fun a_second_channel_going_down_notifies_on_its_own() {
        val decision = decideChannelHealth(setOf("slack", "dingtalk"), alreadyReported = setOf("slack"))
        assertEquals(listOf("dingtalk"), decision.newlyDown)
    }

    @Test fun recovery_withdraws_the_notification_and_arms_the_next_one() {
        val recovered = decideChannelHealth(down = emptySet(), alreadyReported = setOf("slack"))
        assertTrue(recovered.clearAll)
        assertTrue(recovered.remembered.isEmpty())
        // Breaking again is news again.
        val again = decideChannelHealth(setOf("slack"), alreadyReported = recovered.remembered)
        assertEquals(listOf("slack"), again.newlyDown)
    }

    @Test fun a_healthy_run_with_nothing_remembered_does_nothing() {
        val quiet = decideChannelHealth(down = emptySet(), alreadyReported = emptySet())
        assertTrue(quiet.newlyDown.isEmpty())
        assertFalse(quiet.clearAll)
    }

    @Test fun a_channel_that_recovers_while_another_stays_down_keeps_the_notification() {
        val decision = decideChannelHealth(setOf("slack"), alreadyReported = setOf("slack", "feishu"))
        assertTrue(decision.newlyDown.isEmpty())
        assertFalse(decision.clearAll)
        assertEquals(setOf("slack"), decision.remembered)
    }
}
