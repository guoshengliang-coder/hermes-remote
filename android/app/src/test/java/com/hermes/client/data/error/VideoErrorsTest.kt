package com.hermes.client.data.error

import androidx.media3.common.PlaybackException
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.ui.localization.*
import java.io.IOException
import org.junit.Test
import org.junit.Assert.*

class VideoErrorsTest {
    @Test fun codec_error_is_localized_nonretryable_and_redacted() {
        val error = videoPlaybackError(PlaybackException("Authorization: Bearer private", null,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals("HR-FILE-016", error.code.value); assertFalse(error.retryable)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("下载"))
        assertTrue(error.localizedMessage(AppLanguage.EN).contains("another player"))
        assertFalse(error.sanitizedDiagnostic().contains("private"))
    }
    @Test fun generic_error_is_retryable_and_auth_or_source_failure_survives_wrapping() {
        val error = videoPlaybackError(IOException("interrupted"))
        assertEquals("HR-FILE-017", error.code.value); assertTrue(error.retryable)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("重试"))
        assertTrue(error.localizedMessage(AppLanguage.EN).contains("Retry"))
        for ((status, code) in listOf(401 to "HR-AUTH-001", 412 to "HR-FILE-011", 403 to "HR-FILE-003")) {
            val mapped = videoPlaybackError(IOException("loader", HermesApiException(status, "token=private /Users/private/person/video.mp4")))
            assertEquals(code, mapped.code.value); assertFalse(mapped.retryable)
            assertFalse(mapped.sanitizedDiagnostic().contains("/Users/private"))
        }
    }
}
