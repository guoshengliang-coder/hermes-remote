package com.hermes.client.data.error

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.localization.localizedSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    @Test fun localizedSummaryDoesNotRepeatTheStableCode() {
        val error = AppError(AppErrorCode.CRON_ACTION_FAILED, retryable = true)
        assertFalse(error.localizedSummary(AppLanguage.ZH).contains("HR-CRON-003"))
        assertEquals(1, Regex("HR-CRON-003").findAll(error.localizedMessage(AppLanguage.ZH)).count())
    }

    @Test fun gallery_failures_have_bilingual_codes_and_retryability() {
        val cases = listOf(
            AppError(AppErrorCode.GALLERY_READ_FAILED, retryable = true),
            AppError(AppErrorCode.GALLERY_PERMISSION_REQUIRED, retryable = true),
        )
        cases.forEach { error ->
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)
            assertTrue(zh.contains(error.code.value))
            assertTrue(en.contains(error.code.value))
            assertTrue(zh != en)
            assertTrue(error.retryable)
            assertFalse(error.sanitizedDiagnostic().contains("/Users/"))
        }
    }
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

    // HR-NOTIF-002: FCM registration failed (HG-94). Retryable, bilingual, and the token never
    // reaches diagnostics even if a cause quoted it.
    @Test fun pushRegistrationFailureHasBilingualCopyIsRetryableAndHidesTheToken() {
        val error = AppError(
            AppErrorCode.PUSH_REGISTRATION_FAILED,
            retryable = true,
            technicalCause = "stage=register token=fcm-secret-token HTTP 500",
            stage = "push_registration",
        )
        assertEquals("HR-NOTIF-002", error.code.value)
        assertEquals(AppErrorCode.PUSH_REGISTRATION_FAILED, AppErrorCode.fromValue("HR-NOTIF-002"))
        assertEquals("实时推送注册失败，暂用定时同步，请重试。", error.localizedSummary(AppLanguage.ZH))
        assertEquals(
            "Real-time push registration failed; using periodic sync for now. Retry.",
            error.localizedSummary(AppLanguage.EN),
        )
        assertTrue(error.localizedMessage(AppLanguage.ZH).endsWith("(HR-NOTIF-002)"))
        assertTrue(error.localizedMessage(AppLanguage.EN).endsWith("(HR-NOTIF-002)"))
        assertTrue(error.retryable)
        val diagnostic = error.sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-NOTIF-002"))
        assertFalse(diagnostic.contains("fcm-secret-token"))
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

    /**
     * HR-CONN-003 has been registered in docs/ERROR_HANDLING.md the whole time and had no producer
     * in the client. HG-42 is what that cost: every RPC blocked on the readiness gate surfaced as
     * a generic "message send failed", pointing the user at their message when the thing that had
     * failed was the connection.
     */
    @Test fun aHandshakeTimeoutHasItsOwnBilingualCopyAndIsRetryable() {
        val error = AppError(
            AppErrorCode.HANDSHAKE_TIMEOUT,
            retryable = true,
            stage = "prompt_submit",
            technicalCause = "gateway readiness timeout token=xyz",
        )
        assertEquals("HR-CONN-003", AppErrorCode.HANDSHAKE_TIMEOUT.value)
        assertTrue(error.localizedMessage(AppLanguage.ZH).let { it.contains("握手超时") && it.endsWith("(HR-CONN-003)") })
        assertTrue(
            error.localizedMessage(AppLanguage.EN)
                .let { it.contains("handshake timed out") && it.endsWith("(HR-CONN-003)") },
        )
        assertTrue(error.retryable)
        val diagnostic = error.sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-CONN-003"))
        assertFalse(diagnostic.contains("xyz"))
    }

    /**
     * HR-COMPAT-001..003: the Connector's contract check against the Mac's own Hermes. Bilingual,
     * each with its own copy, and never retryable — only updating Hermes or the app changes them.
     */
    @Test fun hermesContractCodesAreBilingualDistinctAndNotRetryable() {
        val cases = mapOf(
            AppErrorCode.HERMES_INCOMPATIBLE to ("HR-COMPAT-001" to "不兼容"),
            AppErrorCode.HERMES_FEATURES_MISSING to ("HR-COMPAT-002" to "缺少部分接口"),
            AppErrorCode.HERMES_BELOW_MINIMUM to ("HR-COMPAT-003" to "最低版本"),
        )
        val english = mutableSetOf<String>()
        cases.forEach { (code, expected) ->
            val (value, zhFragment) = expected
            assertEquals(value, code.value)
            val error = AppError(code, retryable = false, technicalCause = "missing=GET /api/x token=abc")
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)
            assertTrue(zh, zh.contains(zhFragment) && zh.endsWith("($value)"))
            assertTrue(en, en.contains("Hermes") && en.endsWith("($value)"))
            english += en
            assertEquals(code, AppErrorCode.fromValue(value))
            assertFalse(error.sanitizedDiagnostic().contains("abc"))
        }
        assertEquals(3, english.size)
        assertEquals("COMPAT-001", AppErrorCode.HERMES_INCOMPATIBLE.compact)
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
