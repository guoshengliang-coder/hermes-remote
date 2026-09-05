package com.hermes.client.data.error

import com.hermes.client.data.network.HermesApiException
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression: every artifact download failure used to surface as `HR-FILE-001`, a code registered
 * for an OUTGOING attachment the user picked. A permission problem, a size problem and a network
 * blip were indistinguishable, and the reported direction was backwards.
 */
class ArtifactErrorsTest {

    @Test fun forbidden_path_is_not_retryable_and_keeps_its_own_code() {
        val error = artifactDownloadError(HermesApiException(403, "forbidden"))
        assertEquals(AppErrorCode.ARTIFACT_FORBIDDEN, error.code)
        assertEquals("HR-FILE-003", error.code.value)
        assertFalse("retrying a path outside FILES_ROOT can never succeed", error.retryable)
    }

    @Test fun oversized_artifact_is_not_retryable() {
        val error = artifactDownloadError(HermesApiException(413, "file_too_large"))
        assertEquals(AppErrorCode.ARTIFACT_TOO_LARGE, error.code)
        assertFalse(error.retryable)
    }

    @Test fun missing_or_non_regular_file_maps_to_one_code() {
        assertEquals(AppErrorCode.ARTIFACT_MISSING, artifactDownloadError(HermesApiException(404, "not_found")).code)
        assertEquals(AppErrorCode.ARTIFACT_MISSING, artifactDownloadError(HermesApiException(400, "invalid_file")).code)
    }

    @Test fun transport_failure_is_the_only_retryable_case() {
        val error = artifactDownloadError(IOException("stream closed"))
        assertEquals(AppErrorCode.ARTIFACT_DOWNLOAD_FAILED, error.code)
        assertTrue(error.retryable)
    }

    @Test fun never_reuses_the_outgoing_attachment_code() {
        val statuses = listOf(400, 403, 404, 413, 500, 502)
        statuses.forEach { status ->
            val code = artifactDownloadError(HermesApiException(status, "x")).code
            assertTrue(
                "HTTP $status must not report as the outgoing-attachment code",
                code != AppErrorCode.FILE_READ_FAILED,
            )
        }
    }

    @Test fun both_languages_have_copy_and_carry_the_code() {
        val codes = listOf(
            AppErrorCode.ARTIFACT_FORBIDDEN,
            AppErrorCode.ARTIFACT_TOO_LARGE,
            AppErrorCode.ARTIFACT_MISSING,
            AppErrorCode.ARTIFACT_DOWNLOAD_FAILED,
            AppErrorCode.ATTACHMENT_NO_VIEWER,
        )
        codes.forEach { code ->
            listOf(AppLanguage.ZH, AppLanguage.EN).forEach { language ->
                val text = AppError(code, retryable = false).localizedMessage(language)
                assertTrue("$code/$language must have localized copy", text.length > code.value.length + 4)
                assertTrue("$code/$language must name the code", text.contains(code.value))
            }
        }
    }

    @Test fun diagnostics_keep_the_cause_but_redact_credentials() {
        val error = artifactDownloadError(
            HermesApiException(403, "forbidden token=abcdef123 for /api/files"),
            stage = "artifact_open",
        )
        val diagnostic = error.sanitizedDiagnostic()
        assertTrue(diagnostic.contains("HR-FILE-003"))
        assertTrue("the stage must survive for diagnosis", diagnostic.contains("artifact_open"))
        assertTrue("HTTP status is the key fact", diagnostic.contains("403"))
        assertFalse("a credential must never reach copyable diagnostics", diagnostic.contains("abcdef123"))
    }
}
