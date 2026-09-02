package com.hermes.client.data.error

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
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
