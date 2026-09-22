package com.hermes.client.data.progress

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingReadMarksTest {
    /**
     * HG-103: a push-woken process persisted the session unread; the user opened the app and read
     * it, and DataStore then emitted the older on-disk set before the queued read was written. The
     * emission must not bring the session back, or the badge goes up after reading.
     */
    @Test fun a_queued_read_survives_an_older_emission() {
        assertEquals(setOf("b"), overlayPendingReadMarks(setOf("a", "b"), mapOf("a" to false)))
    }

    @Test fun a_queued_unread_survives_an_older_emission() {
        assertEquals(setOf("a", "c"), overlayPendingReadMarks(setOf("a"), mapOf("c" to true)))
    }

    @Test fun with_nothing_queued_the_emission_is_taken_as_is() {
        assertEquals(setOf("a"), overlayPendingReadMarks(setOf("a"), emptyMap()))
    }
}
