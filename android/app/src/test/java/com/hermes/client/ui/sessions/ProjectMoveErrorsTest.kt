package com.hermes.client.ui.sessions

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMoveErrorsTest {
    @Test fun maps_upstream_codes_to_registered_product_errors() {
        assertEquals(AppErrorCode.SESSION_BUSY, workspaceMoveError(GatewayRpcException(4009, "session busy")).code)
        assertEquals(AppErrorCode.PROJECT_FOLDER_MISSING, workspaceMoveError(GatewayRpcException(4017, "working directory does not exist: /x")).code)
        assertEquals(AppErrorCode.SESSION_NOT_FOUND, workspaceMoveError(GatewayRpcException(4007, "session not found")).code)
        assertEquals(AppErrorCode.PROJECT_MOVE_FAILED, workspaceMoveError(GatewayRpcException(5007, "move failed")).code)
        assertEquals(AppErrorCode.PROJECT_MOVE_FAILED, workspaceMoveError(IllegalStateException("socket closed")).code)
    }

    @Test fun retryability_and_diagnostics() {
        val busy = workspaceMoveError(GatewayRpcException(4009, "session busy"))
        assertTrue(busy.retryable)
        assertFalse(workspaceMoveError(GatewayRpcException(4007, "gone")).retryable)
        assertEquals("workspace_move", busy.stage)
        assertTrue(busy.sanitizedDiagnostic().contains("HR-SESS-004"))
        assertTrue(busy.sanitizedDiagnostic().contains("session busy"))
    }

    @Test fun copies_carry_the_code_in_both_languages() {
        val cases = mapOf(
            AppErrorCode.PROJECT_FOLDER_MISSING to "HR-SESS-003",
            AppErrorCode.SESSION_BUSY to "HR-SESS-004",
            AppErrorCode.PROJECT_MOVE_FAILED to "HR-SESS-005",
            AppErrorCode.PROJECT_FELL_BACK_TO_DEFAULT to "HR-SESS-006",
            AppErrorCode.SESSION_NOT_FOUND to "HR-SESS-001",
        )
        for ((code, hr) in cases) {
            val error = com.hermes.client.data.error.AppError(code, retryable = true)
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)
            assertTrue("$hr zh", zh.contains(hr))
            assertTrue("$hr en", en.contains(hr))
            assertTrue("$hr zh copy must be Chinese", zh.any { it.code > 0x4E00 })
            assertTrue("$hr copies differ", zh != en)
        }
    }
}
