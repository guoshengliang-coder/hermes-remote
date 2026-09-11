package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelChipLabelTest {
    @Test fun a_local_session_with_no_override_follows_the_default() {
        assertEquals("默认模型", modelChipLabel(null, "", isBot = false, language = AppLanguage.ZH))
        assertEquals("Default model", modelChipLabel("", "", isBot = false, language = AppLanguage.EN))
    }

    /**
     * The lie this replaces: a channel conversation's model was never in the cache, so the chip
     * fell through to "默认模型" — and the view model went further and wrote the profile's default
     * into the session's own state, naming a model that had never touched that conversation.
     */
    @Test fun a_channel_session_with_no_known_model_says_so() {
        assertEquals("模型未知", modelChipLabel(null, "", isBot = true, language = AppLanguage.ZH))
        assertEquals("Model unknown", modelChipLabel(null, "", isBot = true, language = AppLanguage.EN))
    }

    @Test fun a_known_model_reads_the_same_either_way() {
        val zh = modelChipLabel("anthropic/claude-sonnet-4", " · 深度", isBot = true, language = AppLanguage.ZH)
        assertEquals("claude-sonnet-4 · 深度", zh)
        assertEquals(
            zh,
            modelChipLabel("anthropic/claude-sonnet-4", " · 深度", isBot = false, language = AppLanguage.ZH),
        )
    }
}
