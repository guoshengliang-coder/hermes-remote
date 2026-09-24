package com.hermes.client.data.error

import com.hermes.client.data.network.GatewayReadinessTimeoutException
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.GatewayResponseTimeoutException
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HG-65: nine taps on 新建会话, nine `session.create` calls that died with the socket carrying them,
 * and nine two-second toasts reading `HR-RPC-001` — a transport code that says the request failed
 * and nothing about why, when nothing was wrong with the request at all.
 */
class NewChatFailuresTest {
    @Test fun a_sent_create_without_a_reply_explains_the_recovery() {
        val error = newChatFailure(GatewayResponseTimeoutException("gateway response timeout"), 0)
        assertEquals(AppErrorCode.SESSION_CREATE_UNCONFIRMED, error.code)
        assertTrue(error.retryable)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("稍后重试"))
    }
    @Test fun a_single_dropped_connection_is_an_interruption_worth_retrying_now() {
        val error = newChatFailure(GatewayRpcException(0, "closed"), droppedConnections = 1)

        assertEquals(AppErrorCode.CONNECTION_INTERRUPTED, error.code)
        assertTrue(error.retryable)
    }

    @Test fun a_far_end_that_keeps_hanging_up_says_so_instead_of_offering_a_bare_retry() {
        val error = newChatFailure(GatewayRpcException(0, "closed"), droppedConnections = 5)

        assertEquals(AppErrorCode.CONNECTION_UNSTABLE, error.code)
        assertEquals("HR-CONN-007", error.code.value)
        assertTrue(error.retryable)
    }

    @Test fun the_handshake_failure_keeps_its_own_registered_meaning() {
        val error = newChatFailure(
            GatewayReadinessTimeoutException("gateway readiness timeout"),
            droppedConnections = 9,
        )

        assertEquals(AppErrorCode.HANDSHAKE_TIMEOUT, error.code)
    }

    /** Upstream answering and refusing IS an RPC failure, and must keep the transport code. */
    @Test fun a_refusal_from_upstream_stays_an_rpc_failure() {
        val error = newChatFailure(GatewayRpcException(4001, "session not found"), droppedConnections = 9)

        assertEquals(AppErrorCode.RPC_FAILED, error.code)
    }

    @Test fun both_languages_are_real_sentences_and_neither_leaks_the_cause() {
        val error = newChatFailure(GatewayRpcException(0, "closed"), droppedConnections = 4)

        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("连接反复中断"))
        assertTrue(en.contains("keeps dropping"))
        assertFalse("the raw transport reason belongs in diagnostics only", zh.contains("closed"))
    }

    @Test fun the_diagnostic_keeps_the_cause_the_message_does_not_show() {
        val error = newChatFailure(GatewayRpcException(0, "closed"), droppedConnections = 4)

        val diagnostic = error.sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-CONN-007"))
        assertTrue(diagnostic.contains("session.create"))
        assertTrue(diagnostic.contains("dropped=4"))
    }
}
