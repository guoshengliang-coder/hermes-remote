package com.hermes.client.ui.startup

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure copy rules for the startup gate (DESIGN.md §5.11): merged phases, recovery wording, version line. */
class StartupCopyTest {
    @Test fun preConnectionPhasesShareOneConnectingLine() {
        val merged = listOf(
            StartupPhase.CONFIGURATION,
            StartupPhase.NETWORK,
            StartupPhase.AUTHENTICATION,
            StartupPhase.CONNECTION,
        )
        for (phase in merged) {
            assertEquals("正在连接", startupStatusText(phase, StartupReason.COLD_START, AppLanguage.ZH))
            assertEquals("Connecting", startupStatusText(phase, StartupReason.INITIAL_SETUP, AppLanguage.EN))
        }
    }

    @Test fun recoveryUsesRestoringWording() {
        assertEquals(
            "正在恢复连接",
            startupStatusText(StartupPhase.CONNECTION, StartupReason.CONNECTION_RECOVERY, AppLanguage.ZH),
        )
        assertEquals(
            "Restoring the current screen",
            startupStatusText(StartupPhase.INITIAL_DATA, StartupReason.CONNECTION_RECOVERY, AppLanguage.EN),
        )
    }

    @Test fun retryAndDiagnosticStagesUseBilingualCopy() {
        assertEquals(
            "正在重新连接（第 1/2 次）",
            startupStatusText(StartupPhase.AUTHENTICATION, StartupReason.COLD_START, AppLanguage.ZH, 1),
        )
        assertEquals(
            "Reconnecting (attempt 2/2)",
            startupStatusText(StartupPhase.AUTHENTICATION, StartupReason.COLD_START, AppLanguage.EN, 2),
        )
        assertEquals(
            "正在检查连接问题",
            startupStatusText(StartupPhase.DIAGNOSTICS, StartupReason.COLD_START, AppLanguage.ZH),
        )
        assertEquals(
            "Checking the connection",
            startupStatusText(StartupPhase.DIAGNOSTICS, StartupReason.COLD_START, AppLanguage.EN),
        )
    }

    @Test fun laterPhasesKeepTheirOwnLines() {
        assertEquals("正在准备会话", startupStatusText(StartupPhase.INITIAL_DATA, StartupReason.COLD_START, AppLanguage.ZH))
        assertEquals("Preparing conversations", startupStatusText(StartupPhase.INITIAL_DATA, StartupReason.COLD_START, AppLanguage.EN))
        assertEquals("连接就绪", startupStatusText(StartupPhase.READY, StartupReason.COLD_START, AppLanguage.ZH))
        assertEquals("Connection ready", startupStatusText(StartupPhase.READY, StartupReason.CONNECTION_RECOVERY, AppLanguage.EN))
    }

    @Test fun versionLabelUppercasesTheChannel() {
        assertEquals("0.1.81 · DEBUG", startupVersionLabel("0.1.81", "debug"))
        assertEquals("1.0.0 · RELEASE", startupVersionLabel("1.0.0", "release"))
    }
}
