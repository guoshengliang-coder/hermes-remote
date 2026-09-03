package com.hermes.client.data.error

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    @Test fun identityCodesHaveBilingualCopyAndKeepTheirCode() {
        val photo = AppError(AppErrorCode.AVATAR_PHOTO_FAILED, retryable = true)
        val save = AppError(AppErrorCode.PROFILE_IDENTITY_SAVE_FAILED, retryable = true)
        assertTrue(photo.localizedMessage(AppLanguage.ZH).let { it.contains("照片") && it.endsWith("(HR-MEDIA-002)") })
        assertTrue(photo.localizedMessage(AppLanguage.EN).let { it.contains("photo") && it.endsWith("(HR-MEDIA-002)") })
        assertTrue(save.localizedMessage(AppLanguage.ZH).let { it.contains("身份设置") && it.endsWith("(HR-STORE-001)") })
        assertTrue(save.localizedMessage(AppLanguage.EN).let { it.contains("profile settings") && it.endsWith("(HR-STORE-001)") })
        assertTrue(photo.retryable && save.retryable)
        assertTrue(photo.sanitizedDiagnostic().contains("HR-MEDIA-002"))
    }

    // HR-SEARCH-001: gateway message search failed. Retryable, bilingual, code kept.
    @Test fun searchFailureHasBilingualCopyAndIsRetryable() {
        val error = AppError(AppErrorCode.SEARCH_FAILED, retryable = true, technicalCause = "HTTP 502 token=abc")
        assertTrue(error.localizedMessage(AppLanguage.ZH).let { it.contains("消息搜索失败") && it.endsWith("(HR-SEARCH-001)") })
        assertTrue(error.localizedMessage(AppLanguage.EN).let { it.contains("Message search failed") && it.endsWith("(HR-SEARCH-001)") })
        assertTrue(error.retryable)
        assertEquals("SEARCH-001", AppErrorCode.SEARCH_FAILED.compact)
        val diagnostic = error.sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-SEARCH-001"))
        assertFalse(diagnostic.contains("abc"))
    }

    @Test fun diagnosticsKeepTheCodeAndRedactSecrets() {
        val diagnostic = AppError(
            code = AppErrorCode.CONNECTION_FAILED,
            retryable = true,
            stage = "gateway_ready",
            technicalCause = "token=secret cookie:abc ticket=xyz url?signature=hidden&safe=1",
        ).sanitizedDiagnostic()

        assertTrue(diagnostic.contains("HR-CONN-002"))
        assertTrue(diagnostic.contains("gateway_ready"))
        assertTrue(diagnostic.contains("<redacted>"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("abc"))
        assertFalse(diagnostic.contains("hidden"))
    }
}
