package com.hermes.client.ui.sessions

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
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

    @Test fun the_banner_reads_channel_then_who_then_read_only() {
        assertEquals("来自钉钉 · 私聊 · 只读", botOriginLabel(null, "dm", "dingtalk", AppLanguage.ZH))
        assertEquals(
            "来自钉钉 · 梁国盛,尹奕（Michael Y） · 只读",
            botOriginLabel("梁国盛,尹奕（Michael Y）", "group", "dingtalk", AppLanguage.ZH),
        )
        assertEquals("From 钉钉 · read-only", botOriginLabel(null, null, "dingtalk", AppLanguage.EN))
    }
}
