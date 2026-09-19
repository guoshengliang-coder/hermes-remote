package com.hermes.client.ui.chat

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.DeliveryState
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryStateTest {
    @Test fun optimistic_user_turn_starts_sending_and_history_rows_default_to_sent() {
        val state = ChatUiState().withUserMessage("hi", messageId = "u-1")
        assertEquals(DeliveryState.SENDING, state.messages.single().delivery)
        // Anything not created through the send path — REST history, assistant turns — is SENT.
        assertEquals(DeliveryState.SENT, ChatMessage(id = "h-1", role = Role.USER, text = "x").delivery)
        assertEquals(DeliveryState.SENT, ChatMessage(id = "a-1", role = Role.ASSISTANT, text = "x").delivery)
    }

    @Test fun withDelivery_touches_only_the_addressed_turn() {
        val state = ChatUiState()
            .withUserMessage("one", messageId = "u-1")
            .withUserMessage("two", messageId = "u-2")
            .withDelivery("u-1", DeliveryState.SENT)
        assertEquals(DeliveryState.SENT, state.messages.first { it.id == "u-1" }.delivery)
        assertEquals(DeliveryState.SENDING, state.messages.first { it.id == "u-2" }.delivery)
        val failed = state.withDelivery("u-2", DeliveryState.FAILED)
        assertEquals(DeliveryState.FAILED, failed.messages.first { it.id == "u-2" }.delivery)
    }

    @Test fun withoutMessage_drops_the_failed_turn_for_retry() {
        val state = ChatUiState().withUserMessage("one", messageId = "u-1").withUserMessage("two", messageId = "u-2")
        assertEquals(listOf("u-1"), state.withoutMessage("u-2").messages.map { it.id })
    }

    @Test fun send_failure_code_is_registered_bilingual_and_retryable() {
        val error = AppError(AppErrorCode.MESSAGE_SEND_FAILED, retryable = true, technicalCause = "token=abc socket closed")
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-007") && en.contains("HR-SESS-007"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertTrue(error.retryable)
        assertTrue(error.sanitizedDiagnostic().contains("token=<redacted>"))
    }

    // HG-29: the terminal counterpart. "Conversation is gone" must be a different code with a
    // different retryability, or the bubble cannot tell the user anything true.
    @Test fun undeliverable_code_is_registered_bilingual_and_not_retryable() {
        val error = AppError(AppErrorCode.SESSION_NOT_FOUND, retryable = false, technicalCause = "session not found")
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-001") && en.contains("HR-SESS-001"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertFalse("a conversation that no longer exists cannot be retried into existence", error.retryable)
        assertEquals("SESS-001", AppErrorCode.SESSION_NOT_FOUND.compact)
        assertNotEquals(AppErrorCode.MESSAGE_SEND_FAILED, AppErrorCode.SESSION_NOT_FOUND)
    }

    // HG-30: the third shape. Retryable like SESS-007, but for a reason the user can act on, so it
    // must not reuse SESS-007's copy — "点按重试" alone sends them back into the same refusal.
    @Test fun owned_elsewhere_code_is_registered_bilingual_and_retryable() {
        val error = AppError(
            AppErrorCode.SESSION_OWNED_ELSEWHERE,
            retryable = true,
            technicalCause = "Session s1 already has a live owner (desktop, pid 32991)",
        )
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-013") && en.contains("HR-SESS-013"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertTrue("the other client finishing is what makes this one work", error.retryable)
        assertEquals("SESS-013", AppErrorCode.SESSION_OWNED_ELSEWHERE.compact)
        // Distinct from both neighbours: not the generic failure, not the terminal one.
        assertNotEquals(AppErrorCode.MESSAGE_SEND_FAILED, AppErrorCode.SESSION_OWNED_ELSEWHERE)
        assertNotEquals(
            error.localizedMessage(AppLanguage.ZH),
            AppError(AppErrorCode.MESSAGE_SEND_FAILED, retryable = true).localizedMessage(AppLanguage.ZH),
        )
    }

    // HG-49: the fourth shape. A refused send restored from disk after the app restarted, whose
    // staged attachments did not survive with it — the bytes are never persisted. Replaying it
    // would deliver less than the user meant, so unlike SESS-007 and SESS-013 this one withholds
    // the tap rather than offering a retry that quietly drops the images.
    @Test fun attachments_lost_code_is_registered_bilingual_and_not_retryable() {
        val error = AppError(AppErrorCode.UNSENT_ATTACHMENTS_LOST, retryable = false)
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-015") && en.contains("HR-SESS-015"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertFalse("the attachments are gone; re-sending the text alone is not the same send", error.retryable)
        assertEquals("SESS-015", AppErrorCode.UNSENT_ATTACHMENTS_LOST.compact)
        // It has to read differently from the generic failure, or the user retries into nothing.
        assertNotEquals(
            zh,
            AppError(AppErrorCode.MESSAGE_SEND_FAILED, retryable = true).localizedMessage(AppLanguage.ZH),
        )
    }

    // HG-58: the fifth shape. `pdf.attach` came back 5028 — the Mac's Hermes cannot reach
    // pdftoppm. Upstream calls that "not installed", but on the reported machine poppler had been
    // installed four and a half hours earlier; the managed Hermes is a launchd agent whose PATH is
    // the bare /usr/bin:/bin:/usr/sbin:/sbin, so it simply could not see it. Either way the phone
    // can do nothing about it, so this one withholds the tap like SESS-001 and SESS-015.
    @Test fun pdf_dependency_code_is_registered_bilingual_and_not_retryable() {
        val error = AppError(
            AppErrorCode.PDF_RENDER_DEPENDENCY_MISSING,
            retryable = false,
            technicalCause = "5028 pdftoppm not installed (poppler-utils package required)",
        )
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-016") && en.contains("HR-SESS-016"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertFalse("the fix is on the Mac; every tap repeats the same 5028", error.retryable)
        assertEquals("SESS-016", AppErrorCode.PDF_RENDER_DEPENDENCY_MISSING.compact)
        assertNotEquals(
            zh,
            AppError(AppErrorCode.MESSAGE_SEND_FAILED, retryable = true).localizedMessage(AppLanguage.ZH),
        )
        // The numeric code has to survive into the copyable diagnostic: upstream's prose is the
        // part that can change under us, the number is the part ChatViewModel classifies on.
        assertTrue(error.sanitizedDiagnostic().contains("5028"))
    }

    // HG-65: the sixth shape. The Mac answered and the Connector could not relay the answer
    // (-32001) — what is oversized is the conversation, not the message, because Hermes re-inlines
    // every attachment as base64 on each read. Terminal for a reason the others do not share: the
    // next attempt is not merely as likely to fail, it is the same bytes, and there are more of
    // them every turn.
    @Test fun session_too_large_code_is_registered_bilingual_and_not_retryable() {
        val error = AppError(
            AppErrorCode.SESSION_TOO_LARGE,
            retryable = false,
            technicalCause = "-32001 response is 27577909 bytes, over the 12582912-byte relay frame limit",
        )
        val zh = error.localizedMessage(AppLanguage.ZH)
        val en = error.localizedMessage(AppLanguage.EN)
        assertTrue(zh.contains("HR-SESS-017") && en.contains("HR-SESS-017"))
        assertTrue(zh.any { it.code > 0x4E00 } && zh != en)
        assertFalse("retrying sends the same 26 MiB again", error.retryable)
        assertEquals("SESS-017", AppErrorCode.SESSION_TOO_LARGE.compact)
        assertNotEquals(
            zh,
            AppError(AppErrorCode.MESSAGE_SEND_FAILED, retryable = true).localizedMessage(AppLanguage.ZH),
        )
        assertTrue(error.sanitizedDiagnostic().contains("-32001"))
    }

    // docs/ERROR_HANDLING.md: "retryable = false means the tap is withheld, not merely
    // discouraged". The bubble decides that from the code, so the set it decides from has to hold
    // every terminal send failure — before HG-58 it did not exist at all and SESS-015 printed
    // "点按重试" with only ChatViewModel.retrySend's early return behind it.
    @Test fun terminal_send_codes_are_exactly_the_ones_that_withhold_the_tap() {
        assertEquals(
            setOf(
                AppErrorCode.SESSION_NOT_FOUND,
                AppErrorCode.UNSENT_ATTACHMENTS_LOST,
                AppErrorCode.PDF_RENDER_DEPENDENCY_MISSING,
                AppErrorCode.SESSION_TOO_LARGE,
            ),
            TERMINAL_SEND_ERROR_CODES,
        )
        // The two that clear by themselves stay out of it, or the user loses a tap that works.
        assertFalse(AppErrorCode.MESSAGE_SEND_FAILED in TERMINAL_SEND_ERROR_CODES)
        assertFalse(AppErrorCode.SESSION_OWNED_ELSEWHERE in TERMINAL_SEND_ERROR_CODES)
    }

    @Test fun compact_code_drops_only_the_prefix_and_stays_unique() {
        assertEquals("SESS-007", AppErrorCode.MESSAGE_SEND_FAILED.compact)
        assertEquals("RPC-001", AppErrorCode.RPC_FAILED.compact)
        // Compact forms remain distinct across the whole registry.
        val compacts = AppErrorCode.entries.map { it.compact }
        assertEquals(compacts.size, compacts.toSet().size)
        assertTrue(compacts.none { it.startsWith("HR-") })
    }
}
