package com.hermes.client.data.error

import com.hermes.client.data.network.HermesApiException
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * HG-72: three failures that ask for three different things used to say the same sentence.
 *
 * The case that prompted this: on 2026-09-19 the Mac's Hermes answered 5xx for every affected
 * transcript, the phone said `HR-RPC-001`, and the relay's own log read `outcome=streamed` because
 * it had forwarded the 500 faithfully. Nothing on screen pointed at the Mac, and the diagnosis
 * started in the wrong place for two hours.
 */
class HistoryFailuresTest {

    @Test fun an_upstream_5xx_points_at_the_mac_and_withholds_the_retry() {
        val error = historyFailure(HermesApiException(500, "Internal Server Error"))

        assertEquals(AppErrorCode.HISTORY_UPSTREAM_FAILED, error.code)
        assertFalse("retrying sends the same request into the same fault", error.retryable)
        // The number is the part that would have named the cause, so it must survive into the
        // copyable diagnostic rather than only into the log.
        assertTrue(error.sanitizedDiagnostic().contains("500"))
    }

    @Test fun every_5xx_is_treated_the_same_way() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals(
                "status $status",
                AppErrorCode.HISTORY_UPSTREAM_FAILED,
                historyFailure(HermesApiException(status, "upstream")).code,
            )
        }
    }

    @Test fun a_dropped_connection_stays_retryable() {
        val error = historyFailure(IOException("unexpected end of stream"))

        assertEquals(AppErrorCode.CONNECTION_INTERRUPTED, error.code)
        assertTrue("this is the one where retrying genuinely helps", error.retryable)
    }

    @Test fun an_unreadable_response_asks_for_an_update_rather_than_a_retry() {
        // The 2026-09-18 content-block arrays were exactly this shape: the bytes arrived and the
        // parser could not read them, so every retry produced the same failure.
        val error = historyFailure(SerializationException("Unexpected JSON token at offset 13298"))

        assertEquals(AppErrorCode.HISTORY_UNREADABLE, error.code)
        assertFalse("the same bytes parse the same way next time", error.retryable)
    }

    @Test fun an_unclassified_failure_still_names_its_type() {
        // The bucket remains, but the old one carried nothing at all — which is what made the
        // 2026-09-19 diagnosis start in the wrong place.
        val error = historyFailure(IllegalStateException("something new"))

        assertEquals(AppErrorCode.RPC_FAILED, error.code)
        assertTrue(error.sanitizedDiagnostic().contains("IllegalStateException"))
    }

    @Test fun a_4xx_that_is_not_handled_elsewhere_keeps_the_transport_code() {
        // We do not know what it means, and saying so with the number is more honest than
        // inventing a category for it.
        val error = historyFailure(HermesApiException(418, "teapot"))

        assertEquals(AppErrorCode.RPC_FAILED, error.code)
        assertTrue(error.sanitizedDiagnostic().contains("418"))
    }

    @Test fun the_new_codes_are_registered_bilingual_and_distinct() {
        for (code in listOf(AppErrorCode.HISTORY_UPSTREAM_FAILED, AppErrorCode.HISTORY_UNREADABLE, AppErrorCode.HISTORY_PREVIEW_FAILED)) {
            val error = AppError(code, retryable = false)
            val zh = error.localizedMessage(AppLanguage.ZH)
            val en = error.localizedMessage(AppLanguage.EN)

            assertTrue("$code missing its code", zh.contains(code.value) && en.contains(code.value))
            assertTrue("$code needs Chinese copy", zh.any { it.code > 0x4E00 })
            assertNotEquals("$code must not fall back to English", zh, en)
        }
        assertEquals("SYNC-003", AppErrorCode.HISTORY_UPSTREAM_FAILED.compact)
        assertEquals("SYNC-004", AppErrorCode.HISTORY_UNREADABLE.compact)
        val preview = AppError(AppErrorCode.HISTORY_PREVIEW_FAILED, retryable = true,
            technicalCause = "Authorization: Bearer secret", stage = "history_full_row")
        assertTrue(preview.retryable)
        assertEquals("SYNC-005", preview.code.compact)
        assertFalse(preview.sanitizedDiagnostic().contains("secret"))
    }

    @Test fun the_three_causes_do_not_share_a_sentence() {
        // The whole point: if two of these read the same, the person cannot tell them apart.
        val messages = listOf(
            historyFailure(HermesApiException(500, "x")),
            historyFailure(IOException("x")),
            historyFailure(SerializationException("x")),
        ).map { it.localizedMessage(AppLanguage.ZH) }

        assertEquals("three causes, three sentences", messages.size, messages.toSet().size)
    }
}
