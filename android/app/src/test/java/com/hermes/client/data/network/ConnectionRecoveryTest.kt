package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedSummary
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun accountRepairGatesAlwaysWinOverManualAndAutomaticRecovery() {
        val sessions = mockk<AccountSessionManager>()
        val client = mockk<HermesGatewayClient>()
        for (mode in AccountTransportMode.entries.filter { it != AccountTransportMode.ACCOUNT }) {
            every { sessions.transportMode() } returns mode
            assertFalse(recoverAccountConnection(sessions, client, ConnectionRecoveryTrigger.MANUAL))
            assertFalse(recoverAccountConnection(sessions, client, ConnectionRecoveryTrigger.HEALTH_RECOVERED))
        }
        verify(exactly = 0) { client.recoverIfNeeded(any()) }
        every { sessions.transportMode() } returns AccountTransportMode.ACCOUNT
        every { client.recoverIfNeeded(any()) } returns true
        assertTrue(recoverAccountConnection(sessions, client, ConnectionRecoveryTrigger.HEALTH_RECOVERED))
        verify(exactly = 1) { client.recoverIfNeeded(ConnectionRecoveryTrigger.HEALTH_RECOVERED) }
    }

    @Test fun connectionCodesHaveRegisteredBilingualCopyAndCorrectRetryability() {
        val cases = mapOf("HR-CONN-005" to true, "HR-CONN-006" to true,
            "HR-AUTH-003" to false, "HR-AUTH-004" to false, "HR-AUTH-005" to false,
            "HR-AUTH-007" to true, "HR-ACCOUNT-001" to false, "HR-ACCOUNT-002" to true,
            "HR-BIND-001" to true, "HR-BIND-009" to false, "HR-BIND-011" to false)
        for ((code, retryable) in cases) {
            val error = checkNotNull(connectionProbeError(code))
            assertEquals(code, error.code.value)
            assertEquals(code, retryable, error.retryable)
            val zh = error.localizedSummary(AppLanguage.ZH)
            val en = error.localizedSummary(AppLanguage.EN)
            assertTrue(zh, zh.any { it.code in 0x4e00..0x9fff })
            assertTrue(en, en.any { it in 'a'..'z' })
            assertNotEquals(zh, en)
            assertNull(error.technicalCause)
        }
        assertNull(connectionProbeError("token=secret"))
        assertNull(connectionProbeError("HR-NEW-999"))
    }

    @Test fun recoveryTraceHasUniqueCyclesAndNeverUsesRemoteContent() {
        DebugLog.detachStore(); DebugLog.setEnabled(true); DebugLog.clear()
        try {
            val manual = ConnectionRecoveryTrace.start(ConnectionRecoveryTrigger.MANUAL)
            val automatic = ConnectionRecoveryTrace.start(ConnectionRecoveryTrigger.AUTOMATIC)
            assertNotEquals(manual, automatic)
            ConnectionRecoveryTrace.finish(manual, connectionProbeError("HR-CONN-005"))
            val lines = DebugLog.entries.value.filter { it.category == "recovery" }.joinToString { it.message }
            assertTrue(lines, lines.contains("trigger=MANUAL"))
            assertTrue(lines, lines.contains("HR-CONN-005"))
            assertFalse(lines, lines.contains("Bearer"))
            assertFalse(lines, lines.contains("http"))
            assertFalse(lines, lines.contains("secret"))
        } finally { DebugLog.setEnabled(false); DebugLog.clear() }
    }
}
