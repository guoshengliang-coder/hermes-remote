package com.hermes.client.ui.sessions

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPeerLabelTest {
    /** The bug: upstream leaves display_name blank on every DM, so the label was a bare 在钉钉. */
    @Test fun a_direct_message_says_so_when_upstream_gives_no_name() {
        assertEquals("私聊 · 在钉钉", botPeerLabel(null, "dm", "dingtalk", AppLanguage.ZH))
        assertEquals("Direct message · on 钉钉", botPeerLabel("", "dm", "dingtalk", AppLanguage.EN))
    }

    @Test fun a_named_group_uses_its_name() {
        assertEquals(
            "梁国盛,尹奕（Michael Y） · 在钉钉",
            botPeerLabel("梁国盛,尹奕（Michael Y）", "group", "dingtalk", AppLanguage.ZH),
        )
    }

    @Test fun an_unnamed_group_still_says_it_is_a_group() {
        assertEquals("群聊 · 在Slack", botPeerLabel(null, "group", "slack", AppLanguage.ZH))
    }

    /** Neither field present: name the channel and claim nothing else. */
    @Test fun an_unknown_chat_names_only_the_channel() {
        assertEquals("在钉钉", botPeerLabel(null, null, "dingtalk", AppLanguage.ZH))
    }

    @Test fun the_subtitle_reads_channel_then_who() {
        assertEquals("来自钉钉 · 私聊", botOriginLabel(null, "dm", "dingtalk", AppLanguage.ZH))
        assertEquals(
            "来自钉钉 · 梁国盛,尹奕（Michael Y）",
            botOriginLabel("梁国盛,尹奕（Michael Y）", "group", "dingtalk", AppLanguage.ZH),
        )
        assertEquals("From 钉钉", botOriginLabel(null, null, "dingtalk", AppLanguage.EN))
    }

    /**
     * The label used to end in "只读". Both halves of that claim were wrong: the conversation can
     * be written to now, and the delivery it warned about never happened in the first place.
     */
    @Test fun the_subtitle_no_longer_claims_the_conversation_is_read_only() {
        assertFalse(botOriginLabel(null, "dm", "dingtalk", AppLanguage.ZH).contains("只读"))
        assertFalse(botOriginLabel(null, "dm", "dingtalk", AppLanguage.EN).contains("read-only"))
    }

    @Test fun the_send_notice_names_the_channel_it_is_about() {
        assertEquals("这条不会发到钉钉", botSendNoticeTitle("dingtalk", AppLanguage.ZH))
        assertEquals("This won't reach Slack", botSendNoticeTitle("slack", AppLanguage.EN))
        assertTrue(botSendNoticeBody("feishu", AppLanguage.ZH).contains("飞书"))
        assertFalse(botSendNoticeBody("feishu", AppLanguage.ZH).contains("钉钉"))
    }

    /** Both facts have to be in the body: it does not go out, and it still shapes the next reply. */
    @Test fun the_send_notice_says_the_message_stays_but_the_context_does_not() {
        val zh = botSendNoticeBody("dingtalk", AppLanguage.ZH)
        assertTrue(zh.contains("只存在 Hermes 里"))
        assertTrue(zh.contains("上下文"))
        val en = botSendNoticeBody("dingtalk", AppLanguage.EN)
        assertTrue(en.contains("never appears"))
        assertTrue(en.contains("context"))
    }
}
