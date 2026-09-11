package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.sessions.BotOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerLabelTest {
    private val dmOnDingTalk = BotOrigin("dingtalk", displayName = null, chatType = "dm")
    private val groupOnSlack = BotOrigin("slack", displayName = "#engineering", chatType = "group")

    @Test fun an_ordinary_conversation_still_says_you() {
        assertEquals("你", userSpeakerLabel(null, AppLanguage.ZH))
        assertEquals("You", userSpeakerLabel(null, AppLanguage.EN))
    }

    @Test fun a_channel_conversation_names_the_peer_instead() {
        assertEquals("私聊 · 在钉钉", userSpeakerLabel(dmOnDingTalk, AppLanguage.ZH))
        assertEquals("#engineering · on Slack", userSpeakerLabel(groupOnSlack, AppLanguage.EN))
    }

    /** An ordinary chat never labels its own bubbles — that would be new noise on every screen. */
    @Test fun an_ordinary_turn_carries_no_label_at_all() {
        assertNull(userTurnLabel("u-1", setOf("u-1"), null, AppLanguage.ZH))
        assertNull(userTurnLabel("srv-9", emptySet(), null, AppLanguage.ZH))
    }

    /**
     * The reason this function exists: once the composer works, the right-hand column carries both
     * the peer's messages and the reader's own, and one blanket label would misattribute half of
     * them.
     */
    @Test fun a_turn_typed_here_is_yours_and_the_rest_are_theirs() {
        assertEquals("你", userTurnLabel("u-42", setOf("u-42"), dmOnDingTalk, AppLanguage.ZH))
        assertEquals("私聊 · 在钉钉", userTurnLabel("srv-7", setOf("u-42"), dmOnDingTalk, AppLanguage.ZH))
    }

    @Test fun the_prompts_list_is_not_called_yours_when_someone_else_asked() {
        assertEquals("我的提问", promptListTitle(null, AppLanguage.ZH))
        assertEquals("Your prompts", promptListTitle(null, AppLanguage.EN))
        assertEquals("对方的提问", promptListTitle(dmOnDingTalk, AppLanguage.ZH))
        assertEquals("Their prompts", promptListTitle(groupOnSlack, AppLanguage.EN))
    }
}
