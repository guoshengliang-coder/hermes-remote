package com.hermes.client.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCompletionTest {
    @Test fun recordingLimitKeepsDraft_evenWithSendIntentAndLiveConnection() {
        assertTrue(voiceMustKeepDraft(VoiceEvent.Final("定稿", limited = true), editing = false, canSend = true))
    }
    @Test fun ordinaryReleasePreservesSend_editAndUnavailablePreserveDraft() {
        val final = VoiceEvent.Final("定稿")
        assertFalse(voiceMustKeepDraft(final, editing = false, canSend = true))
        assertTrue(voiceMustKeepDraft(final, editing = true, canSend = true))
        assertTrue(voiceMustKeepDraft(final, editing = false, canSend = false))
    }
}
