package com.hermes.client.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode

class AppLanguageTest {
    @Test fun chinese_and_english_select_the_expected_copy() {
        assertEquals("会话", localized(AppLanguage.ZH, "会话", "Chats"))
        assertEquals("Chats", localized(AppLanguage.EN, "会话", "Chats"))
    }

    @Test fun localizedText_crosses_nonCompose_boundaries_withoutChoosingEarly() {
        val text = localizedText("已保存", "Saved")
        assertEquals("已保存", text.resolve(AppLanguage.ZH))
        assertEquals("Saved", text.resolve(AppLanguage.EN))
    }

    @Test fun appErrors_keepOneCodeAcrossBothLanguages() {
        val error = AppError(AppErrorCode.CONFIG_READ_FAILED, retryable = true)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("HR-CONFIG-001"))
        assertTrue(error.localizedMessage(AppLanguage.EN).contains("HR-CONFIG-001"))
    }

    @Test fun modelErrors_carryRegisteredCodesAndBothLanguages() {
        val cases = mapOf(
            AppErrorCode.MODEL_LIST_FAILED to "HR-RPC-003",
            AppErrorCode.MODEL_SWITCH_FAILED to "HR-RPC-004",
            AppErrorCode.MODEL_DEFAULT_FAILED to "HR-RPC-005",
        )
        for ((code, hr) in cases) {
            val error = AppError(code, retryable = true)
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)
            assertTrue("$hr zh copy must carry the code", zh.contains(hr))
            assertTrue("$hr en copy must carry the code", en.contains(hr))
            assertTrue("$hr zh copy must be Chinese", zh.any { it.code > 0x4E00 })
            assertTrue("$hr copies must differ per language", zh != en)
            assertTrue("model errors are retryable", error.retryable)
        }
    }

    // Each update stage owns a distinct code so a failed check, a failed download, and a failed
    // signature check are never presented as the same problem (docs/ERROR_HANDLING.md).
    @Test fun updateStages_haveDistinctRegisteredCodesAndBilingualCopy() {
        val cases = mapOf(
            AppErrorCode.UPDATE_CHECK_FAILED to "HR-UPDATE-002",
            AppErrorCode.UPDATE_ENQUEUE_FAILED to "HR-UPDATE-003",
            AppErrorCode.UPDATE_DOWNLOAD_FAILED to "HR-UPDATE-004",
            AppErrorCode.UPDATE_VERIFICATION_FAILED to "HR-UPDATE-005",
            AppErrorCode.UPDATE_FILE_MISSING to "HR-UPDATE-006",
            AppErrorCode.UPDATE_INSTALLER_FAILED to "HR-UPDATE-007",
        )
        val summaries = mutableSetOf<String>()
        for ((code, hr) in cases) {
            val error = AppError(code, retryable = true)
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)
            assertEquals(hr, code.value)
            assertTrue("$hr zh copy must carry the code", zh.contains(hr))
            assertTrue("$hr en copy must carry the code", en.contains(hr))
            assertTrue("$hr zh copy must be Chinese", zh.any { it.code > 0x4E00 })
            assertTrue("$hr copies must differ per language", zh != en)
            assertTrue("$hr must not reuse another stage's Chinese summary", summaries.add(zh))
        }
    }

    @Test fun connectorOffline_hasLocalizedRetryableCopy() {
        val error = AppError(AppErrorCode.CONNECTOR_OFFLINE, retryable = true)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("Mac 端当前离线"))
        assertTrue(error.localizedMessage(AppLanguage.EN).contains("Mac is offline"))
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("HR-CONN-005"))
    }
}
