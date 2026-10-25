package com.hermes.client.data.error

import com.hermes.client.ui.localization.*
import java.io.IOException
import org.junit.Test
import org.junit.Assert.*

class VideoCacheErrorsTest {
    @Test fun management_failure_has_bilingual_recovery_and_no_private_diagnostics() {
        val failure = videoCacheError(IOException("token=private /Users/private/movie.mp4"))
        assertEquals("HR-CACHE-001", failure.code.value); assertTrue(failure.retryable)
        assertTrue(failure.localizedMessage(AppLanguage.ZH).contains("重试"))
        assertTrue(failure.localizedMessage(AppLanguage.EN).contains("Retry"))
        assertFalse(failure.sanitizedDiagnostic().contains("private"))
        assertEquals(AppErrorCode.VIDEO_CACHE_FAILED, AppErrorCode.fromValue("HR-CACHE-001"))
    }
    @Test fun storage_state_explains_automatic_recovery_without_fake_retry() {
        val failure = videoCacheLowStorage()
        assertEquals("HR-CACHE-002", failure.code.value); assertFalse(failure.retryable)
        assertTrue(failure.localizedMessage(AppLanguage.ZH).contains("自动恢复"))
        assertTrue(failure.localizedMessage(AppLanguage.EN).contains("resumes"))
        assertEquals(AppErrorCode.VIDEO_CACHE_LOW_STORAGE, AppErrorCode.fromValue(failure.code.value))
    }
    @Test fun new_speed_and_capacity_statuses_are_bilingual_nonretryable_and_redacted() {
        for ((code, value) in listOf(AppErrorCode.VIDEO_SPEED_UNSUPPORTED to "HR-FILE-018",
            AppErrorCode.VIDEO_CACHE_CAPACITY to "HR-CACHE-003")) {
            val error = AppError(code, retryable = false, technicalCause = "token=private")
            assertEquals(value, code.value); assertEquals(code, AppErrorCode.fromValue(value))
            assertFalse(error.retryable)
            for (language in AppLanguage.entries) assertTrue(error.localizedMessage(language).contains(value))
            assertNotEquals(error.localizedSummary(AppLanguage.ZH), error.localizedSummary(AppLanguage.EN))
            assertFalse(error.sanitizedDiagnostic().contains("private"))
        }
    }

}
