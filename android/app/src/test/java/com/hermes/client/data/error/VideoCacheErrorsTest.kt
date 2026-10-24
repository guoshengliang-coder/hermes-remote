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
}
