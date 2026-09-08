package com.hermes.client.ui.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingCategoryTest {
    @Test fun the_chat_platforms_are_chat() {
        listOf("dingtalk", "slack", "telegram", "feishu", "wecom", "whatsapp", "discord", "line")
            .forEach { assertEquals(it, MessagingCategory.CHAT, messagingCategory(it)) }
    }

    @Test fun the_non_conversational_ones_are_named_for_what_they_are() {
        assertEquals(MessagingCategory.MAIL, messagingCategory("email"))
        assertEquals(MessagingCategory.SMS, messagingCategory("sms"))
        assertEquals(MessagingCategory.PUSH, messagingCategory("ntfy"))
        assertEquals(MessagingCategory.API, messagingCategory("webhook"))
        assertEquals(MessagingCategory.API, messagingCategory("api_server"))
    }

    /** A platform added upstream after this release still gets a sensible glyph. */
    @Test fun an_unknown_platform_falls_back_to_chat() {
        assertEquals(MessagingCategory.CHAT, messagingCategory("some_new_im"))
        assertEquals(MessagingCategory.CHAT, messagingCategory("  DingTalk "))
    }
}
