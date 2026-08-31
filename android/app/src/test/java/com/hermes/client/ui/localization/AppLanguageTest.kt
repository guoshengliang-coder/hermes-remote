package com.hermes.client.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode

class AppLanguageTest {
    @Test fun chinese_and_english_select_the_expected_copy() {
        assertEquals("会话", localized(AppLanguage.ZH, "会话", "Chats"))
        assertEquals("Chats", localized(AppLanguage.EN, "会话", "Chats"))
    }

    @Test fun localizedText_crosses_nonCompose_boundaries_withoutChoosingEarly() {
        val text = localizedText("已保存", "Saved")
        assertEquals("已保存", text.resolve(AppLanguage.ZH))
        assertEquals("Saved", text.resolve(AppLanguage.EN))
    }

    @Test fun appErrors_keepOneCodeAcrossBothLanguages() {
        val error = AppError(AppErrorCode.CONFIG_READ_FAILED, retryable = true)
        assertTrue(error.localizedMessage(AppLanguage.ZH).contains("HR-CONFIG-001"))
        assertTrue(error.localizedMessage(AppLanguage.EN).contains("HR-CONFIG-001"))
    }
}
