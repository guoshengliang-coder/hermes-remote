package com.hermes.client.ui.chat

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.DeliveryState
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import org.junit.Assert.assertEquals
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
}
