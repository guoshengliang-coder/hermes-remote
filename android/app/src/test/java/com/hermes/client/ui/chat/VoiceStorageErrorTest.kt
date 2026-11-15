package com.hermes.client.ui.chat

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.localization.localizedSummary
import org.junit.Assert.*
import org.junit.Test

class VoiceStorageErrorTest {
    @Test fun localStorageFailureHasBilingualRetryableRedactedContract() {
        val error = AppError(AppErrorCode.VOICE_STORAGE_FAILED, retryable = true,
            technicalCause = "Authorization: Bearer private token=secret", stage = "voice_storage")
        assertEquals("HR-VOICE-006", error.code.value)
        assertEquals(error.code, AppErrorCode.fromValue(error.code.value))
        assertTrue(error.retryable)
        assertTrue(error.localizedSummary(AppLanguage.ZH).contains("存储"))
        assertTrue(error.localizedSummary(AppLanguage.EN).contains("storage"))
        for (language in AppLanguage.entries) assertTrue(error.localizedMessage(language).contains("HR-VOICE-006"))
        assertFalse(error.sanitizedDiagnostic().contains("private"))
        assertFalse(error.sanitizedDiagnostic().contains("secret"))
    }
}
