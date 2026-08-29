package com.hermes.client.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun chinese_and_english_select_the_expected_copy() {
        assertEquals("会话", localized(AppLanguage.ZH, "会话", "Chats"))
        assertEquals("Chats", localized(AppLanguage.EN, "会话", "Chats"))
    }
}
