package com.hermes.client.data.repository

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.SessionArchiveMode
import com.hermes.client.data.network.sessionArchiveMode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedSummary
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionArchiveTest {
    @Test fun shared_ready_contract() {
        val cases = Json.parseToJsonElement(File("../../docs/hermes-archive-cases.json").readText()).jsonObject["readyCases"]!!.jsonArray
        for (case in cases) {
            val row = case.jsonObject
            assertEquals(row["name"].toString(), row["mode"]!!.jsonPrimitive.content.uppercase(), sessionArchiveMode(row["payload"]).name)
        }
    }
    private fun ack(id: String = "s", archived: Boolean = true) = buildJsonObject { put("session_key", id); put("archived", archived) }
    @Test fun legacy_rpc_and_authority_rest_are_separate() = runTest {
        for (mode in listOf(SessionArchiveMode.LEGACY, SessionArchiveMode.AUTHORITY)) {
            var rpc = 0; var rest = 0
            archiveWithBackend(mode, "s", true, { rpc++; ack() }, { rest++ }, { error("unexpected read") })
            assertEquals(if (mode == SessionArchiveMode.LEGACY) 1 else 0, rpc)
            assertEquals(if (mode == SessionArchiveMode.AUTHORITY) 1 else 0, rest)
        }
    }
    @Test fun only_missing_method_falls_back() = runTest {
        var rest = 0
        archiveWithBackend(SessionArchiveMode.LEGACY, "s", true,
            { throw GatewayRpcException(-32601, "unsupported") }, { rest++ }, { error("unexpected read") })
        assertEquals(1, rest)
        for (code in listOf(4001, 4009, 4064, 4403, 4090)) {
            try {
                archiveWithBackend(SessionArchiveMode.LEGACY, "s", true,
                    { throw GatewayRpcException(code, "refused") }, { error("unsafe fallback") }, { error("unexpected read") })
                fail("must refuse")
            } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.SESSION_ARCHIVE_REFUSED, e.error.code) }
        }
    }
    @Test fun authority_conflict_does_not_use_rpc() = runTest {
        try {
            archiveWithBackend(SessionArchiveMode.AUTHORITY, "s", true, { error("unsafe RPC") },
                { throw HermesApiException(409, "revision_conflict") }, { error("unexpected read") })
            fail("must refuse")
        } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.SESSION_ARCHIVE_REFUSED, e.error.code) }
    }
    @Test fun lost_ack_reads_but_never_replays() = runTest {
        for (archived in listOf(true, false)) {
            var writes = 0
            archiveWithBackend(SessionArchiveMode.LEGACY, "s", archived,
                { writes++; throw GatewayRpcException(0, "lost reply") }, { error("unsafe fallback") }, { archived })
            assertEquals(1, writes)
        }
        for (observed in listOf(null, false)) {
            try {
                archiveWithBackend(SessionArchiveMode.LEGACY, "s", true,
                    { throw GatewayRpcException(0, "lost reply") }, { error("unsafe fallback") }, { observed })
                fail("cannot confirm")
            } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED, e.error.code) }
        }
    }
    @Test fun internal_or_unknown_rpc_errors_reconcile_without_replay() = runTest {
        for (code in listOf(5007, -32603, -32000, 7001)) {
            var reads = 0
            archiveWithBackend(SessionArchiveMode.LEGACY, "s", true,
                { throw GatewayRpcException(code, "storage failed") }, { error("unsafe fallback") }, { reads++; true })
            assertEquals(1, reads)
            try {
                archiveWithBackend(SessionArchiveMode.LEGACY, "s", true,
                    { throw GatewayRpcException(code, "storage failed") }, { error("unsafe fallback") }, { false })
                fail("must remain unknown")
            } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED, e.error.code) }
        }
    }
    @Test fun wrong_target_and_unknown_mode_do_not_claim_success() = runTest {
        try {
            archiveWithBackend(SessionArchiveMode.LEGACY, "s", true, { ack("other") }, {}, { null })
            fail("wrong target")
        } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED, e.error.code) }
        try {
            archiveWithBackend(SessionArchiveMode.UNKNOWN, "s", true, { error("unsafe RPC") }, { error("unsafe REST") }, { null })
            fail("unknown mode")
        } catch (e: SessionArchiveFailure) { assertEquals(AppErrorCode.ARCHIVE_INCOMPATIBLE, e.error.code) }
    }
    @Test fun error_contract_and_redaction() {
        for (code in listOf(AppErrorCode.ARCHIVE_INCOMPATIBLE, AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED, AppErrorCode.SESSION_ARCHIVE_REFUSED)) {
            val error = AppError(code, false, "Authorization: Bearer private-secret cookie=private-cookie", "session_archive")
            assertNotEquals(error.localizedSummary(AppLanguage.ZH), error.localizedSummary(AppLanguage.EN))
            assertFalse(error.retryable)
            assertFalse(error.sanitizedDiagnostic().contains("private-secret"))
            assertFalse(error.sanitizedDiagnostic().contains("private-cookie"))
            assertEquals(code, AppErrorCode.fromValue(code.value))
        }
    }
}
