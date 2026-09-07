package com.hermes.client.data.repository

import com.hermes.client.data.error.AppErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffCodesTest {
    /** Each refusal is a different thing to do about it; one generic message served none of them. */
    @Test fun every_gateway_refusal_maps_to_its_own_code() {
        assertEquals(AppErrorCode.HANDOFF_SESSION_BUSY, handoffErrorCode(4009))
        assertEquals(AppErrorCode.HANDOFF_CHANNEL_DISABLED, handoffErrorCode(4025))
        assertEquals(AppErrorCode.HANDOFF_NO_TARGET, handoffErrorCode(4026))
        assertEquals(AppErrorCode.HANDOFF_IN_FLIGHT, handoffErrorCode(4027))
    }

    @Test fun an_unmodelled_refusal_falls_back_rather_than_guessing() {
        assertEquals(AppErrorCode.RPC_FAILED, handoffErrorCode(5021))
        assertEquals(AppErrorCode.RPC_FAILED, handoffErrorCode(null))
    }

    @Test fun phases_parse_case_insensitively_and_unknowns_stay_unknown() {
        assertEquals(HandoffPhase.PENDING, handoffPhase("pending"))
        assertEquals(HandoffPhase.RUNNING, handoffPhase("  RUNNING "))
        assertEquals(HandoffPhase.COMPLETED, handoffPhase("completed"))
        assertEquals(HandoffPhase.FAILED, handoffPhase("failed"))
        assertEquals(HandoffPhase.UNKNOWN, handoffPhase(""))
        assertEquals(HandoffPhase.UNKNOWN, handoffPhase("reticulating"))
    }
}
